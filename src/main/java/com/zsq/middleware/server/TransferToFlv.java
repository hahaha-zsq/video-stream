package com.zsq.middleware.server;

import com.zsq.middleware.model.Device;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.*;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static com.zsq.middleware.server.Common.deviceContext;

/**
 * RTSP / RTMP 视频流 → FLV 实时转码服务
 *
 * 核心职责：
 * 1. 从摄像头或流媒体服务器拉取 RTSP / RTMP 视频流
 * 2. 使用 FFmpeg 实时转码为 FLV
 * 3. 通过 MediaChannel 分发给多个 HTTP-FLV 客户端
 * 4. 支持流复用（多个客户端共享同一路转码）
 *
 * 设计原则：
 * - Recorder 延迟创建（等待首帧视频）
 * - 低延迟（zerolatency + 时间戳继承）
 * - 稳定性优先（RTSP over TCP + 大探测参数）
 */
@Slf4j
@Data
public class TransferToFlv implements Runnable {

    /**
     * 转码线程运行标志
     * volatile：保证多线程下的可见性（事件线程 / 拉流线程）
     */
    private volatile boolean running = false;

    /**
     * FFmpeg 拉流器
     * 负责：网络连接 + 解复用 + 解码
     */
    private FFmpegFrameGrabber grabber;

    /**
     * FFmpeg 录制器（转码器）
     * 负责：编码 + 封装（FLV）
     */
    private FFmpegFrameRecorder recorder;

    /**
     * 内存输出流
     *
     * FFmpegFrameRecorder 会持续向该流写入 FLV 数据，
     * 每次发送给客户端后立即 reset()，避免无限增长
     */
    private ByteArrayOutputStream bos = new ByteArrayOutputStream();

    /**
     * FLV 文件头缓存
     *
     * 用途：
     * - 新客户端加入时，必须先发送 FLV Header
     * - 否则播放器无法解码后续数据
     *
     * 特点：
     * - 只在 recorder.start() 时生成一次
     * - 所有客户端共享
     */
    private volatile byte[] flvHeader;

    /**
     * 当前转码的设备信息
     */
    private Device currentDevice;

    /**
     * 媒体通道（HTTP 客户端管理）
     */
    private MediaChannel mediaChannel;

    /* ==========================================================
     * 1. 创建 Grabber（拉流器）
     * ========================================================== */

    /**
     * 创建并启动 FFmpegFrameGrabber
     *
     * @param url RTSP / RTMP 视频源地址
     */
    protected void createGrabber(String url) throws FFmpegFrameGrabber.Exception {

        // 创建拉流器（仅创建对象，不会立刻连接）
        grabber = new FFmpegFrameGrabber(url);

        /* ================= RTSP 稳定性关键参数 ================= */

        // 强制 RTSP over TCP
        // 原因：UDP 极易丢失 SPS/PPS，导致无法解析分辨率
        grabber.setOption("rtsp_transport", "tcp");

        // RTSP 连接超时（微秒）
        grabber.setOption("stimeout", "10000000");

        // 通用读写超时（微秒）
        grabber.setOption("rw_timeout", "15000000");

        /* ================= 流信息探测（非常关键） ================= */

        // 探测数据量（字节）
        // 用于读取足够多的数据来解析视频参数（宽高、编码等）
        grabber.setOption("probesize", "10000000");

        // 分析时长（微秒）
        // 给 FFmpeg 更多时间等待 SPS / PPS
        grabber.setOption("analyzeduration", "10000000");

        /* ================= 解码性能 ================= */

        // 解码线程数
        // RTSP 实时流 1~2 足够，避免 CPU 飙升
        grabber.setOption("threads", "1");

        // 网络接收缓冲区（字节）
        grabber.setOption("buffer_size", "1024000");

        // 真正开始连接流媒体服务器
        grabber.start();

        // ⚠️ 注意：此时不一定能立即拿到 width / height
        // 必须等到 grab() 到第一帧视频
    }

    /* ==========================================================
     * 2. 安全创建 Recorder（等待首帧视频）
     * ========================================================== */

    /**
     * 在拿到第一帧视频后再创建 Recorder
     *
     * @param firstVideoFrame 第一帧包含 image 的视频帧
     */
    protected void createRecorderSafely(Frame firstVideoFrame)
            throws FFmpegFrameRecorder.Exception {

        // 从 grabber 中获取真实分辨率
        int width = grabber.getImageWidth();
        int height = grabber.getImageHeight();

        // 如果还拿不到分辨率，直接失败（避免生成错误 FLV）
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("无法获取视频分辨率，Recorder 创建失败");
        }

        // 获取音频通道数
        int audioChannels = grabber.getAudioChannels();

        // RTSP 常见情况：无音频
        // audioChannels 必须明确为 0
        if (audioChannels <= 0) {
            audioChannels = 0;
        }

        // 创建 Recorder，输出到内存流
        recorder = new FFmpegFrameRecorder(
                bos,
                width,
                height,
                audioChannels
        );

        // 设置编码 / 封装参数
        setRecorderParams(recorder);

        // 启动 Recorder
        // ⚠️ 此时会自动写入 FLV Header
        recorder.start();

        // 1. 获取 Header
        flvHeader = bos.toByteArray();

        // 2. [新增] 立即发送 Header 给当前所有在线的客户端（包括第一个触发者）
        if (flvHeader != null && flvHeader.length > 0) {
            mediaChannel.sendData(flvHeader);
        }

        // 3. 然后再清空 bos，准备接收后续的视频帧
        bos.reset();
    }

    /* ==========================================================
     * 3. Recorder 编码参数配置
     * ========================================================== */

    /**
     * 配置 FFmpegFrameRecorder 的编码参数
     */
    private void setRecorderParams(FFmpegFrameRecorder r) {

        // 输出封装格式
        r.setFormat("flv");

        // 交错写入（音视频按时间戳交错）
        // 直播 / 转发场景必须为 true
        r.setInterleaved(true);

        /* ================= 视频编码 ================= */

        // 视频编码器：H.264
        r.setVideoCodec(avcodec.AV_CODEC_ID_H264);

        // 像素格式：YUV420P（兼容性最好）
        r.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);

        // 编码预设：ultrafast（最低延迟）
        r.setVideoOption("preset", "ultrafast");

        // 零延迟调优
        r.setVideoOption("tune", "zerolatency");

        // 恒定质量因子（推荐 18~28）
        r.setVideoOption("crf", "23");

        // 编码线程数
        r.setVideoOption("threads", "1");

        // 帧率
        r.setFrameRate(25);

        // GOP 大小（关键帧间隔）
        r.setGopSize(25);
        r.setOption("keyint_min", "25");

        /* ================= 音频编码 ================= */

        // 只有存在音频时才启用 AAC
        if (r.getAudioChannels() > 0) {
            r.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
        }

        // 最小延迟
        r.setMaxDelay(0);
    }

    /**
     * 获取 FLV Header（供新客户端发送）
     */
    public byte[] getFlvHeader() {
        return flvHeader;
    }

    /* ==========================================================
     * 4. 核心转码循环
     * ========================================================== */

    protected void transferToFlv() {

        try {
            // 1️⃣ 创建并启动 Grabber
            createGrabber(currentDevice.getRtspUrl());

            running = true;
            boolean recorderStarted = false;
            Frame frame;

            // 2️⃣ 主循环：持续拉流 + 转码
            while (running && (frame = grabber.grab()) != null) {

                // 只在第一次拿到视频帧时创建 Recorder
                if (!recorderStarted && frame.image != null) {
                    createRecorderSafely(frame);
                    recorderStarted = true;
                }

                // Recorder 已启动，开始转码
                if (recorderStarted) {

                    // ⚠️ 使用源流时间戳，防止音画不同步
                    recorder.setTimestamp(frame.timestamp);

                    // 写入一帧（音频或视频）
                    recorder.record(frame);

                    // 如果有数据输出，立即分发给客户端
                    if (bos.size() > 0) {
                        mediaChannel.sendData(bos.toByteArray());
                        bos.reset();
                    }
                }
            }

        } catch (Exception e) {
            log.error("视频转码异常，deviceId={}", currentDevice.getDeviceId(), e);
        } finally {
            release();
        }
    }

    /* ==========================================================
     * 5. 资源释放
     * ========================================================== */

    private void release() {
        running = false;
        try {
            if (recorder != null) recorder.close();
            if (grabber != null) grabber.close();
            if (bos != null) bos.close();
        } catch (IOException e) {
            log.warn("释放资源异常", e);
        } finally {
            closeMedia();
        }
    }

    /**
     * 关闭媒体通道并清理上下文
     */
    private void closeMedia() {
        deviceContext.remove(currentDevice.getDeviceId());
        mediaChannel.closeChannel();
    }

    /* ==========================================================
     * 6. Spring 事件监听（关闭流）
     * ========================================================== */

    @Async("winterNettyServerTaskExecutor")
    @EventListener
    public void onCloseStream(CloseStreamEvent event) {
        if (event.getDevice().getDeviceId()
                .equals(currentDevice.getDeviceId())) {
            running = false;
        }
    }

    /* ==========================================================
     * 7. 线程入口
     * ========================================================== */

    @Override
    public void run() {
        transferToFlv();
    }
}
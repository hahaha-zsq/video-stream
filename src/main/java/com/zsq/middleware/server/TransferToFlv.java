package com.zsq.middleware.server;

import com.zsq.middleware.model.Device;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * RTSP 转 FLV 转码工作线程
 * <p>
 * 核心功能:
 * 1. 从 RTSP 流中抓取视频帧
 * 2. 将视频帧实时编码为 FLV 格式
 * 3. 通过 MediaChannel 推送给所有 HTTP 客户端
 * 4. 支持外部停止控制
 * <p>
 * 设计理念:
 * - 不持有 Spring 上下文,保持线程的纯粹性
 * - 不监听事件,由外部(MediaStreamService)控制生命周期
 * - 使用 volatile 保证多线程可见性
 */
@Slf4j
@Data
public class TransferToFlv implements Runnable {

    /**
     * 转码任务运行标志
     * volatile 保证线程间可见性,用于优雅停止任务
     */
    private volatile boolean running = false;

    /**
     * FFmpeg 帧抓取器,负责从 RTSP 流中读取视频/音频帧
     */
    private FFmpegFrameGrabber grabber;

    /**
     * FFmpeg 帧录制器,负责将帧编码为 FLV 格式
     */
    private FFmpegFrameRecorder recorder;

    /**
     * FLV 数据缓冲区
     * 用于临时存储编码后的 FLV 数据,然后推送给客户端
     */
    private ByteArrayOutputStream bos = new ByteArrayOutputStream();

    /**
     * FLV 文件头
     * volatile 保证多线程访问安全
     * 新客户端加入时需要先发送此头部才能正常播放
     */
    private volatile byte[] flvHeader;

    /**
     * 当前处理的设备信息
     * 包含设备 ID 和 RTSP 流地址
     */
    private Device currentDevice;

    /**
     * 媒体通道,负责管理所有订阅此流的 HTTP 客户端
     * 转码后的数据通过此通道推送给所有客户端
     */
    private MediaChannel mediaChannel;

    /**
     * 停止转码任务
     * <p>
     * 供外部(MediaStreamService)调用,用于优雅停止转码线程
     * 通过设置 running = false,使转码循环自然退出
     */
    public void stop() {
        this.running = false;
        log.info("服务端:设备[{}] 转码任务接收到停止指令", currentDevice.getDeviceId());
    }

    /**
     * 线程入口方法
     * 启动转码任务
     */
    @Override
    public void run() {
        transferToFlv();
    }


    /**
     * RTSP 转 FLV 转码核心方法
     * <p>
     * 执行流程:
     * 1. 创建 RTSP 流抓取器,连接到摄像头
     * 2. 循环抓取视频/音频帧
     * 3. 获取到第一个视频帧后,创建 FLV 录制器
     * 4. 将每一帧编码为 FLV 格式
     * 5. 实时推送编码后的数据给所有 HTTP 客户端
     * 6. 异常或停止时释放所有资源
     * <p>
     * 设计要点:
     * - 延迟初始化录制器: 必须等到第一个视频帧才能获取分辨率等参数
     * - 边抓取边编码边推送: 实现真正的实时流媒体
     * - 优雅停止: 通过 running 标志控制,支持外部中断
     * - 资源安全: finally 块保证资源一定被释放
     */
    protected void transferToFlv() {
        try {
            // ========== 步骤1: 创建并启动 RTSP 流抓取器 ==========
            // 连接到设备的 RTSP 流,配置传输协议、超时等参数
            createGrabber(currentDevice.getRtspUrl());
            
            // ========== 步骤2: 设置运行标志,开始转码任务 ==========
            running = true;
            
            // 录制器启动标志,用于延迟初始化
            // 必须等到第一个视频帧才能创建录制器(需要获取分辨率)
            boolean recorderStarted = false;
            
            // 当前抓取的帧(可能是视频帧或音频帧)
            Frame frame;

            // ========== 步骤3: 主转码循环 ==========
            // 持续抓取帧,直到外部停止(running=false)或流结束(grab()返回null)
            while (running && (frame = grabber.grab()) != null) {
                
                // ========== 步骤3.1: 延迟创建录制器 ==========
                // 只在第一个视频帧到达时创建录制器
                // 判断条件:
                // - 录制器尚未启动(recorderStarted=false)
                // - 当前帧是视频帧(frame.image != null,音频帧的image为null)
                if (!recorderStarted && frame.image != null) {
                    // 创建录制器并配置编码参数
                    // 此时会生成 FLV 文件头并推送给所有客户端
                    createRecorderSafely(frame);
                    // 标记录制器已启动
                    recorderStarted = true;
                }
                
                // ========== 步骤3.2: 编码并推送帧数据 ==========
                // 只有在录制器启动后才能编码帧
                // (第一个视频帧之前的音频帧会被忽略)
                if (recorderStarted) {
                    // 设置当前帧的时间戳,用于音视频同步
                    recorder.setTimestamp(frame.timestamp);
                    
                    // 将帧编码为 FLV 格式,输出到 bos 字节流
                    recorder.record(frame);
                    
                    // 如果缓冲区有数据(编码后的 FLV 数据)
                    // 注意: 并非每次 record() 都会立即输出数据
                    // FFmpeg 可能会缓存多帧后一起输出
                    if (bos.size() > 0) {
                        // 将编码后的 FLV 数据推送给所有 HTTP 客户端
                        mediaChannel.sendData(bos.toByteArray());
                        
                        // 清空缓冲区,准备接收下一批数据
                        bos.reset();
                    }
                }
            }
            
            // ========== 循环结束说明 ==========
            // 退出循环的可能原因:
            // 1. running=false: 外部调用 stop() 方法停止任务
            // 2. frame=null: RTSP 流结束或连接断开
            
        } catch (Exception e) {
            // ========== 异常处理 ==========
            // 捕获所有异常,避免线程意外终止
            // 可能的异常:
            // - 网络异常: RTSP 连接失败、超时等
            // - 编码异常: 视频格式不支持、内存不足等
            // - IO 异常: 写入缓冲区失败等
            log.error("视频转码异常，deviceId={}", currentDevice.getDeviceId(), e);
            
        } finally {
            // ========== 资源释放 ==========
            // 无论正常结束还是异常,都要释放资源
            // 包括: 关闭抓取器、录制器、缓冲区、媒体通道
            release();
        }
    }

    /**
     * 创建并配置 RTSP 流抓取器
     * <p>
     * 参数说明:
     * - rtsp_transport=tcp: 使用 TCP 传输 RTSP,提高稳定性(UDP 可能丢包)
     * - stimeout=10000000: 套接字超时时间 10 秒,单位微秒
     * - rw_timeout=15000000: 读写超时 15 秒
     * - probesize=10000000: 探测流信息的数据量,适当增大可更准确识别流格式
     * - analyzeduration=10000000: 分析流的最大时长,单位微秒
     * - threads=1: 解码线程数,单线程避免并发问题
     * - buffer_size=1024000: 缓冲区大小约 1MB,平衡内存和流畅度
     *
     * @param url RTSP 流地址
     * @throws FFmpegFrameGrabber.Exception 创建或启动失败时抛出
     */
    protected void createGrabber(String url) throws FFmpegFrameGrabber.Exception {
        grabber = new FFmpegFrameGrabber(url);
        // 使用 TCP 传输,避免 UDP 丢包
        grabber.setOption("rtsp_transport", "tcp");
        // 套接字超时 10 秒
        grabber.setOption("stimeout", "10000000");
        // 读写超时 15 秒
        grabber.setOption("rw_timeout", "15000000");
        // 探测流格式的数据量
        grabber.setOption("probesize", "10000000");
        // 分析流的最大时长
        grabber.setOption("analyzeduration", "10000000");
        // 单线程解码
        grabber.setOption("threads", "1");
        // 1MB 缓冲区
        grabber.setOption("buffer_size", "1024000");
        // 启动抓取器,开始连接 RTSP 流
        grabber.start();
    }

    /**
     * 安全创建 FLV 录制器
     * <p>
     * 必须在获取到第一个视频帧后调用,因为需要从 grabber 中获取:
     * - 视频分辨率(宽高)
     * - 音频通道数
     * <p>
     * 执行步骤:
     * 1. 从抓取器获取流参数
     * 2. 创建录制器并配置编码参数
     * 3. 启动录制器,生成 FLV 文件头
     * 4. 将文件头推送给所有已连接的客户端
     *
     * @param firstVideoFrame 第一个视频帧(用于触发创建,当前未直接使用)
     * @throws FFmpegFrameRecorder.Exception 创建或启动失败时抛出
     */
    protected void createRecorderSafely(Frame firstVideoFrame) throws FFmpegFrameRecorder.Exception {
        // 步骤1: 从抓取器获取流参数
        int width = grabber.getImageWidth();     // 视频宽度
        int height = grabber.getImageHeight();   // 视频高度
        int audioChannels = grabber.getAudioChannels(); // 音频通道数
        // 如果没有音频,设置为 0
        if (audioChannels <= 0) audioChannels = 0;

        // 步骤2: 创建录制器,输出到字节流
        recorder = new FFmpegFrameRecorder(bos, width, height, audioChannels);
        // 配置编码参数(H.264 编码、低延迟等)
        setRecorderParams(recorder);
        // 步骤3: 启动录制器,此时会生成 FLV 文件头
        recorder.start();

        // 步骤4: 提取 FLV 文件头
        flvHeader = bos.toByteArray();
        // 将文件头推送给所有客户端(客户端必须先收到文件头才能播放)
        if (flvHeader != null && flvHeader.length > 0) {
            mediaChannel.sendData(flvHeader);
        }
        // 清空缓冲区,准备接收视频数据
        bos.reset();
    }

    /**
     * 配置 FLV 录制器的编码参数
     * <p>
     * 参数设计目标: 实时流媒体 + 低延迟 + 浏览器兼容
     * <p>
     * 参数说明:
     * - format=flv: 输出 FLV 格式,HTTP-FLV 协议标准格式
     * - interleaved=true: 交织音视频数据,提高兼容性
     * - videoCodec=H.264: 视频编码器,浏览器广泛支持
     * - pixelFormat=YUV420P: 像素格式,H.264 标准格式
     * - preset=ultrafast: 编码速度优先,牺牲压缩率换取实时性
     * - tune=zerolatency: 零延迟优化,禁用 B 帧等增加延迟的特性
     * - crf=23: 恒定质量因子,23 是质量和大小的平衡点(0-51,越小质量越高)
     * - threads=1: 单线程编码,避免并发问题
     * - frameRate=25: 帧率 25fps,常用标准帧率
     * - gopSize=25: GOP 大小 1 秒(25 帧),即每秒一个关键帧
     * - keyint_min=25: 最小关键帧间隔,保证快速响应
     * - audioCodec=AAC: 音频编码器,浏览器广泛支持
     * - maxDelay=0: 最大延迟为 0,实时优先
     *
     * @param r 待配置的录制器
     */
    private void setRecorderParams(FFmpegFrameRecorder r) {
        // 输出格式: FLV
        r.setFormat("flv");
        // 交织音视频,提高兼容性
        r.setInterleaved(true);
        // 视频编码: H.264
        r.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        // 像素格式: YUV420P
        r.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
        // 编码速度最快,实时性优先
        r.setVideoOption("preset", "ultrafast");
        // 零延迟调优
        r.setVideoOption("tune", "zerolatency");
        // 恒定质量因子 25,平衡质量和大小
        r.setVideoOption("crf", "25");
        // 单线程编码
        r.setVideoOption("threads", "1");
        // 帧率 25fps
        r.setFrameRate(25);
        // GOP 大小 1 秒(每秒一个关键帧)
        r.setGopSize(25);
        // 最小关键帧间隔
        r.setOption("keyint_min", "25");
        // 如果有音频,使用 AAC 编码
        if (r.getAudioChannels() > 0) {
            r.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
        }
        // 最大延迟为 0,实时优先
        r.setMaxDelay(0);
    }

    /**
     * 释放所有资源
     * <p>
     * 执行清理步骤:
     * 1. 停止运行标志
     * 2. 关闭录制器(释放编码器)
     * 3. 关闭抓取器(断开 RTSP 连接)
     * 4. 关闭字节流缓冲区
     * 5. 关闭媒体通道(断开所有客户端连接)
     * <p>
     * 注意:
     * - 此方法在 finally 块中调用,保证一定执行
     * - 各资源独立关闭,某个失败不影响其他资源释放
     * - 不再操作静态 Map,改为关闭自己的 mediaChannel
     */
    private void release() {
        // 停止运行标志
        running = false;
        try {
            // 关闭录制器,释放编码资源
            if (recorder != null) recorder.close();
            // 关闭抓取器,断开 RTSP 连接
            if (grabber != null) grabber.close();
            // 关闭字节流缓冲区
            if (bos != null) bos.close();
        } catch (IOException e) {
            log.warn("释放资源异常", e);
        } finally {
            // 关闭媒体通道,断开所有客户端连接
            // 不再操作静态 Map,只关闭自己的 mediaChannel
            if (mediaChannel != null) {
                mediaChannel.closeChannel();
            }
        }
    }
}
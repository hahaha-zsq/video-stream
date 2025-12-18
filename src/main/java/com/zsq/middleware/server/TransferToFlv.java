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
 * 纯粹的转码工作线程
 * 不再持有 Spring 上下文，也不监听事件
 */
@Slf4j
@Data
public class TransferToFlv implements Runnable {

    private volatile boolean running = false;
    private FFmpegFrameGrabber grabber;
    private FFmpegFrameRecorder recorder;
    private ByteArrayOutputStream bos = new ByteArrayOutputStream();
    private volatile byte[] flvHeader;
    private Device currentDevice;
    private MediaChannel mediaChannel;

    // --- 新增：供外部调用的停止方法 ---
    public void stop() {
        this.running = false;
        log.info("服务端：设备[{}] 转码任务接收到停止指令", currentDevice.getDeviceId());
    }

    @Override
    public void run() {
        transferToFlv();
    }

    protected void transferToFlv() {
        try {
            createGrabber(currentDevice.getRtspUrl());
            running = true;
            boolean recorderStarted = false;
            Frame frame;

            while (running && (frame = grabber.grab()) != null) {
                if (!recorderStarted && frame.image != null) {
                    createRecorderSafely(frame);
                    recorderStarted = true;
                }
                if (recorderStarted) {
                    recorder.setTimestamp(frame.timestamp);
                    recorder.record(frame);
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

    protected void createGrabber(String url) throws FFmpegFrameGrabber.Exception {
        grabber = new FFmpegFrameGrabber(url);
        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("stimeout", "10000000");
        grabber.setOption("rw_timeout", "15000000");
        grabber.setOption("probesize", "10000000");
        grabber.setOption("analyzeduration", "10000000");
        grabber.setOption("threads", "1");
        grabber.setOption("buffer_size", "1024000");
        grabber.start();
    }

    protected void createRecorderSafely(Frame firstVideoFrame) throws FFmpegFrameRecorder.Exception {
        int width = grabber.getImageWidth();
        int height = grabber.getImageHeight();
        int audioChannels = grabber.getAudioChannels();
        if (audioChannels <= 0) audioChannels = 0;

        recorder = new FFmpegFrameRecorder(bos, width, height, audioChannels);
        setRecorderParams(recorder);
        recorder.start();

        flvHeader = bos.toByteArray();
        if (flvHeader != null && flvHeader.length > 0) {
            mediaChannel.sendData(flvHeader);
        }
        bos.reset();
    }

    private void setRecorderParams(FFmpegFrameRecorder r) {
        r.setFormat("flv");
        r.setInterleaved(true);
        r.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        r.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
        r.setVideoOption("preset", "ultrafast");
        r.setVideoOption("tune", "zerolatency");
        r.setVideoOption("crf", "23");
        r.setVideoOption("threads", "1");
        r.setFrameRate(25);
        r.setGopSize(25);
        r.setOption("keyint_min", "25");
        if (r.getAudioChannels() > 0) {
            r.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
        }
        r.setMaxDelay(0);
    }

    private void release() {
        running = false;
        try {
            if (recorder != null) recorder.close();
            if (grabber != null) grabber.close();
            if (bos != null) bos.close();
        } catch (IOException e) {
            log.warn("释放资源异常", e);
        } finally {
            // 修改点：不再操作静态 Map，只关闭自己的 mediaChannel
            if (mediaChannel != null) {
                mediaChannel.closeChannel();
            }
        }
    }
}
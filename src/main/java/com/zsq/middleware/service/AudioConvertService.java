package com.zsq.middleware.service;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * 音频转换服务
 *
 * 功能说明：
 * 1. 支持将视频或音频文件中的音频轨道提取并转换为 MP3 格式
 * 2. 使用 JavaCV（FFmpeg）完成底层解码与编码
 * 3. 通过 Spring @Async 注解实现异步执行
 * 4. 适用于上传文件后的音频抽取、音频下载等业务场景
 */
@Slf4j
@Service
public class AudioConvertService {

    /**
     * 异步将音视频文件转换为 MP3 文件
     *
     * 使用说明：
     * - 输入文件可以是视频（mp4 / flv / avi 等）或音频文件
     * - 只会处理音频帧（忽略视频帧）
     * - 转换过程在自定义线程池中执行，不阻塞主线程
     *
     * 线程池说明：
     * - 使用项目中定义的 winterNettyServerTaskExecutor
     * - 适合 CPU 密集型 + IO 密集型混合任务（FFmpeg 转码）
     *
     * @param inputPath  原始音视频文件路径（本地文件系统）
     * @param outputPath 转换后的 MP3 文件路径
     * @return CompletableFuture<File>
     *         - 成功：返回生成的 MP3 文件
     *         - 失败：返回异常信息
     */
    @Async("winterNettyServerTaskExecutor")
    public CompletableFuture<File> convertToMp3Async(String inputPath, String outputPath) {

        // 记录异步任务开始日志，便于排查转码任务状态
        log.info("异步转换任务开始: {} -> {}", inputPath, outputPath);

        // 使用 try-with-resources，确保 FFmpeg 资源正确释放
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputPath)) {

            // 启动抓取器，开始读取输入音视频文件
            grabber.start();

            // 创建音频录制器（输出 MP3）
            // 第二个参数：音频通道数（从输入文件中读取）
            try (FFmpegFrameRecorder recorder =
                         new FFmpegFrameRecorder(outputPath, grabber.getAudioChannels())) {

                // 设置输出格式为 MP3
                recorder.setFormat("mp3");

                // 设置音频编码器为 MP3
                recorder.setAudioCodec(avcodec.AV_CODEC_ID_MP3);

                // 设置音频码率（128kbps，常用标准）
                recorder.setAudioBitrate(128000);

                // 设置采样率，保持与原始音频一致
                recorder.setSampleRate(grabber.getSampleRate());

                // 启动录制器，准备写入 MP3 文件
                recorder.start();

                Frame frame;

                // 循环读取输入文件的每一帧
                while ((frame = grabber.grab()) != null) {

                    // 只处理音频帧
                    // frame.samples != null 表示该帧包含音频数据
                    if (frame.samples != null) {
                        recorder.record(frame);
                    }
                }

                // 停止录制器，完成 MP3 文件写入
                recorder.stop();

                // 记录任务完成日志
                log.info("异步转换任务完成: {}", outputPath);

                // 返回成功的 CompletableFuture
                return CompletableFuture.completedFuture(new File(outputPath));
            }

        } catch (Exception e) {

            // 捕获转码过程中所有异常
            log.error("异步转换失败", e);

            // 返回失败的 CompletableFuture，便于调用方感知异常
            return CompletableFuture.failedFuture(e);
        }
    }
}
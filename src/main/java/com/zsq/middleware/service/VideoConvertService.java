package com.zsq.middleware.service;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class VideoConvertService {

    @Async("winterNettyServerTaskExecutor")
    public CompletableFuture<File> convertToEncryptedM3u8ZipAsync(
            String inputPath, String workDir, String taskId) {

        log.info("开始视频加密转换任务, ID: {}", taskId);
        File zipFile = new File(workDir, "video_package.zip");
        Path keyPath = Path.of(workDir, "video.key");

        try {
            // 1. 生成 16 字节密钥并保存
            byte[] keyBytes = new byte[16];
            new SecureRandom().nextBytes(keyBytes);
            Files.write(keyPath, keyBytes);

            // 2. 生成 Key Info 文件
            String keyApiUri =
                    "http://localhost:8080/api/video/key?taskId=" + taskId + "&authCode=secret123";

            Path keyInfoPath = Path.of(workDir, "encrypt.keyinfo");
            String keyInfoContent = keyApiUri + "\n" + keyPath.toAbsolutePath();
            Files.writeString(keyInfoPath, keyInfoContent);

            // 3. 转码生成 HLS
            String m3u8Path = Path.of(workDir, "index.m3u8").toString();

            try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputPath)) {
                grabber.start();

                try (FFmpegFrameRecorder recorder =
                             new FFmpegFrameRecorder(
                                     m3u8Path,
                                     grabber.getImageWidth(),
                                     grabber.getImageHeight(),
                                     grabber.getAudioChannels())) {

                    recorder.setFormat("hls");
                    recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
                    recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
                    recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);

                    recorder.setOption("hls_time", "60");
                    recorder.setOption("hls_list_size", "0");
                    recorder.setOption(
                            "hls_key_info_file",
                            keyInfoPath.toAbsolutePath().toString());

                    recorder.start();

                    Frame frame;
                    while ((frame = grabber.grab()) != null) {
                        recorder.record(frame);
                    }

                    recorder.stop();
                }
            }

            // 4. 仅打包 ts + m3u8
            packageTsAndM3u8ToZip(workDir, zipFile);

            // 5. 清理工作目录，仅保留 video.key
            cleanupWorkDir(workDir, keyPath, zipFile.toPath());

            log.info("转换、打包并清理完成: {}", zipFile.getAbsolutePath());
            return CompletableFuture.completedFuture(zipFile);

        } catch (Exception e) {
            log.error("视频处理异常", e);
            return CompletableFuture.failedFuture(e);
        }
    }


    /**
     * 异步将视频截取并转换为 GIF (体积优化版)
     * <p>
     * 优化策略：
     * 1. 自动缩小分辨率：最大宽度限制为 640px (按比例缩放高度)，大幅减小体积
     * 2. 降低帧率：限制最大 10fps
     * 3. 修复了之前的 error -22 问题 (RGB8 + 人工时间戳)
     */
    @Async("winterNettyServerTaskExecutor")
    public CompletableFuture<File> convertToGifAsync(String inputPath, String outputPath, double startTime, double endTime) {
        log.info("开始GIF转换任务(优化版): {} -> {}, start: {}, end: {}", inputPath, outputPath, startTime, endTime);

        // 定义 GIF 的最大宽度，超过此宽度将进行等比缩放
        final int MAX_WIDTH = 640;
        // 定义 GIF 的目标帧率，建议 8-12，太高体积会极大
        final double GIF_FRAME_RATE = 12.0;

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputPath)) {
            // 第一次启动，仅为了获取元数据
            grabber.start();

            // 1. 获取原始信息
            int originWidth = grabber.getImageWidth();
            int originHeight = grabber.getImageHeight();
            double originFrameRate = grabber.getFrameRate();

            // 2. 计算目标分辨率
            int targetWidth = originWidth;
            int targetHeight = originHeight;

            // 如果原始宽度为0 (异常情况)，尝试预读
            if (originWidth == 0 || originHeight == 0) {
                grabber.grabImage();
                originWidth = grabber.getImageWidth();
                originHeight = grabber.getImageHeight();
            }

            // 如果原视频宽度大于 MAX_WIDTH，则进行等比缩小
            if (originWidth > MAX_WIDTH) {
                double scale = (double) MAX_WIDTH / originWidth;
                targetWidth = MAX_WIDTH;
                targetHeight = (int) (originHeight * scale);
                // 确保高度是偶数 (某些编码器对奇数高度敏感)
                if (targetHeight % 2 != 0) {
                    targetHeight -= 1;
                }
                log.info("触发分辨率优化: {}x{} -> {}x{}", originWidth, originHeight, targetWidth, targetHeight);
            }

            // 3. 确定目标帧率
            double targetFrameRate = GIF_FRAME_RATE;
            if (originFrameRate > 0 && originFrameRate < GIF_FRAME_RATE) {
                targetFrameRate = originFrameRate; // 如果原视频帧率很低，就用原视频的
            }

            // 4. 重启 Grabber 以应用新的分辨率
            // 这一步很关键：让 FFmpeg 在解码阶段就缩放，比读取后再缩放性能更高
            grabber.stop();
            grabber.setImageWidth(targetWidth);
            grabber.setImageHeight(targetHeight);
            grabber.start();

            // 计算截取时间（微秒）
            long startMicro = (long) (startTime * 1_000_000);
            long endMicro = (long) (endTime * 1_000_000);

            // Seek 到开始位置
            if (startMicro > 0) {
                grabber.setTimestamp(startMicro);
            }

            // 5. 配置 Recorder
            try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputPath, targetWidth, targetHeight, 0)) {
                recorder.setFormat("gif");
                recorder.setVideoCodec(avcodec.AV_CODEC_ID_GIF);
                recorder.setFrameRate(targetFrameRate);

                // 使用 RGB8 避免 error -22 并减小色深压力
                recorder.setPixelFormat(avutil.AV_PIX_FMT_RGB8);
                recorder.setAudioChannels(0);

                // 启用全局调色板优化 (可选，加上这个参数可能进一步减小体积，但会稍微增加CPU消耗)
                // recorder.setOption("gifflags", "transdiff"); // 仅存储差异帧

                recorder.start();

                Frame frame;
                long frameIndex = 0;

                // 6. 循环抓取
                while ((frame = grabber.grabImage()) != null) {
                    long currentSrcTimestamp = grabber.getTimestamp();

                    if (currentSrcTimestamp > endMicro) {
                        break;
                    }

                    if (currentSrcTimestamp >= startMicro) {
                        // 人工生成时间戳，解决 error -22 问题
                        long syntheticTimestamp = (long) (1000000.0 * frameIndex / targetFrameRate);

                        recorder.setTimestamp(syntheticTimestamp);
                        recorder.record(frame);

                        frameIndex++;
                    }
                }
                recorder.stop();
            }

            log.info("GIF转换完成，输出文件大小: {} KB", new File(outputPath).length() / 1024);
            return CompletableFuture.completedFuture(new File(outputPath));

        } catch (Exception e) {
            log.error("GIF转换失败", e);
            File outFile = new File(outputPath);
            if (outFile.exists() && outFile.length() == 0) {
                outFile.delete();
            }
            return CompletableFuture.failedFuture(e);
        }
    }


    /**
     * 只打包 .ts 和 .m3u8 文件
     */
    private void packageTsAndM3u8ToZip(String sourceDir, File zipFile)
            throws Exception {

        Path root = Path.of(sourceDir);

        try (ZipOutputStream zos =
                     new ZipOutputStream(new FileOutputStream(zipFile))) {

            Files.walk(root)
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p ->
                            p.toString().endsWith(".ts") ||
                            p.toString().endsWith(".m3u8"))
                    .forEach(path -> {
                        try {
                            zos.putNextEntry(
                                    new ZipEntry(
                                            root.relativize(path).toString()));
                            Files.copy(path, zos);
                            zos.closeEntry();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    /**
     * 清理目录，仅保留 video.key
     */
    private void cleanupWorkDir(String workDir, Path keyPath, Path zipPath)
            throws Exception {

        Path root = Path.of(workDir);

        Files.walk(root)
                .filter(p -> !Files.isDirectory(p))
                .filter(p ->
                        !p.equals(keyPath) &&          // 保留 key
                        !p.equals(zipPath))             // 保留 zip
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception e) {
                        log.warn("文件删除失败: {}", p, e);
                    }
                });
    }
}
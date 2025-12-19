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
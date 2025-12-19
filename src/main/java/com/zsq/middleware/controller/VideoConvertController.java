package com.zsq.middleware.controller;

import com.zsq.middleware.service.VideoConvertService;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/video")
public class VideoConvertController {

    @Value("${temp_path}")
    private String tempPath ;

    private final VideoConvertService videoConvertService;
    private final Tika tika = new Tika();

    public VideoConvertController(VideoConvertService videoConvertService) {
        this.videoConvertService = videoConvertService;
    }

    /**
     * 1. 视频上传与异步转换接口
     */
    @PostMapping("/convert")
    public CompletableFuture<ResponseEntity<FileSystemResource>> uploadAndConvert(@RequestParam("file") MultipartFile file) {
        try {
            // Tika 类型检查
            String mimeType = tika.detect(file.getInputStream());
            if (!mimeType.startsWith("video/")) {
                return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
            }

            String taskId = UUID.randomUUID().toString();
            String tempDir = tempPath + "/vsm-" + taskId;
            Files.createDirectories(Path.of(tempDir));

            File srcFile = new File(tempDir + "/original_media");
            file.transferTo(srcFile);

            return videoConvertService.convertToEncryptedM3u8ZipAsync(srcFile.getAbsolutePath(), tempDir, taskId)
                    .thenApply(zip -> ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"video_m3u8.zip\"")
                            .contentType(MediaType.parseMediaType("application/zip"))
                            .body(new FileSystemResource(zip)))
                    .exceptionally(ex -> ResponseEntity.internalServerError().build());

        } catch (Exception e) {
            return CompletableFuture.completedFuture(ResponseEntity.internalServerError().build());
        }
    }

    /**
     * 2. 密钥获取鉴权接口 (Key Server)
     * 只有携带正确 authCode 的请求才能获取密钥
     */
    @GetMapping("/key")
    public ResponseEntity<byte[]> getSecretKey(@RequestParam String taskId, @RequestParam String authCode) {
        log.info("收到密钥请求, 任务ID: {}, 验证码: {}", taskId, authCode);

        // 鉴权逻辑：这里可以换成具体的业务登录状态校验
        if (!"secret123".equals(authCode)) {
            log.warn("密钥请求鉴权失败, authCode: {}", authCode);
            return ResponseEntity.status(403).build();
        }

        try {
            Path keyPath = Path.of(tempPath, "vsm-" + taskId, "video.key");
            log.info("尝试读取密钥文件: {}", keyPath.toAbsolutePath());
            
            if (Files.exists(keyPath)) {
                byte[] keyBytes = Files.readAllBytes(keyPath);
                log.info("密钥文件读取成功, 大小: {} bytes", keyBytes.length);
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(keyBytes);
            } else {
                log.error("密钥文件不存在: {}", keyPath.toAbsolutePath());
            }
        } catch (Exception e) {
            log.error("读取密钥失败", e);
        }
        return ResponseEntity.notFound().build();
    }


    /**
     * 2. 视频转 GIF 接口
     * 支持指定开始和结束时间，最大时长10秒
     */
    @PostMapping("/gif")
    public CompletableFuture<ResponseEntity<Resource>> convertToGif(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "startTime", defaultValue = "0") Double startTime,
            @RequestParam(value = "endTime", required = false) Double endTime) {

        // 1. 基础校验
        if (startTime < 0) {
            return CompletableFuture.completedFuture(ResponseEntity.badRequest().body(null));
        }

        // 2. 时间逻辑处理
        if (endTime == null) {
            endTime = startTime + 10.0;
        }

        double duration = endTime - startTime;
        if (duration <= 0 || duration > 10.0) {
            log.warn("GIF转换请求时长不合法: start={}, end={}, duration={}", startTime, endTime, duration);
            return CompletableFuture.completedFuture(ResponseEntity.badRequest().body(null));
        }

        try {
            // 3. Tika 类型检查
            String mimeType = tika.detect(file.getInputStream());
            if (!mimeType.startsWith("video/")) {
                log.warn("非视频文件尝试转GIF: {}", mimeType);
                return CompletableFuture.completedFuture(ResponseEntity.badRequest().body(null));
            }

            // 4. 准备临时目录和文件
            String fileId = UUID.randomUUID().toString();
            String tempDir = tempPath + "/vsm-gif/";
            Files.createDirectories(Path.of(tempDir));

            File sourceFile = new File(tempDir + fileId + "_src");
            file.transferTo(sourceFile);

            File outputFile = new File(tempDir + fileId + ".gif");
            // 获取原始文件名（可能包含中文）
            String originalFilename = Objects.requireNonNull(file.getOriginalFilename());
            String downloadName = originalFilename.replaceAll("\\.[^.]+$", "") + ".gif";

            // 5. 调用 Service 执行异步转换
            Double finalEndTime = endTime;
            return videoConvertService.convertToGifAsync(
                            sourceFile.getAbsolutePath(),
                            outputFile.getAbsolutePath(),
                            startTime,
                            finalEndTime
                    )
                    .thenApply(gifFile -> {
                        // 转换成功后，删除源视频文件
                        if (sourceFile.exists()) {
                            sourceFile.delete();
                        }

                        // 使用 Spring 的 ContentDisposition 处理中文文件名编码
                        // 这会生成类似: attachment; filename*=UTF-8''%E4%B8%AD%E6%96%87.gif 的标准头
                        ContentDisposition contentDisposition = ContentDisposition.attachment()
                                .filename(downloadName, StandardCharsets.UTF_8)
                                .build();

                        // 返回文件流
                        Resource resource = new FileSystemResource(gifFile);
                        return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                                .contentType(MediaType.parseMediaType("image/gif"))
                                .contentLength(gifFile.length())
                                .body(resource);
                    })
                    .exceptionally(ex -> {
                        log.error("GIF转换异常", ex);
                        if (sourceFile.exists()) {
                            sourceFile.delete();
                        }
                        return ResponseEntity.internalServerError().build();
                    });

        } catch (Exception e) {
            log.error("GIF请求处理失败", e);
            return CompletableFuture.completedFuture(ResponseEntity.internalServerError().build());
        }
    }
}
package com.zsq.middleware.controller;

import com.zsq.middleware.service.VideoConvertService;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
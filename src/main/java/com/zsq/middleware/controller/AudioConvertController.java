package com.zsq.middleware.controller;

import com.zsq.middleware.service.AudioConvertService;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 音频转换控制器
 * <p>
 * 功能说明：
 * 1. 提供 HTTP 接口，允许客户端上传音视频文件
 * 2. 异步将文件转换为 MP3 格式
 * 3. 转换完成后，直接以文件下载的方式返回给客户端
 * <p>
 * 设计特点：
 * - 使用 CompletableFuture 实现真正的异步 HTTP 返回
 * - 后端转码任务不阻塞 Servlet / Web 线程
 * - 适用于大文件、转码耗时较长的场景
 */
@RestController
@RequestMapping("/api/convert")
@Slf4j
public class AudioConvertController {
    @Value("${temp_path}")
    private String tempPath ;
    /**
     * 音频转换服务
     * 使用构造器注入，符合 Spring 推荐的依赖注入方式
     */
    private final AudioConvertService audioConvertService;
    private final Tika tika = new Tika(); // 实例化 Tika

    public AudioConvertController(AudioConvertService audioConvertService) {
        this.audioConvertService = audioConvertService;
    }

    /**
     * 异步将上传的音视频文件转换为 MP3 并下载
     * <p>
     * 接口说明：
     * - 请求方式：POST
     * - 请求地址：/api/convert/async-to-mp3
     * - 请求参数：multipart/form-data，参数名为 file
     * <p>
     * 响应说明：
     * - 转换成功：返回 MP3 文件下载
     * - 转换失败：返回 500 状态码
     *
     * @param file 客户端上传的音视频文件
     * @return CompletableFuture<ResponseEntity < Resource>>
     */
    @PostMapping("/async-to-mp3")
    public CompletableFuture<ResponseEntity<Resource>> asyncConvert(@RequestParam("file") MultipartFile file) {
        // 1. 使用 Tika 进行严格的类型校验
        try {
            String mimeType = tika.detect(file.getInputStream());
            log.info("上传文件探测类型: {}, 文件名: {}", mimeType, file.getOriginalFilename());

            if (!mimeType.startsWith("video/") && !mimeType.startsWith("audio/")) {
                log.warn("非法文件上传尝试: {}", mimeType);
                return CompletableFuture.completedFuture(
                        ResponseEntity.badRequest()
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(null) // 或者返回特定的错误信息对象
                );
            }
        } catch (Exception e) {
            log.error("文件类型检测失败", e);
            return CompletableFuture.completedFuture(ResponseEntity.internalServerError().build());
        }
        try {
            // ===================== 1. 准备临时文件目录 =====================

            // 获取 JVM 临时目录，例如：/tmp
            String tempDir = tempPath + "/vsm-async/";

            // 如果目录不存在则创建
            Files.createDirectories(Path.of(tempDir));

            // 生成唯一文件 ID，防止并发请求文件名冲突
            String fileId = UUID.randomUUID().toString();

            // 构建源文件（上传文件）路径
            File sourceFile = new File(tempDir + fileId + "_src");

            // 将 MultipartFile 写入本地临时文件
            file.transferTo(sourceFile);

            // 构建转换后的 MP3 文件路径
            File outputFile = new File(tempDir + fileId + ".mp3");

            // 构建下载时展示给用户的文件名
            // 保留原文件名（去除原后缀），替换为 .mp3
            String downloadName = Objects.requireNonNull(file.getOriginalFilename())
                                          .replaceAll("\\.[^.]+$", "") + ".mp3";

            // ===================== 2. 调用异步音频转换服务 =====================

            return audioConvertService
                    .convertToMp3Async(
                            sourceFile.getAbsolutePath(),
                            outputFile.getAbsolutePath()
                    )
                    // ===================== 3. 转换成功回调 =====================
                    .thenApply(convertedFile -> {

                        // 转换完成后删除源文件，释放磁盘空间
                        if (sourceFile.exists()) {
                            boolean delete = sourceFile.delete();
                        }

                        // 将生成的 MP3 文件封装为 Spring Resource
                        Resource resource = new FileSystemResource(convertedFile);

                        // 构建文件下载响应
                        return ResponseEntity.ok()
                                // 设置下载文件名
                                .header(
                                        HttpHeaders.CONTENT_DISPOSITION,
                                        "attachment; filename=\"" + downloadName + "\""
                                )
                                // 设置 MIME 类型为 MP3
                                .contentType(MediaType.parseMediaType("audio/mpeg"))
                                // 设置文件大小
                                .contentLength(convertedFile.length())
                                // 响应体
                                .body(resource);
                    })
                    // ===================== 4. 异步异常处理 =====================
                    .exceptionally(ex -> {

                        // 异常情况下也尝试删除源文件
                        if (sourceFile.exists()) {
                            boolean delete = sourceFile.delete();
                        }

                        // 返回 500 错误
                        return ResponseEntity.internalServerError().build();
                    });

        } catch (Exception e) {

            // 捕获同步阶段异常（如文件保存失败）
            return CompletableFuture.completedFuture(
                    ResponseEntity.internalServerError().build()
            );
        }
    }
}
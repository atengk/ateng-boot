package io.github.atengk.boot.sample.storage.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.storage.service.FileStorageDemoService;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.resource.StorageResource;
import io.github.atengk.boot.storage.template.StorageTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 样例工程通用存储 Web 控制器
 * 演示文件上传、流式下载（含 HTTP Range 206 断点续传）、HMAC 预签名外链与动态多源存储
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
public class FileController {

    private final StorageTemplate storageTemplate;
    private final FileStorageDemoService fileStorageDemoService;

    /**
     * 上传文件到默认存储源
     *
     * @param file 上传的表单文件
     * @param path 可选自定义存储路径（为空则使用原始文件名）
     * @return 存储元数据强类型契约
     */
    @PostMapping("/upload")
    public Result<StorageObject> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "path", required = false) String path) {
        String targetPath = (path != null && !path.isBlank()) ? path : file.getOriginalFilename();
        StorageObject object = storageTemplate.upload(targetPath, file);
        return Result.success(object);
    }

    /**
     * 流式下载存储文件（支持 Spring 原生 HTTP Range 206 断点续传）
     *
     * @param path 目标相对路径
     * @return 资源文件响应体
     * @throws IOException IO 异常
     */
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam("path") String path) throws IOException {
        StorageResource resource = storageTemplate.getResource(path);
        String filename = resource.getFilename();
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename)
                .contentType(MediaType.parseMediaType(resource.getContentType()))
                .contentLength(resource.contentLength())
                .body(resource);
    }

    /**
     * 获取指定存储对象的 HMAC 预签名只读下载外链
     *
     * @param path            目标相对路径
     * @param durationSeconds 外链有效秒数（默认 300 秒）
     * @return 预签名外链凭据
     */
    @GetMapping("/presigned-url")
    public Result<PresignedUrl> getPresignedUrl(
            @RequestParam("path") String path,
            @RequestParam(value = "durationSeconds", defaultValue = "300") long durationSeconds) {
        PresignedUrl presignedUrl = storageTemplate.getPresignedUrl(path, Duration.ofSeconds(durationSeconds));
        return Result.success(presignedUrl);
    }

    /**
     * 上传文件到 backup 隔离备份源（演示 @Storage 声明式切源）
     *
     * @param file 上传的表单文件
     * @param path 可选自定义存储路径
     * @return 存储元数据强类型契约
     * @throws IOException IO 异常
     */
    @PostMapping("/upload-backup")
    public Result<StorageObject> uploadToBackup(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "path", required = false) String path) throws IOException {
        String targetPath = (path != null && !path.isBlank()) ? path : file.getOriginalFilename();
        StorageObject object = fileStorageDemoService.uploadToBackup(
                targetPath,
                file.getInputStream(),
                file.getContentType(),
                file.getSize()
        );
        return Result.success(object);
    }
}

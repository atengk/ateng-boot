package io.github.atengk.boot.storage.controller;

import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.properties.StorageProperties;
import io.github.atengk.boot.storage.resource.StorageResource;
import io.github.atengk.boot.storage.util.StorageSecurityUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * 本地存储预签名防盗链只读安全下载端点
 * 校验 HMAC-SHA256 签名与时效，提供二次路径安全归一化与流式输出
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
@RestController
public class LocalStorageDownloadController {

    private final StorageProperties properties;
    private final StorageDriver storageDriver;

    /**
     * 构造本地存储下载端点控制器
     *
     * @param properties    存储顶层配置属性
     * @param storageDriver 活动存储驱动
     */
    public LocalStorageDownloadController(StorageProperties properties, StorageDriver storageDriver) {
        this.properties = properties;
        this.storageDriver = storageDriver;
    }

    /**
     * 预签名只读安全下载端点
     *
     * @param path      存储对象相对路径
     * @param expires   Unix 秒级过期时间戳
     * @param signature HMAC-SHA256 防篡改签名
     * @return 文件资源流响应体
     */
    @GetMapping("${ateng.boot.storage.local.download-endpoint:/storage/local/download}")
    public ResponseEntity<Resource> download(
            @RequestParam("path") String path,
            @RequestParam("expires") long expires,
            @RequestParam("signature") String signature) {

        // 1. 基础入参非空与二次路径安全清洗
        if (path == null || path.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        String cleanPath;
        try {
            cleanPath = StorageSecurityUtil.normalizePath(path);
        } catch (StorageException e) {
            log.warn("预签名下载路径非法或包含路径遍历攻击: rawPath={}, cause={}", path, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // 2. 时效性有效校验（过期阻断返回 403）
        long now = Instant.now().getEpochSecond();
        if (now > expires) {
            log.warn("预签名下载外链已过期: path={}, expires={}, now={}", cleanPath, expires, now);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // 3. 签名合法性防篡改校验（签名无效返回 403）
        String secretKey = resolveSecretKey();
        if (secretKey == null || secretKey.isBlank() || !StorageSecurityUtil.verifyHmacSha256(cleanPath + ":" + expires, secretKey, signature)) {
            log.warn("预签名下载外链签名校验失败或已被篡改: path={}, expires={}", cleanPath, expires);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // 4. 对象存在性检查
        if (!storageDriver.exists(cleanPath)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // 5. 流式响应写出并设置标准响应头
        try {
            StorageResource resource = new StorageResource(cleanPath, storageDriver);
            String filename = resource.getFilename();
            String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename)
                    .contentType(MediaType.parseMediaType(resource.getContentType()))
                    .contentLength(resource.contentLength())
                    .body(resource);
        } catch (IOException e) {
            log.error("读取预签名下载本地文件流异常: path={}", cleanPath, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 解析当前环境下的预签名 HMAC 密钥
     *
     * @return 密钥字符串
     */
    private String resolveSecretKey() {
        if (storageDriver instanceof LocalDriver localDriver && localDriver.getSecretKey() != null) {
            return localDriver.getSecretKey();
        }
        if (properties != null && properties.getLocal() != null && properties.getLocal().getSecretKey() != null) {
            return properties.getLocal().getSecretKey();
        }
        return null;
    }
}

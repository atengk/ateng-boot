package io.github.atengk.boot.storage.controller;

import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.factory.StorageDriverFactory;
import io.github.atengk.boot.storage.properties.StorageProperties;
import io.github.atengk.boot.storage.resource.StorageResource;
import io.github.atengk.boot.storage.util.StorageSecurityUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * 本地存储预签名防盗链只读安全下载端点
 * 校验 HMAC-SHA256 签名与时效，支持多存储源按 source 动态路由本地驱动，提供二次路径安全归一化与流式输出
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
@RestController
public class LocalStorageDownloadController {

    private final StorageProperties properties;
    private final Supplier<StorageDriverFactory> factorySupplier;
    private final Supplier<StorageDriver> defaultDriverSupplier;

    /**
     * 单源向后兼容构造函数
     *
     * @param properties    存储顶层配置属性
     * @param storageDriver 活动存储驱动
     */
    public LocalStorageDownloadController(StorageProperties properties, StorageDriver storageDriver) {
        this.properties = properties;
        this.factorySupplier = () -> null;
        this.defaultDriverSupplier = () -> storageDriver;
    }

    /**
     * 多源工厂直接装配构造函数
     *
     * @param properties    存储顶层配置属性
     * @param driverFactory 存储驱动多源工厂
     */
    public LocalStorageDownloadController(StorageProperties properties, StorageDriverFactory driverFactory) {
        this.properties = properties;
        this.factorySupplier = () -> driverFactory;
        this.defaultDriverSupplier = () -> null;
    }

    /**
     * Spring 自动装配完整构造函数（优先通过 StorageDriverFactory 按 source 动态路由）
     *
     * @param properties            存储顶层配置属性
     * @param driverFactoryProvider 多存储源工厂提供者
     * @param defaultDriverProvider 默认存储驱动提供者（可选兜底）
     */
    @Autowired
    public LocalStorageDownloadController(
            StorageProperties properties,
            ObjectProvider<StorageDriverFactory> driverFactoryProvider,
            ObjectProvider<StorageDriver> defaultDriverProvider) {
        this.properties = properties;
        this.factorySupplier = () -> driverFactoryProvider != null ? driverFactoryProvider.getIfAvailable() : null;
        this.defaultDriverSupplier = () -> defaultDriverProvider != null ? defaultDriverProvider.getIfAvailable() : null;
    }

    /**
     * 预签名只读安全下载端点（单源便捷重载，默认不绑定命名 source）
     *
     * @param path        存储对象相对路径
     * @param expires     Unix 秒级过期时间戳
     * @param signature   HMAC-SHA256 防篡改签名
     * @param filename    自定义下载文件名（可选）
     * @param contentType 自定义响应 Content-Type（可选）
     * @param disposition 自定义响应 Content-Disposition（可选）
     * @return 文件资源流响应体
     */
    public ResponseEntity<Resource> download(
            String path,
            long expires,
            String signature,
            String filename,
            String contentType,
            String disposition) {
        return download(path, expires, signature, filename, contentType, disposition, null);
    }

    /**
     * 预签名只读安全下载端点（支持多源按 source 参数动态路由）
     *
     * @param path        存储对象相对路径
     * @param expires     Unix 秒级过期时间戳
     * @param signature   HMAC-SHA256 防篡改签名
     * @param filename    自定义下载文件名（可选）
     * @param contentType 自定义响应 Content-Type（可选）
     * @param disposition 自定义响应 Content-Disposition（可选）
     * @param source      目标存储源标识（可选，空值按默认源 "default" 处理）
     * @return 文件资源流响应体
     */
    @GetMapping("${ateng.boot.storage.local.download-endpoint:/storage/local/download}")
    public ResponseEntity<Resource> download(
            @RequestParam("path") String path,
            @RequestParam("expires") long expires,
            @RequestParam("signature") String signature,
            @RequestParam(value = "filename", required = false) String filename,
            @RequestParam(value = "contentType", required = false) String contentType,
            @RequestParam(value = "disposition", required = false) String disposition,
            @RequestParam(value = "source", required = false) String source) {

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

        // 3. 按 source 动态解析目标本地驱动（非 LocalDriver 直接抛出语义化 StorageException，未知源返回 400）
        LocalDriver localDriver = LocalStorageEndpointSupport.resolveLocalDriver(
                factorySupplier.get(), defaultDriverSupplier.get(), source);
        if (localDriver == null) {
            log.warn("未找到对应的本地存储驱动: source={}", source);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // 4. 签名合法性防篡改校验（签名无效返回 403）
        String secretKey = LocalStorageEndpointSupport.resolveSecretKey(localDriver, properties);
        String signatureSource = LocalStorageEndpointSupport.normalizeOptionalSource(source);
        String payload = StorageSecurityUtil.buildPresignedDownloadSignaturePayload(
                cleanPath, expires, filename, contentType, disposition, signatureSource);
        if (secretKey == null || secretKey.isBlank() || !StorageSecurityUtil.verifyHmacSha256(payload, secretKey, signature)) {
            log.warn("预签名下载外链签名校验失败或已被篡改: path={}, expires={}, source={}", cleanPath, expires, signatureSource);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // 5. 对象存在性检查
        if (!localDriver.exists(cleanPath)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // 6. 流式响应写出并设置标准响应头
        try {
            StorageResource resource = new StorageResource(cleanPath, localDriver);
            String effectiveDisposition;
            if (disposition != null && !disposition.isBlank()) {
                effectiveDisposition = disposition.replace("\r", "").replace("\n", "").trim();
            } else if (filename != null && !filename.isBlank()) {
                effectiveDisposition = StorageSecurityUtil.formatContentDisposition(filename);
            } else {
                String rawName = resource.getFilename();
                effectiveDisposition = StorageSecurityUtil.formatContentDisposition(rawName);
            }

            String effectiveContentType = (contentType != null && !contentType.isBlank())
                    ? contentType.replace("\r", "").replace("\n", "").trim()
                    : resource.getContentType();

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, effectiveDisposition)
                    .contentType(MediaType.parseMediaType(effectiveContentType))
                    .contentLength(resource.contentLength())
                    .body(resource);
        } catch (IOException e) {
            log.error("读取预签名下载本地文件流异常: path={}", cleanPath, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}

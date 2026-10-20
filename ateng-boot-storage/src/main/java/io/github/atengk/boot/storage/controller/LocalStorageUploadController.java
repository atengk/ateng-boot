package io.github.atengk.boot.storage.controller;

import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.factory.StorageDriverFactory;
import io.github.atengk.boot.storage.guard.StorageGuard;
import io.github.atengk.boot.storage.properties.StorageProperties;
import io.github.atengk.boot.storage.util.StorageSecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

/**
 * 本地存储预签名防盗链直传写入安全端点
 * 基于 HTTP PUT 接收客户端直传数据，校验 HMAC-SHA256 签名、时效、限定 MIME 与体积上限，并联动 StorageGuard 执行魔数防卫
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Slf4j
@RestController
public class LocalStorageUploadController {

    private final StorageProperties properties;
    private final StorageDriver defaultDriver;
    private final ObjectProvider<StorageDriverFactory> driverFactoryProvider;
    private final StorageGuard storageGuard;

    /**
     * 常用单源环境构造函数
     *
     * @param properties 存储顶层配置
     * @param defaultDriver 默认活动存储驱动
     */
    public LocalStorageUploadController(StorageProperties properties, StorageDriver defaultDriver) {
        this(properties, defaultDriver, null, null);
    }

    /**
     * 完整依赖装配构造函数
     *
     * @param properties 存储顶层配置
     * @param defaultDriver 默认活动存储驱动
     * @param driverFactoryProvider 多存储源工厂提供者（可选）
     * @param storageGuardProvider 安全防卫嗅探器提供者（可选）
     */
    public LocalStorageUploadController(
            StorageProperties properties,
            StorageDriver defaultDriver,
            ObjectProvider<StorageDriverFactory> driverFactoryProvider,
            ObjectProvider<StorageGuard> storageGuardProvider) {
        this.properties = properties;
        this.defaultDriver = defaultDriver;
        this.driverFactoryProvider = driverFactoryProvider;
        this.storageGuard = (storageGuardProvider != null && storageGuardProvider.getIfAvailable() != null)
                ? storageGuardProvider.getIfAvailable()
                : (properties != null && properties.getGuard() != null)
                    ? new StorageGuard(properties.getGuard())
                    : new StorageGuard();
    }

    /**
     * 预签名写入安全直传端点（支持单文件直传与分片直传智能聚合）
     *
     * @param path 存储相对路径
     * @param expires Unix 秒级过期时间戳
     * @param signature HMAC-SHA256 防篡改签名
     * @param expectedContentType 严格限定的内容类型（可选）
     * @param maxBytes 允许上传的最大字节大小（可选，<= 0 不限制）
     * @param source 目标命名存储源（可选，多源模式下使用）
     * @param uploadId 分片上传会话标识（分片上传必填）
     * @param chunkIndex 分片正序编号（分片上传必填，1-based）
     * @param request HTTP 请求对象
     * @return 响应状态实体
     */
    @PutMapping("${ateng.boot.storage.local.upload-endpoint:/storage/local/upload}")
    public ResponseEntity<Void> upload(
            @RequestParam("path") String path,
            @RequestParam("expires") long expires,
            @RequestParam("signature") String signature,
            @RequestParam(value = "contentType", required = false) String expectedContentType,
            @RequestParam(value = "maxBytes", required = false, defaultValue = "-1") long maxBytes,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "uploadId", required = false) String uploadId,
            @RequestParam(value = "chunkIndex", required = false, defaultValue = "-1") int chunkIndex,
            HttpServletRequest request) {

        // 1. 基础入参非空与二次路径安全清洗
        if (path == null || path.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        String cleanPath;
        try {
            cleanPath = StorageSecurityUtil.normalizePath(path);
        } catch (StorageException e) {
            log.warn("预签名直传路径非法或包含路径遍历风险: rawPath={}, cause={}", path, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // 2. 时效性有效校验（过期阻断返回 403）
        long now = Instant.now().getEpochSecond();
        if (now > expires) {
            log.warn("预签名直传外链已过期: path={}, expires={}, now={}", cleanPath, expires, now);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // 3. 动态定位目标 LocalDriver 驱动实例
        LocalDriver localDriver = resolveLocalDriver(source);
        if (localDriver == null) {
            log.warn("未找到对应的本地存储驱动: source={}", source);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // 4. 签名合法性防篡改校验（签名无效返回 403）
        String secretKey = localDriver.getSecretKey();
        boolean isChunked = (uploadId != null && !uploadId.isBlank() && chunkIndex > 0);
        String signPayload;
        if (isChunked) {
            signPayload = cleanPath + ":" + expires + ":" + uploadId + ":" + chunkIndex
                    + (source != null && !source.isBlank() ? ":" + source : "");
        } else {
            String ct = (expectedContentType != null && !expectedContentType.isBlank())
                    ? expectedContentType.trim()
                    : "";
            signPayload = cleanPath + ":" + expires + ":" + ct + ":" + maxBytes
                    + (source != null && !source.isBlank() ? ":" + source : "");
        }

        if (secretKey == null || secretKey.isBlank() || !StorageSecurityUtil.verifyHmacSha256(signPayload, secretKey, signature)) {
            log.warn("预签名直传外链签名校验失败或已被篡改: path={}, expires={}, isChunked={}", cleanPath, expires, isChunked);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // 5. 校验 Content-Type（单文件直传且配置了限定类型时）
        String incomingContentType = request.getContentType();
        if (!isChunked && expectedContentType != null && !expectedContentType.isBlank()) {
            if (incomingContentType == null || !isContentTypeMatch(incomingContentType, expectedContentType.trim())) {
                log.warn("预签名直传 Content-Type 不匹配: expected={}, actual={}", expectedContentType, incomingContentType);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }
        }

        // 6. 校验最大允许体积（Content-Length 报头前置拦截）
        long contentLength = request.getContentLengthLong();
        if (maxBytes > 0 && contentLength > maxBytes) {
            log.warn("预签名直传文件大小超出限制 (Content-Length 拦截): size={}, maxBytes={}", contentLength, maxBytes);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }

        // 7. 执行流式数据写入与防卫校验
        try (InputStream rawStream = request.getInputStream()) {
            if (isChunked) {
                // 分片直接流式落盘
                localDriver.uploadChunk(uploadId, chunkIndex, rawStream, contentLength);
                return ResponseEntity.ok().build();
            }

            // 单文件直传：魔数安全嗅探防卫
            StorageGuard.GuardResult guardResult;
            try {
                guardResult = storageGuard.inspect(cleanPath, rawStream, incomingContentType);
            } catch (StorageException e) {
                log.warn("预签名直传文件安全嗅探拦截: path={}, cause={}", cleanPath, e.getMessage());
                return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
            }

            // 流式落盘与运行时体积熔断保护
            InputStream in = guardResult.getGuardedStream();
            if (maxBytes > 0) {
                in = new BoundedCountingInputStream(in, maxBytes);
            }

            try {
                localDriver.upload(cleanPath, in, guardResult.getDetectedMimeType(), contentLength);
                return ResponseEntity.ok().build();
            } catch (StorageException e) {
                if (isSizeExceeded(e)) {
                    log.warn("预签名直传文件流超出最大体积限制: path={}, maxBytes={}", cleanPath, maxBytes);
                    localDriver.delete(cleanPath);
                    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
                }
                log.error("预签名直传写入失败: path={}", cleanPath, e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        } catch (IOException e) {
            log.error("预签名直传文件流写入异常: path={}", cleanPath, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 解析当前请求绑定的本地存储驱动
     */
    private LocalDriver resolveLocalDriver(String source) {
        if (source != null && !source.isBlank() && driverFactoryProvider != null) {
            StorageDriverFactory factory = driverFactoryProvider.getIfAvailable();
            if (factory != null) {
                StorageDriver driver = factory.getDriver(source.trim());
                if (driver instanceof LocalDriver ld) {
                    return ld;
                }
            }
        }
        if (defaultDriver instanceof LocalDriver ld) {
            return ld;
        }
        return null;
    }

    /**
     * 判断实际请求 Content-Type 与期望 Content-Type 是否匹配（忽略 charset 等附属参数）
     */
    private boolean isContentTypeMatch(String actual, String expected) {
        String cleanActual = actual.split(";")[0].trim().toLowerCase();
        String cleanExpected = expected.split(";")[0].trim().toLowerCase();
        return cleanActual.equals(cleanExpected);
    }

    /**
     * 判定异常是否由上传体积超标引起
     */
    private boolean isSizeExceeded(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof MaxUploadSizeExceededException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 运行时体积计数与上限保护流
     */
    private static class BoundedCountingInputStream extends InputStream {
        private final InputStream in;
        private final long maxBytes;
        private long bytesRead = 0;

        public BoundedCountingInputStream(InputStream in, long maxBytes) {
            this.in = in;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b != -1) {
                bytesRead++;
                checkLimit();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                bytesRead += n;
                checkLimit();
            }
            return n;
        }

        private void checkLimit() throws MaxUploadSizeExceededException {
            if (maxBytes > 0 && bytesRead > maxBytes) {
                throw new MaxUploadSizeExceededException("上传数据流体积超出允许的最大上限: " + maxBytes + " 字节");
            }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /**
     * 上传体积超标受控异常
     */
    private static class MaxUploadSizeExceededException extends IOException {
        public MaxUploadSizeExceededException(String message) {
            super(message);
        }
    }
}

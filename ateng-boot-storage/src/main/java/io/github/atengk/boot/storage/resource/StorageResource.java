package io.github.atengk.boot.storage.resource;

import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.guard.StorageGuard;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.util.StorageSecurityUtil;
import lombok.Getter;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.WritableResource;

import java.io.FileNotFoundException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Spring 原生资源契约存储适配器
 * 实现 Spring 框架原生 Resource 与 WritableResource 接口
 * 支持 Spring MVC Controller 极简流式输出与断点续传（HTTP Range）
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class StorageResource extends AbstractResource implements WritableResource {

    @Getter
    private final String path;

    private final StorageDriver driver;

    private final StorageGuard storageGuard;

    private final Consumer<StorageObject> uploadedCallback;

    private StorageObject cachedMetadata;

    /**
     * 构造存储资源适配器
     *
     * @param path   对象相对路径
     * @param driver 绑定的底层存储驱动
     */
    public StorageResource(String path, StorageDriver driver) {
        this(path, driver, null, null, null);
    }

    /**
     * 携带预加载元数据构造存储资源适配器
     *
     * @param path     对象相对路径
     * @param driver   绑定的底层存储驱动
     * @param metadata 预加载的对象元数据
     */
    public StorageResource(String path, StorageDriver driver, StorageObject metadata) {
        this(path, driver, metadata, null, null);
    }

    /**
     * 携带安全守卫与上传完成回调构造存储资源适配器
     *
     * @param path             对象相对路径
     * @param driver           绑定的底层存储驱动
     * @param storageGuard     可选文件安全守卫
     * @param uploadedCallback 可选上传成功回调（用于发布事件）
     */
    public StorageResource(String path,
                           StorageDriver driver,
                           StorageGuard storageGuard,
                           Consumer<StorageObject> uploadedCallback) {
        this(path, driver, null, storageGuard, uploadedCallback);
    }

    private StorageResource(String path,
                            StorageDriver driver,
                            StorageObject metadata,
                            StorageGuard storageGuard,
                            Consumer<StorageObject> uploadedCallback) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Resource path must not be null or blank");
        }
        if (driver == null) {
            throw new IllegalArgumentException("StorageDriver must not be null");
        }
        this.path = path;
        this.driver = driver;
        this.cachedMetadata = metadata;
        this.storageGuard = storageGuard;
        this.uploadedCallback = uploadedCallback;
    }

    @Override
    public boolean exists() {
        return driver.exists(path);
    }

    @Override
    public boolean isReadable() {
        return exists();
    }

    @Override
    public boolean isWritable() {
        return true;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        try {
            return driver.download(path);
        } catch (StorageException e) {
            throw new FileNotFoundException("Storage object not found or inaccessible: " + path + ", cause: " + e.getMessage());
        }
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        if (storageGuard != null && storageGuard.isEnabled()) {
            storageGuard.validatePathAndExtension(cleanPath);
        }
        Path tempFile = Files.createTempFile("ateng-storage-resource-", ".tmp");
        OutputStream fileOut;
        try {
            fileOut = Files.newOutputStream(tempFile);
        } catch (Throwable ex) {
            Files.deleteIfExists(tempFile);
            throw ex;
        }
        long maxFileSizeBytes = (storageGuard != null) ? storageGuard.resolveMaxFileSizeBytes() : -1L;
        return new FilterOutputStream(fileOut) {
            private final AtomicBoolean closed = new AtomicBoolean(false);
            private final AtomicBoolean aborted = new AtomicBoolean(false);
            private long writtenBytes = 0L;

            @Override
            public void write(int b) throws IOException {
                checkWriteSizeLimit(1);
                out.write(b);
                writtenBytes++;
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                checkWriteSizeLimit(len);
                out.write(b, off, len);
                writtenBytes += len;
            }

            private void checkWriteSizeLimit(int incomingBytes) {
                if (maxFileSizeBytes > 0 && incomingBytes > 0 && (writtenBytes + incomingBytes) > maxFileSizeBytes) {
                    aborted.set(true);
                    throw new StorageException(StorageResultCode.STORAGE_SIZE_EXCEEDED,
                            "WritableResource 写入字节超出安全防卫配额上限: written=" + (writtenBytes + incomingBytes)
                                    + ", maxBytes=" + maxFileSizeBytes);
                }
            }

            @Override
            public void close() throws IOException {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                try {
                    super.close();
                    if (aborted.get()) {
                        return;
                    }
                    long size = Files.size(tempFile);
                    if (storageGuard != null) {
                        storageGuard.validateFileSize(size);
                    }
                    try (InputStream rawIn = Files.newInputStream(tempFile)) {
                        InputStream effectiveStream = rawIn;
                        String contentType = null;
                        if (storageGuard != null && storageGuard.isEnabled()) {
                            StorageGuard.GuardResult guardResult = storageGuard.inspect(cleanPath, rawIn, null);
                            effectiveStream = guardResult.getGuardedStream();
                            contentType = guardResult.getDetectedMimeType();
                        }
                        try (InputStream uploadStream = effectiveStream) {
                            cachedMetadata = driver.upload(cleanPath, uploadStream, contentType, size);
                        }
                    }
                    if (cachedMetadata != null && uploadedCallback != null) {
                        uploadedCallback.accept(cachedMetadata);
                    }
                } finally {
                    Files.deleteIfExists(tempFile);
                }
            }
        };
    }

    @Override
    public long contentLength() throws IOException {
        return getMetadata().getSize();
    }

    @Override
    public long lastModified() throws IOException {
        return getMetadata().getLastModified().toEpochMilli();
    }

    @Override
    public String getFilename() {
        return FileUtil.cleanFilename(path);
    }

    @Override
    public String getDescription() {
        return "StorageResource [path=" + path + "]";
    }

    /**
     * 获取推导出的 MIME 内容类型
     *
     * @return Content-Type 字符串
     */
    public String getContentType() {
        try {
            return getMetadata().getContentType();
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    /**
     * 获取存储对象元数据
     *
     * @return 强类型元数据对象
     * @throws FileNotFoundException 当目标对象不存在时抛出
     */
    public StorageObject getMetadata() throws FileNotFoundException {
        if (cachedMetadata != null) {
            return cachedMetadata;
        }
        try {
            this.cachedMetadata = driver.getMetadata(path);
            return this.cachedMetadata;
        } catch (StorageException e) {
            throw new FileNotFoundException("Storage metadata not found for path: " + path);
        }
    }
}

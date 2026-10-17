package io.github.atengk.boot.storage.resource;

import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.model.StorageObject;
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

    private StorageObject cachedMetadata;

    /**
     * 构造存储资源适配器
     *
     * @param path   对象相对路径
     * @param driver 绑定的底层存储驱动
     */
    public StorageResource(String path, StorageDriver driver) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Resource path must not be null or blank");
        }
        if (driver == null) {
            throw new IllegalArgumentException("StorageDriver must not be null");
        }
        this.path = path;
        this.driver = driver;
    }

    /**
     * 携带预加载元数据构造存储资源适配器
     *
     * @param path     对象相对路径
     * @param driver   绑定的底层存储驱动
     * @param metadata 预加载的对象元数据
     */
    public StorageResource(String path, StorageDriver driver, StorageObject metadata) {
        this(path, driver);
        this.cachedMetadata = metadata;
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
        Path tempFile = Files.createTempFile("ateng-storage-resource-", ".tmp");
        OutputStream fileOut = Files.newOutputStream(tempFile);
        return new FilterOutputStream(fileOut) {
            @Override
            public void close() throws IOException {
                super.close();
                try (InputStream in = Files.newInputStream(tempFile)) {
                    long size = Files.size(tempFile);
                    cachedMetadata = driver.upload(path, in, null, size);
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

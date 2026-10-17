package io.github.atengk.boot.storage.template;

import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.resource.StorageResource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储操作核心受管高阶门面
 * 负责参数清洗、Web 文件适配、Spring 资源桥接与多存储源路由调度
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
public class StorageTemplate implements StorageOperations {

    private final StorageDriver defaultDriver;

    private final Map<String, StorageDriver> driverMap;

    /**
     * 基于单一主驱动构造门面
     *
     * @param defaultDriver 默认存储驱动
     */
    public StorageTemplate(StorageDriver defaultDriver) {
        this(defaultDriver, Collections.emptyMap());
    }

    /**
     * 基于主驱动与多命名驱动映射构造门面
     *
     * @param defaultDriver 默认主存储驱动
     * @param driverMap     命名存储驱动字典
     */
    public StorageTemplate(StorageDriver defaultDriver, Map<String, StorageDriver> driverMap) {
        if (defaultDriver == null) {
            throw new IllegalArgumentException("Default StorageDriver must not be null");
        }
        this.defaultDriver = defaultDriver;
        this.driverMap = (driverMap != null && !driverMap.isEmpty())
                ? new HashMap<>(driverMap)
                : Collections.emptyMap();
    }

    /**
     * 编程式链式切源：获取指定命名存储源的操作契约
     *
     * @param sourceKey 存储源标识（如 "local", "oss-public", "minio-secure"）
     * @return 绑定目标存储源的操作门面实例
     */
    public StorageOperations use(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank() || "primary".equalsIgnoreCase(sourceKey) || "default".equalsIgnoreCase(sourceKey)) {
            return this;
        }
        StorageDriver targetDriver = driverMap.get(sourceKey);
        if (targetDriver == null) {
            throw new StorageException(StorageResultCode.STORAGE_SOURCE_NOT_FOUND, "未找到指定的存储源: " + sourceKey);
        }
        return new StorageTemplate(targetDriver, this.driverMap);
    }

    @Override
    public StorageObject upload(String path, InputStream in) {
        return upload(path, in, null, -1);
    }

    @Override
    public StorageObject upload(String path, InputStream in, String contentType) {
        return upload(path, in, contentType, -1);
    }

    @Override
    public StorageObject upload(String path, InputStream in, String contentType, long size) {
        return getDriver().upload(path, in, contentType, size);
    }

    @Override
    public StorageObject upload(String path, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new StorageException(StorageResultCode.UPLOAD_FAILED, "上传文件不能为空");
        }
        String targetPath = path;
        String originalFilename = file.getOriginalFilename();
        if (targetPath == null || targetPath.isBlank()) {
            targetPath = originalFilename;
        } else if (targetPath.endsWith("/") || targetPath.endsWith("\\")) {
            targetPath = targetPath + FileUtil.cleanFilename(originalFilename);
        }

        try (InputStream in = file.getInputStream()) {
            return upload(targetPath, in, file.getContentType(), file.getSize());
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.UPLOAD_FAILED, "读取 MultipartFile 输入流失败: " + originalFilename, e);
        }
    }

    @Override
    public InputStream download(String path) {
        return getDriver().download(path);
    }

    @Override
    public StorageResource getResource(String path) {
        return new StorageResource(path, getDriver());
    }

    @Override
    public StorageObject getMetadata(String path) {
        return getDriver().getMetadata(path);
    }

    @Override
    public boolean exists(String path) {
        return getDriver().exists(path);
    }

    @Override
    public boolean delete(String path) {
        return getDriver().delete(path);
    }

    @Override
    public boolean deleteBatch(List<String> paths) {
        return getDriver().deleteBatch(paths);
    }

    @Override
    public PresignedUrl getPresignedUrl(String path, Duration duration) {
        return getDriver().getPresignedUrl(path, duration);
    }

    @Override
    public PresignedUrl getPresignedUploadUrl(String path, Duration duration) {
        return getDriver().getPresignedUploadUrl(path, duration);
    }

    /**
     * 获取当前活动驱动实例
     *
     * @return StorageDriver 实例
     */
    protected StorageDriver getDriver() {
        return defaultDriver;
    }
}

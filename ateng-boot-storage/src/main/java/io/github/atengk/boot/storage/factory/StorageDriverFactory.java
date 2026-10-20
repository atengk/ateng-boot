package io.github.atengk.boot.storage.factory;

import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.properties.LocalStorageProperties;
import io.github.atengk.boot.storage.properties.StorageSourceProperties;
import io.github.atengk.boot.storage.util.StorageSecurityUtil;
import lombok.Getter;
import org.springframework.beans.factory.DisposableBean;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储驱动单例注册表与生命周期受管工厂
 * 统一纳管全部命名存储驱动（Local/S3 等），提供动态查找与 primary 缺省保底，
 * 并在 Spring 容器下线销毁时自动释放驱动持有的 HTTP 连接池与系统资源
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class StorageDriverFactory implements DisposableBean, AutoCloseable {

    private final Map<String, StorageDriver> driverMap = new ConcurrentHashMap<>();

    @Getter
    private final String primary;

    /**
     * 基础构造函数
     *
     * @param primary 默认主存储源名称
     */
    public StorageDriverFactory(String primary) {
        this(primary, Collections.emptyMap());
    }

    /**
     * 完整构造函数
     *
     * @param primary        默认主存储源名称
     * @param initialDrivers 初始驱动字典
     */
    public StorageDriverFactory(String primary, Map<String, StorageDriver> initialDrivers) {
        this.primary = (primary != null && !primary.isBlank()) ? primary.trim() : "primary";
        if (initialDrivers != null && !initialDrivers.isEmpty()) {
            this.driverMap.putAll(initialDrivers);
        }
    }

    /**
     * 注册命名存储驱动
     *
     * @param sourceKey 存储源标识（如 "local", "oss-public", "minio-secure"）
     * @param driver    驱动实例
     */
    public void registerDriver(String sourceKey, StorageDriver driver) {
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new StorageException(StorageResultCode.STORAGE_SOURCE_NOT_FOUND, "注册存储源标识 sourceKey 不能为空");
        }
        if (driver == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "注册存储驱动 driver 不能为空: " + sourceKey);
        }
        this.driverMap.put(sourceKey.trim(), driver);
    }

    /**
     * 根据存储源名称获取目标存储驱动
     * 若名称为空或为 primary/default 则返回主驱动
     *
     * @param sourceKey 存储源标识
     * @return 匹配的 StorageDriver 实例
     */
    public StorageDriver getDriver(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()
                || "primary".equalsIgnoreCase(sourceKey)
                || "default".equalsIgnoreCase(sourceKey)) {
            return getPrimaryDriver();
        }
        StorageDriver driver = this.driverMap.get(sourceKey.trim());
        if (driver == null) {
            throw new StorageException(StorageResultCode.STORAGE_SOURCE_NOT_FOUND, "未找到指定的存储源: " + sourceKey);
        }
        return driver;
    }

    /**
     * 获取默认主存储驱动（primary）
     *
     * @return 主驱动实例
     */
    public StorageDriver getPrimaryDriver() {
        StorageDriver driver = this.driverMap.get(this.primary);
        if (driver != null) {
            return driver;
        }
        if (!this.driverMap.isEmpty()) {
            return this.driverMap.values().iterator().next();
        }
        throw new StorageException(StorageResultCode.STORAGE_SOURCE_NOT_FOUND,
                "存储驱动工厂未注册任何可用驱动，无法获取主驱动: " + this.primary);
    }

    /**
     * 检查是否包含指定的存储驱动
     *
     * @param sourceKey 存储源标识
     * @return true 包含，false 不包含
     */
    public boolean containsDriver(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) {
            return !this.driverMap.isEmpty();
        }
        if ("primary".equalsIgnoreCase(sourceKey) || "default".equalsIgnoreCase(sourceKey)) {
            return this.driverMap.containsKey(this.primary) || !this.driverMap.isEmpty();
        }
        return this.driverMap.containsKey(sourceKey.trim());
    }

    /**
     * 获取所有已注册的存储驱动不可变只读视图
     *
     * @return 不可变驱动映射
     */
    public Map<String, StorageDriver> getAllDrivers() {
        return Collections.unmodifiableMap(this.driverMap);
    }

    /**
     * 根据 StorageSourceProperties 快速构建存储驱动
     *
     * @param properties 存储源配置属性
     * @return 对应的 StorageDriver 实例
     */
    public static StorageDriver createDriver(StorageSourceProperties properties) {
        if (properties == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "存储源配置属性不能为空");
        }
        String type = properties.getType();
        if ("s3".equalsIgnoreCase(type)) {
            return createS3DriverSafely(properties);
        }
        // 默认 local
        LocalStorageProperties local = properties.getLocal();
        String basePath = local.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            basePath = System.getProperty("java.io.tmpdir") + "/ateng-storage";
        }
        String secretKey = local.getSecretKey();
        if (secretKey == null || secretKey.isBlank()) {
            secretKey = StorageSecurityUtil.generateRandomSecretKey();
            local.setSecretKey(secretKey);
        }
        return new LocalDriver(basePath, local.getDomain(), secretKey, local.getDownloadEndpoint(), local.getUploadEndpoint());
    }

    /**
     * 安全反射创建 S3Driver，防御类路径缺失 AWS SDK
     */
    private static StorageDriver createS3DriverSafely(StorageSourceProperties properties) {
        try {
            Class.forName("software.amazon.awssdk.services.s3.S3Client");
            return S3DriverHelper.createS3Driver(properties);
        } catch (ClassNotFoundException e) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED,
                    "当前类路径中未引入 software.amazon.awssdk:s3 依赖，无法装配 S3 存储源", e);
        }
    }

    /**
     * 内部类加载隔离辅助类，防止在没有 AWS SDK 时直接内省引发 NoClassDefFoundError
     */
    private static class S3DriverHelper {
        private static StorageDriver createS3Driver(StorageSourceProperties properties) {
            software.amazon.awssdk.services.s3.S3Client s3Client =
                    io.github.atengk.boot.storage.driver.S3Driver.createS3Client(properties.getS3());
            software.amazon.awssdk.services.s3.presigner.S3Presigner s3Presigner =
                    io.github.atengk.boot.storage.driver.S3Driver.createS3Presigner(properties.getS3());
            return new io.github.atengk.boot.storage.driver.S3Driver(
                    s3Client, s3Presigner, properties.getS3().getBucket(), properties.getS3().getDomain(), properties.getS3().getSecretKey());
        }
    }

    @Override
    public void destroy() {
        close();
    }

    @Override
    public void close() {
        for (Map.Entry<String, StorageDriver> entry : this.driverMap.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception ignored) {
            }
        }
        this.driverMap.clear();
    }
}

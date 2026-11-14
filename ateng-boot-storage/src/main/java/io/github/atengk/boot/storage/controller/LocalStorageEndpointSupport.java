package io.github.atengk.boot.storage.controller;

import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.factory.StorageDriverFactory;
import io.github.atengk.boot.storage.properties.StorageProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * 本地存储安全端点共享解析辅助类
 * 统一提供多源环境下按 source 动态解析 LocalDriver 及有效 HMAC 密钥的公共逻辑
 *
 * @author Ateng
 * @since 2026-11-14
 */
@Slf4j
final class LocalStorageEndpointSupport {

    private LocalStorageEndpointSupport() {
    }

    /**
     * 按 source 参数动态定位目标 LocalDriver 实例
     * 若 source 为空或为 default/primary 则定位默认主驱动；若解析到的驱动非 LocalDriver 则抛出语义化 StorageException；
     * 若指定的命名存储源不存在则返回 null 交由控制器返回 400 Bad Request
     *
     * @param factory       多存储源驱动工厂（可选）
     * @param defaultDriver 默认活动存储驱动（factory 不可用时的兜底）
     * @param source        请求传入的存储源标识（可选）
     * @return 解析成功的 LocalDriver，未找到指定存储源时返回 null
     */
    static LocalDriver resolveLocalDriver(
            StorageDriverFactory factory,
            StorageDriver defaultDriver,
            String source) {
        String targetSource = (source == null || source.isBlank()) ? "default" : source.trim();
        if (factory != null) {
            try {
                StorageDriver resolved = factory.getDriver(targetSource);
                if (resolved instanceof LocalDriver ld) {
                    return ld;
                }
                throw new StorageException(StorageResultCode.OPERATION_FAILED,
                        "存储源 [" + targetSource + "] 对应的驱动类型为 [" + resolved.getClass().getSimpleName() + "]，仅支持 LocalDriver 提供本地预签名端点服务");
            } catch (StorageException e) {
                if (StorageResultCode.STORAGE_SOURCE_NOT_FOUND.equals(e.getResultCode())) {
                    log.warn("定位命名存储源失败: source={}, cause={}", targetSource, e.getMessage());
                    return null;
                }
                throw e;
            }
        }

        if (!"default".equalsIgnoreCase(targetSource) && !"primary".equalsIgnoreCase(targetSource)) {
            return null;
        }
        if (defaultDriver == null) {
            return null;
        }
        if (defaultDriver instanceof LocalDriver ld) {
            return ld;
        }
        throw new StorageException(StorageResultCode.OPERATION_FAILED,
                "默认存储源对应的驱动类型为 [" + defaultDriver.getClass().getSimpleName() + "]，仅支持 LocalDriver 提供本地预签名端点服务");
    }

    /**
     * 解析当前生效本地驱动的预签名 HMAC 密钥
     *
     * @param localDriver 目标本地驱动实例
     * @param properties  存储顶层配置属性
     * @return 密钥字符串，未配置时返回 null
     */
    static String resolveSecretKey(LocalDriver localDriver, StorageProperties properties) {
        if (localDriver != null && localDriver.getSecretKey() != null && !localDriver.getSecretKey().isBlank()) {
            return localDriver.getSecretKey();
        }
        if (properties != null && properties.getLocal() != null && properties.getLocal().getSecretKey() != null) {
            return properties.getLocal().getSecretKey();
        }
        return null;
    }
}

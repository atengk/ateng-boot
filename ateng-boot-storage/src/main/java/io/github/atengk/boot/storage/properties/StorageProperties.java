package io.github.atengk.boot.storage.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存储基础设施顶层配置属性
 * 支持单源极简平铺模式与多源 sources 层次化自动升阶
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.storage")
public class StorageProperties {

    /**
     * 是否启用存储基础设施自动装配
     */
    private boolean enabled = true;

    /**
     * 单源模式默认存储驱动类型（默认 local，可选 local / s3）
     */
    private String type = "local";

    /**
     * 默认主存储源标识（多源模式下生效，默认 primary）
     */
    private String primary = "primary";

    /**
     * 本地磁盘存储配置
     */
    private LocalStorageProperties local = new LocalStorageProperties();

    /**
     * S3 兼容协议存储配置
     */
    private S3StorageProperties s3 = new S3StorageProperties();

    /**
     * 命名多存储源配置字典（配置 sources 时自动激活多源拓扑）
     */
    private Map<String, StorageSourceProperties> sources = new LinkedHashMap<>();
}

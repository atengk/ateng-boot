package io.github.atengk.boot.storage.properties;

import lombok.Data;

/**
 * 命名多存储源配置属性
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Data
public class StorageSourceProperties {

    /**
     * 存储类型（local 或 s3）
     */
    private String type = "local";

    /**
     * 本地存储配置
     */
    private LocalStorageProperties local = new LocalStorageProperties();

    /**
     * S3 兼容协议存储配置
     */
    private S3StorageProperties s3 = new S3StorageProperties();
}

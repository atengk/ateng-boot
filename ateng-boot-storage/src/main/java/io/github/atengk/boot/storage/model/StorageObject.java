package io.github.atengk.boot.storage.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 存储对象元数据容器
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageObject implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 存储对象路径 / Key（规范化后的纯净相对路径）
     */
    private String path;

    /**
     * 文件名称（从路径提取的基础文件名，如 avatar.png）
     */
    private String filename;

    /**
     * 文件大小（字节数）
     */
    private Long size;

    /**
     * 文件 MIME 内容类型（如 image/png, application/pdf）
     */
    private String contentType;

    /**
     * 实体标签 / 摘要哈希（如 MD5 或 ETag）
     */
    private String etag;

    /**
     * 最后修改时间戳
     */
    private Instant lastModified;

    /**
     * 用户自定义元数据键值对
     */
    @Builder.Default
    private Map<String, String> userMetadata = Collections.emptyMap();

    /**
     * 是否为目录 / 虚拟前缀
     */
    @Builder.Default
    private Boolean directory = false;

    /**
     * 判断当前对象是否为目录
     *
     * @return true 为目录
     */
    public boolean isDirectory() {
        return Boolean.TRUE.equals(this.directory);
    }
}

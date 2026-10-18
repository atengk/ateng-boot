package io.github.atengk.boot.storage.event;

import lombok.Getter;

/**
 * 存储对象上传/创建完成事件
 * 当文件通过 StorageTemplate 成功上传、写入或由复制/移动生成时发布
 *
 * @author Ateng
 * @since 2026-10-18
 */
@Getter
public class StorageUploadedEvent extends StorageEvent {

    /**
     * 文件字节大小（未知时为 -1）
     */
    private final Long size;

    /**
     * 文件媒体类型（MIME Type）
     */
    private final String contentType;

    /**
     * 完整全参构造函数
     *
     * @param source      发布源
     * @param sourceKey   存储源标识
     * @param path        存储对象相对路径
     * @param size        文件字节大小
     * @param contentType 媒体类型
     */
    public StorageUploadedEvent(Object source, String sourceKey, String path, Long size, String contentType) {
        super(source, sourceKey, path);
        this.size = size != null ? size : -1L;
        this.contentType = contentType;
    }
}

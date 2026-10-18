package io.github.atengk.boot.storage.event;

import lombok.Getter;

/**
 * 存储对象删除完成事件
 * 当文件通过 StorageTemplate 成功删除或被移动移除源文件时发布
 *
 * @author Ateng
 * @since 2026-10-18
 */
@Getter
public class StorageDeletedEvent extends StorageEvent {

    /**
     * 基础构造函数
     *
     * @param source    发布源
     * @param sourceKey 存储源标识
     * @param path      被删除的存储对象相对路径
     */
    public StorageDeletedEvent(Object source, String sourceKey, String path) {
        super(source, sourceKey, path);
    }
}

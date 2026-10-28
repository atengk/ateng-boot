package io.github.atengk.boot.storage.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 存储生命周期事件抽象基类
 * 承载当前活动存储源标识与目标存储对象路径
 *
 * @author Ateng
 * @since 2026-10-18
 */
@Getter
public abstract class StorageEvent extends ApplicationEvent {

    /**
     * 当前活动的存储源标识（如 "local", "oss-public", "primary"）
     */
    private final String sourceKey;

    /**
     * 目标存储对象相对路径
     */
    private final String path;

    /**
     * 基础构造函数
     *
     * @param source    发布事件的组件源（如 StorageTemplate 实例）
     * @param sourceKey 存储源标识
     * @param path      目标存储对象相对路径
     */
    protected StorageEvent(Object source, String sourceKey, String path) {
        super(source);
        this.sourceKey = sourceKey;
        this.path = path;
    }
}

package io.github.atengk.boot.encrypt.event;

import org.springframework.context.ApplicationEvent;

/**
 * 密钥动态轮换或变更领域事件
 * 在 MultiKeyRegistry 发生主密钥轮换或命名密钥增删更新时发布，通知下游或已代理属性源刷新状态
 *
 * @author Ateng
 * @since 2026-11-01
 */
public class KeyRotatedEvent extends ApplicationEvent {

    private final String keyId;

    public KeyRotatedEvent(Object source, String keyId) {
        super(source);
        this.keyId = keyId;
    }

    /**
     * 获取发生变更或轮换的密钥别名（如 "default" 或命名 KeyId）
     *
     * @return 密钥别名
     */
    public String getKeyId() {
        return keyId;
    }
}

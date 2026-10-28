package io.github.atengk.boot.mail.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 动态发信账号缓存驱逐领域事件
 * <p>
 * 当外部介质（如数据库、配置中心等）中的发信账号发生更新或注销时广播发布此事件，
 * 支持单机或分布式集群（联动消息总线广播）热驱逐本地受管物理 Sender 缓存，
 * 触发下次发信时自动重新懒加载最新真明文凭据。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Getter
public class MailAccountEvictEvent extends ApplicationEvent {

    /**
     * 待驱逐的动态账号逻辑标识；若为 null 或空字符串则表示全量清空所有动态缓存
     */
    private final String accountName;

    /**
     * 构造动态账号精准驱逐事件
     *
     * @param source      事件发布源
     * @param accountName 待驱逐的账号标识
     */
    public MailAccountEvictEvent(Object source, String accountName) {
        super(source);
        this.accountName = accountName;
    }

    /**
     * 是否为全量清空驱逐事件
     *
     * @return true 若为全量清空
     */
    public boolean isClearAll() {
        return accountName == null || accountName.isBlank();
    }
}

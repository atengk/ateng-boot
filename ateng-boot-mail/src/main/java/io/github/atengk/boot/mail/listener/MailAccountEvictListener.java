package io.github.atengk.boot.mail.listener;

import io.github.atengk.boot.mail.event.MailAccountEvictEvent;
import io.github.atengk.boot.mail.registry.MailAccountRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import java.util.Objects;

/**
 * 动态发信账号驱逐领域事件监听器
 * <p>
 * 响应 {@link MailAccountEvictEvent} 领域事件，联动本地 {@link MailAccountRegistry}
 * 精准热注销或全量清理受管动态账号及其底层物理 Sender，支持分布式集群消息广播同步热刷新。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class MailAccountEvictListener {

    private static final Logger log = LoggerFactory.getLogger(MailAccountEvictListener.class);

    private final MailAccountRegistry accountRegistry;

    public MailAccountEvictListener(MailAccountRegistry accountRegistry) {
        this.accountRegistry = Objects.requireNonNull(accountRegistry, "accountRegistry must not be null");
    }

    /**
     * 处理动态账号驱逐领域事件
     *
     * @param event 账号驱逐领域事件
     */
    @EventListener
    public void onMailAccountEvict(MailAccountEvictEvent event) {
        if (event == null) {
            return;
        }
        if (event.isClearAll()) {
            log.info("Handling MailAccountEvictEvent: clearing all dynamic mail account caches");
            accountRegistry.clearDynamic();
        } else {
            String accountName = event.getAccountName();
            log.info("Handling MailAccountEvictEvent: evicting dynamic mail account cache for [{}]", accountName);
            accountRegistry.evictDynamic(accountName);
        }
    }
}

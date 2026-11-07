package io.github.atengk.boot.sample.social.listener;

import io.github.atengk.boot.social.event.SocialAuthenticatedEvent;
import io.github.atengk.boot.social.event.SocialBindTicketIssuedEvent;
import io.github.atengk.boot.social.event.SocialBoundEvent;
import io.github.atengk.boot.social.event.SocialUnboundEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 社交登录领域事件监听与审计流水样例
 * <p>
 * 演示通过 Spring 原生 {@link EventListener} 异步或同步接收四大领域事件
 * （{@link SocialAuthenticatedEvent}、{@link SocialBoundEvent}、{@link SocialUnboundEvent}、{@link SocialBindTicketIssuedEvent}），
 * 支撑安全风控审计、解除绑定流水入库及告警通知。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Getter
@Component
public class SampleSocialEventListener {

    private final List<AuditEventRecord> auditEvents = new CopyOnWriteArrayList<>();
    private final AtomicInteger unboundCount = new AtomicInteger();
    private volatile SocialUnboundEvent lastUnboundEvent;

    /**
     * 响应社交认证成功领域事件
     *
     * @param event 认证成功领域事件
     */
    @EventListener
    public void onAuthenticated(SocialAuthenticatedEvent event) {
        log.info("[领域事件] 社交认证成功: userId=[{}], appKey=[{}], openId=[{}]",
                event.getUserId(), event.getAppKey(), event.getSocialUser() != null ? event.getSocialUser().getOpenId() : null);
        auditEvents.add(AuditEventRecord.builder()
                .eventType("AUTHENTICATED")
                .userId(event.getUserId())
                .appKey(event.getAppKey())
                .timestamp(Instant.ofEpochMilli(event.getTimestamp()))
                .details("openId=" + (event.getSocialUser() != null ? event.getSocialUser().getOpenId() : null))
                .build());
    }

    /**
     * 响应社交账号绑定完成领域事件
     *
     * @param event 账号绑定完成领域事件
     */
    @EventListener
    public void onBound(SocialBoundEvent event) {
        log.info("[领域事件] 社交账号绑定完成: userId=[{}], appKey=[{}], openId=[{}]",
                event.getUserId(), event.getAppKey(), event.getSocialUser() != null ? event.getSocialUser().getOpenId() : null);
        auditEvents.add(AuditEventRecord.builder()
                .eventType("BOUND")
                .userId(event.getUserId())
                .appKey(event.getAppKey())
                .timestamp(Instant.ofEpochMilli(event.getTimestamp()))
                .details("openId=" + (event.getSocialUser() != null ? event.getSocialUser().getOpenId() : null))
                .build());
    }

    /**
     * 响应社交账号解绑领域事件
     *
     * @param event 解绑领域事件
     */
    @EventListener
    public void onSocialUnbound(SocialUnboundEvent event) {
        log.info("[领域事件] 监听到社交账号解绑事件: userId=[{}], appKey=[{}], unbindTime=[{}]",
                event.getUserId(), event.getAppKey(), event.getUnbindTime());
        this.lastUnboundEvent = event;
        this.unboundCount.incrementAndGet();
        auditEvents.add(AuditEventRecord.builder()
                .eventType("UNBOUND")
                .userId(event.getUserId())
                .appKey(event.getAppKey())
                .timestamp(event.getUnbindTime() != null ? event.getUnbindTime() : Instant.now())
                .details("unbindTime=" + event.getUnbindTime())
                .build());
    }

    /**
     * 响应两阶段绑定票据签发领域事件
     *
     * @param event 绑定票据签发事件
     */
    @EventListener
    public void onBindTicketIssued(SocialBindTicketIssuedEvent event) {
        String openId = event.getSocialUser() != null ? event.getSocialUser().getOpenId() : null;
        log.info("[领域事件] 两阶段绑定票据签发: appKey=[{}], openId=[{}], maskedTicket=[{}]",
                event.getAppKey(), openId, event.getMaskedTicket());
        auditEvents.add(AuditEventRecord.builder()
                .eventType("BIND_TICKET_ISSUED")
                .appKey(event.getAppKey())
                .timestamp(Instant.ofEpochMilli(event.getTimestamp()))
                .details("maskedTicket=" + event.getMaskedTicket() + ", openId=" + openId)
                .build());
    }

    /**
     * 获取不可修改的审计事件流水列表
     *
     * @return 审计事件流水列表
     */
    public List<AuditEventRecord> getAuditEvents() {
        return Collections.unmodifiableList(auditEvents);
    }

    /**
     * 获取累计接收到的解绑事件数
     *
     * @return 解绑事件数
     */
    public int getUnboundCount() {
        return unboundCount.get();
    }

    /**
     * 重置计数器与状态（供单元测试生命周期隔离使用）
     */
    public void reset() {
        auditEvents.clear();
        unboundCount.set(0);
        lastUnboundEvent = null;
    }

    /**
     * 领域审计事件流水快照模型
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditEventRecord implements Serializable {
        private static final long serialVersionUID = 1L;
        private String eventType;
        private String userId;
        private String appKey;
        private Instant timestamp;
        private String details;
    }
}

package io.github.atengk.boot.sample.social.listener;

import io.github.atengk.boot.social.event.SocialUnboundEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 社交解绑领域事件监听器样例
 * <p>
 * 演示通过 Spring 原生 {@link EventListener} 异步或同步接收 {@link SocialUnboundEvent}，
 * 用于安全审计、解除绑定流水入库及风控告警通知。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Getter
@Component
public class SampleSocialEventListener {

    private final AtomicInteger unboundCount = new AtomicInteger();
    private volatile SocialUnboundEvent lastUnboundEvent;

    /**
     * 响应社交账号解绑领域事件
     *
     * @param event 解绑领域事件
     */
    @EventListener
    public void onSocialUnbound(SocialUnboundEvent event) {
        log.info("[SampleSocialEventListener] 监听到社交账号解绑事件: userId=[{}], appKey=[{}], unbindTime=[{}]",
                event.getUserId(), event.getAppKey(), event.getUnbindTime());
        this.lastUnboundEvent = event;
        this.unboundCount.incrementAndGet();
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
        unboundCount.set(0);
        lastUnboundEvent = null;
    }
}

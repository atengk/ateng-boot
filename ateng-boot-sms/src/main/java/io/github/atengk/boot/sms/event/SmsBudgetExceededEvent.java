package io.github.atengk.boot.sms.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 短信单日成本预算熔断告警事件
 * 当全系统单日累计发信量触碰硬熔断阈值时向 Spring 容器广播
 * 供运维监控与群机器人报警等扩展监听消费
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
public class SmsBudgetExceededEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    /**
     * 单日配额阈值上限
     */
    private final long dailyQuota;

    /**
     * 触发熔断时系统当前累计发信量
     */
    private final long currentSent;

    /**
     * 本次被阻断的拟发条数
     */
    private final int attemptedCount;

    public SmsBudgetExceededEvent(Object source, long dailyQuota, long currentSent, int attemptedCount) {
        super(source);
        this.dailyQuota = dailyQuota;
        this.currentSent = currentSent;
        this.attemptedCount = attemptedCount;
    }
}

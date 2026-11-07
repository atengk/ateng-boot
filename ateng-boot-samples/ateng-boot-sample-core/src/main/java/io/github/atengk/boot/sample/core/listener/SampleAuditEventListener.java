package io.github.atengk.boot.sample.core.listener;

import io.github.atengk.boot.core.audit.OperateLogEvent;
import io.github.atengk.boot.core.audit.OperateLogModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 核心操作审计事件消费者组件
 * 演示在业务模块中通过 Spring @EventListener 解耦消费操作审计领域事件
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Component
public class SampleAuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(SampleAuditEventListener.class);

    private final AtomicInteger eventCount = new AtomicInteger(0);
    private volatile OperateLogModel lastReceivedModel;

    /**
     * 监听并消费结构化操作审计领域事件
     *
     * @param event 审计事件载荷
     */
    @EventListener
    public void onOperateLogEvent(OperateLogEvent event) {
        if (event == null || event.getLogModel() == null) {
            return;
        }
        this.lastReceivedModel = event.getLogModel();
        int count = this.eventCount.incrementAndGet();
        log.info("[AUDIT_EVENT_CONSUMED] 接收到操作审计事件 #{} [module={}, type={}, user={}]",
                count,
                lastReceivedModel.getModule(),
                lastReceivedModel.getOperateType(),
                lastReceivedModel.getUserName());
    }

    /**
     * 获取累计接收到的审计事件数量
     *
     * @return 事件计数
     */
    public int getEventCount() {
        return eventCount.get();
    }

    /**
     * 获取最新接收到的审计事件模型
     *
     * @return 审计模型
     */
    public OperateLogModel getLastReceivedModel() {
        return lastReceivedModel;
    }

    /**
     * 重置事件计数器与最新模型（供测试清理使用）
     */
    public void reset() {
        this.eventCount.set(0);
        this.lastReceivedModel = null;
    }
}

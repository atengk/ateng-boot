package io.github.atengk.boot.sample.liteflow.component.dynamic;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.DynamicFlowContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * 动态规则外部风控合规慢查询审计组件
 * 当节点绑定 {@code SLOW_AUDIT} 标签时模拟外部合规接口慢查询耗时，用于验证慢节点阈值告警与指标导出
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
@Component("dynamicComplianceAuditCmp")
public class DynamicComplianceAuditCmp extends BaseNodeComponent<DynamicFlowContext> {

    private static final String SLOW_AUDIT_TAG = "SLOW_AUDIT";
    private static final long SIMULATED_DELAY_MS = 85L;

    @Override
    public void process(DynamicFlowContext context) {
        String tag = getTag();
        if (SLOW_AUDIT_TAG.equals(tag)) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(SIMULATED_DELAY_MS));
            if (Thread.currentThread().isInterrupted()) {
                log.warn("【动态规则-合规审计】外部风控合规慢查询等待被中断");
            }
        }
        String payload = context.resolvePayload(getRequestData());
        context.put("complianceStatus", "PASSED");
        context.put("complianceTag", tag != null ? tag : "FAST");
        context.setResult("COMPLIANCE_AUDITED:" + payload);
        log.info("【动态规则-合规审计】完成外部风控合规核验：tag={}, payload={}", tag, payload);
    }
}

package io.github.atengk.boot.sample.job.xxl.handler;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每日电商订单对账与告警风暴抑制自愈演练任务处理器
 *
 * <p>模拟每日订单对账场景，支持通过参数注入借贷不平差错，演示首发立即告警、抑制窗口内聚合为摘要卡片，
 * 以及平账成功后自动发送绿色自愈卡片的完整状态机流转。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Component
public class OrderReconciliationJobHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderReconciliationJobHandler.class);

    private final AtomicBoolean faultInjected = new AtomicBoolean(false);

    /**
     * 设置是否注入对账不平故障
     *
     * @param fault 是否注入差错
     */
    public void setFaultInjected(boolean fault) {
        this.faultInjected.set(fault);
    }

    /**
     * 每日订单对账任务：根据入参或故障标记模拟对账差错抛错或平账成功
     */
    @XxlJob("orderReconciliationJob")
    @AtengXxlJob(value = "orderReconciliationJob")
    public void executeOrderReconciliation() {
        String param = XxlJobHelper.getJobParam();
        boolean hasDiff = faultInjected.get() || (param != null && param.contains("hasDiff=true"));
        if (hasDiff) {
            log.error(">>>>>> [OrderReconciliationJobHandler] 发现订单与支付渠道对账差错，借贷金额不平！");
            throw new RuntimeException("对账发现金额差错不平，触发差错调账告警！");
        }
        log.info(">>>>>> [OrderReconciliationJobHandler] 订单每日对账完成，借贷平衡。");
        XxlJobHelper.handleSuccess("订单每日对账完成，平账成功");
    }

    /**
     * 兼容别名任务：模拟告警演练任务
     */
    @XxlJob("alertDemoJob")
    @AtengXxlJob(value = "alertDemoJob")
    public void executeAlertDemo() {
        executeOrderReconciliation();
    }
}

package io.github.atengk.boot.sample.ai.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.atengk.boot.ai.annotation.RiskLevel;
import io.github.atengk.boot.ai.annotation.SensitiveTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 订单高危退款受管工具
 * <p>
 * 声明 {@link SensitiveTool} 注解，在执行前由沙箱自动拦截并签发待审批单据，需人机协作审批后方可执行。
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Component
public class OrderRefundTool {

    private static final Logger log = LoggerFactory.getLogger(OrderRefundTool.class);

    private final AtomicInteger refundExecutionCounter = new AtomicInteger(0);

    /**
     * 执行订单高危退款操作
     *
     * @param orderId 待退款订单业务编号
     * @param amount  退款金额（元）
     * @param reason  退款审核原因与业务依据
     * @return 退款执行结果文本
     */
    @Tool("执行订单退款高危操作，向原支付渠道退还指定金额")
    @SensitiveTool(riskLevel = RiskLevel.HIGH, approvalType = "ORDER_REFUND", description = "订单高危退款出金操作")
    public String executeRefund(
            @P("待退款订单业务编号，例如 ORD_20261106001") String orderId,
            @P("退款金额，单位元，例如 199.99") Double amount,
            @P("退款审核原因与业务依据") String reason) {
        log.info("[TOOL-INVOKE] 订单退款真实执行中: orderId={}, amount={}, reason={}", orderId, amount, reason);
        refundExecutionCounter.incrementAndGet();
        return String.format("订单 [%s] 退款成功，金额: ￥%.2f，原因: %s", orderId, amount, reason);
    }

    /**
     * 获取真实执行退款的调用累计次数
     *
     * @return 累计执行次数
     */
    public int getRefundExecutionCount() {
        return refundExecutionCounter.get();
    }

    /**
     * 重置退款计数器（用于单测或体验清理现场）
     */
    public void resetCounter() {
        refundExecutionCounter.set(0);
    }
}

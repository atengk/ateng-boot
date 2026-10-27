package io.github.atengk.boot.sample.core.service;

import io.github.atengk.boot.core.pipeline.Pipeline;
import io.github.atengk.boot.core.pipeline.PipelineInterruptible;
import io.github.atengk.boot.core.pipeline.PipelineResult;
import io.github.atengk.boot.core.pipeline.PipelineStage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Core 场景六：强类型业务管道与责任链编排服务
 * 演示在零 Web 依赖下使用 {@link Pipeline} 编排订单提单前置校验流水线，支持阶段短路、中断阻断与快速失败
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Slf4j
@Service
public class CorePipelineScenarioService {

    /**
     * 提单上下文模型
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderCheckoutContext implements PipelineInterruptible {
        private String orderNo;
        private Long userId;
        private BigDecimal orderAmount;
        private List<OrderItem> items;
        private boolean riskPassed;
        private boolean inventoryReserved;
        private BigDecimal discountAmount;

        private boolean interrupted;
        private String interruptReason;

        @Builder.Default
        private List<String> auditTrail = new ArrayList<>();

        @Override
        public boolean isInterrupted() {
            return interrupted;
        }

        @Override
        public String getInterruptReason() {
            return interruptReason;
        }

        @Override
        public void interrupt(String reason) {
            this.interrupted = true;
            this.interruptReason = reason;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItem {
        private String skuCode;
        private Integer quantity;
        private Integer availableStock;
    }

    /**
     * 构建开箱即用的订单结算前置校验流水线
     *
     * @return 管道实例
     */
    public Pipeline<OrderCheckoutContext> buildCheckoutPipeline() {
        return Pipeline.<OrderCheckoutContext>builder()
                // 阶段一：基础参数与金额合法性前置检查
                .addStage("基础参数检查", ctx -> {
                    ctx.getAuditTrail().add("基础参数检查");
                    boolean valid = ctx.getOrderNo() != null
                            && ctx.getUserId() != null
                            && ctx.getOrderAmount() != null
                            && ctx.getOrderAmount().compareTo(BigDecimal.ZERO) > 0
                            && ctx.getItems() != null
                            && !ctx.getItems().isEmpty();
                    if (!valid) {
                        ctx.interrupt("订单基础参数或金额非法");
                        return false;
                    }
                    return true;
                })
                // 阶段二：用户风控信用等级评估
                .addStage(PipelineStage.ofConsumer("风控合规校验", ctx -> {
                    ctx.getAuditTrail().add("风控合规校验");
                    // 模拟风控：特定黑名单用户直接熔断
                    if (Long.valueOf(9999L).equals(ctx.getUserId())) {
                        ctx.interrupt("用户命中欺诈风险黑名单，禁止下单");
                    } else {
                        ctx.setRiskPassed(true);
                    }
                }))
                // 阶段三：多 SKU 可售库存预占校验（短路核心场景）
                .addStage("库存充足性检查", ctx -> {
                    ctx.getAuditTrail().add("库存充足性检查");
                    for (OrderItem item : ctx.getItems()) {
                        if (item.getAvailableStock() < item.getQuantity()) {
                            ctx.interrupt("商品 [" + item.getSkuCode() + "] 库存不足，当前可用 " + item.getAvailableStock());
                            return false;
                        }
                    }
                    ctx.setInventoryReserved(true);
                    return true;
                })
                // 阶段四：营销优惠与满减计算
                .addStage(PipelineStage.ofConsumer("营销优惠计算", ctx -> {
                    ctx.getAuditTrail().add("营销优惠计算");
                    if (ctx.getOrderAmount().compareTo(BigDecimal.valueOf(100)) >= 0) {
                        ctx.setDiscountAmount(BigDecimal.valueOf(20));
                    } else {
                        ctx.setDiscountAmount(BigDecimal.ZERO);
                    }
                }))
                .build();
    }

    /**
     * 执行提单前置校验流水线
     *
     * @param context 提单上下文
     * @return 执行结果契约
     */
    public PipelineResult processCheckout(OrderCheckoutContext context) {
        Pipeline<OrderCheckoutContext> pipeline = buildCheckoutPipeline();
        PipelineResult result = pipeline.execute(context);
        log.info("订单提单流水线执行完成: orderNo={}, isSuccess={}, isTerminated={}, terminatedStage={}, reason={}",
                context.getOrderNo(), result.isSuccess(), result.isTerminated(),
                result.getTerminatedStageName(), result.getInterruptReason());
        return result;
    }
}

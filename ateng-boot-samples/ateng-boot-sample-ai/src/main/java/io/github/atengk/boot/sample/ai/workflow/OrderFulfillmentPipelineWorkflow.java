package io.github.atengk.boot.sample.ai.workflow;

import io.github.atengk.boot.ai.graph.TypedKey;
import io.github.atengk.boot.ai.graph.pipeline.PipelineStage;
import io.github.atengk.boot.ai.graph.pipeline.SequentialPipelineWorkflow;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.sample.ai.model.OrderFulfillmentState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 电商订单履约确定性顺序流水线认知工作流
 * <p>
 * 串联以下 5 个确定性业务阶段：
 * <ul>
 *     <li><b>order_verification (订单核验)</b>：验证订单参数合法性并预占库存；</li>
 *     <li><b>risk_evaluation (风控评级)</b>：评估交易信用与金额风控，支持 VIP 特权条件跳过；</li>
 *     <li><b>manual_audit (人工复审)</b>：高危订单触发前置静态挂起中断（HITL），等待人工决断注入；</li>
 *     <li><b>payment_clearing (资金清算)</b>：执行支付扣减与对账清结算；</li>
 *     <li><b>auto_delivery (自动派发)</b>：根据商品类型自动生成实物快递运单或虚拟兑换密钥。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Component
public class OrderFulfillmentPipelineWorkflow extends SequentialPipelineWorkflow<OrderFulfillmentState> {

    public static final String WORKFLOW_NAME = "orderFulfillmentWorkflow";

    public static final String STAGE_VERIFY = "order_verification";
    public static final String STAGE_RISK = "risk_evaluation";
    public static final String STAGE_MANUAL_AUDIT = "manual_audit";
    public static final String STAGE_PAYMENT = "payment_clearing";
    public static final String STAGE_DELIVERY = "auto_delivery";

    public OrderFulfillmentPipelineWorkflow() {
        super(WORKFLOW_NAME, OrderFulfillmentState::new);
    }

    @Override
    protected List<PipelineStage<OrderFulfillmentState>> registerStages() {
        return List.of(
                // 阶段 1：订单核验与库存预占
                PipelineStage.of(STAGE_VERIFY, "订单基础信息核验与库存预占", state -> {
                    log.info("[PIPELINE-STAGE:{}] 执行订单核验: orderId={}, amount={}",
                            STAGE_VERIFY, state.getOrderId(), state.getAmount());
                    if (state.getOrderId() == null || state.getOrderId().isBlank()) {
                        throw new IllegalArgumentException("订单编号不能为空");
                    }
                    return Map.of(
                            OrderFulfillmentState.VERIFIED.name(), true,
                            "stockReserved", true,
                            "verifiedAt", LocalDateTime.now().toString()
                    );
                }),

                // 阶段 2：风控评估 (支持 VIP 特权条件跳过)
                PipelineStage.of(STAGE_RISK, "风控信用反欺诈评级", state -> {
                    BigDecimal amount = state.getAmount();
                    int creditScore = state.getCreditScore();
                    log.info("[PIPELINE-STAGE:{}] 执行风控评级: amount={}, creditScore={}",
                            STAGE_RISK, amount, creditScore);

                    String riskLevel;
                    if (amount.compareTo(new BigDecimal("5000")) > 0 || creditScore < 600) {
                        riskLevel = "HIGH";
                    } else if (amount.compareTo(new BigDecimal("1000")) > 0) {
                        riskLevel = "MEDIUM";
                    } else {
                        riskLevel = "LOW";
                    }

                    return Map.of(
                            OrderFulfillmentState.RISK_LEVEL.name(), riskLevel,
                            "evaluatedAt", LocalDateTime.now().toString()
                    );
                }, OrderFulfillmentState::isVipBypass),

                // 阶段 3：人工复审 (仅 HIGH 高危订单执行，且前置中断挂起 HITL)
                PipelineStage.<OrderFulfillmentState>builder()
                        .name(STAGE_MANUAL_AUDIT)
                        .description("高危大额订单人工介入复审")
                        .skipCondition(state -> !"HIGH".equalsIgnoreCase(state.getRiskLevel()))
                        .interruptBefore(true)
                        .handler(state -> {
                            Object rawApproval = state.data().get(TypedKey.APPROVAL_DATA.name());
                            String action = extractAction(rawApproval);
                            String comment = extractComment(rawApproval);
                            log.info("[PIPELINE-STAGE:{}] 人工复审决断注入: action={}, comment={}",
                                    STAGE_MANUAL_AUDIT, action, comment);

                            if ("APPROVED".equalsIgnoreCase(action)) {
                                return Map.of(
                                        OrderFulfillmentState.AUDIT_DECISION.name(), "APPROVED",
                                        "auditComment", comment != null ? comment : "人工审核放行",
                                        "auditedAt", LocalDateTime.now().toString()
                                );
                            } else {
                                throw new IllegalStateException("人工复审驳回: "
                                        + (comment != null && !comment.isBlank() ? comment : "未满足放行合规资质"));
                            }
                        })
                        .build(),

                // 阶段 4：资金清结算
                PipelineStage.of(STAGE_PAYMENT, "支付清算与资金结算", state -> {
                    log.info("[PIPELINE-STAGE:{}] 资金清算扣减中: orderId={}, amount={}",
                            STAGE_PAYMENT, state.getOrderId(), state.getAmount());
                    return Map.of(
                            OrderFulfillmentState.PAYMENT_STATUS.name(), "SUCCESS",
                            "clearingChannel", "EPAY_DIRECT",
                            "clearedAt", LocalDateTime.now().toString()
                    );
                }),

                // 阶段 5：自动派发履约
                PipelineStage.of(STAGE_DELIVERY, "发货派送与单号/密钥生成", state -> {
                    String itemType = state.getItemType();
                    String trackingNumber;
                    if ("VIRTUAL".equalsIgnoreCase(itemType)) {
                        trackingNumber = "VIRT-KEY-" + IdUtil.fastSimpleUUID().substring(0, 12).toUpperCase();
                    } else {
                        trackingNumber = "SF-EXPR-" + IdUtil.fastSimpleUUID().substring(0, 12).toUpperCase();
                    }
                    log.info("[PIPELINE-STAGE:{}] 自动履约发货: orderId={}, itemType={}, trackingNumber={}",
                            STAGE_DELIVERY, state.getOrderId(), itemType, trackingNumber);

                    return Map.of(
                            OrderFulfillmentState.TRACKING_NUMBER.name(), trackingNumber,
                            "deliveryStatus", "DISPATCHED",
                            "dispatchedAt", LocalDateTime.now().toString()
                    );
                })
        );
    }

    @Override
    protected Map<String, Object> onFinish(OrderFulfillmentState state) {
        Map<String, Object> patch = super.onFinish(state);
        patch.put(OrderFulfillmentState.FINAL_OUTPUT.name(),
                String.format("订单 [%s] 履约全流程顺利完成，派发单号/密钥: %s",
                        state.getOrderId(), state.getTrackingNumber()));
        return patch;
    }

    private String extractAction(Object approval) {
        if (approval instanceof Map<?, ?> map) {
            Object act = map.get("action");
            return act != null ? act.toString() : "";
        }
        return approval != null ? approval.toString() : "";
    }

    private String extractComment(Object approval) {
        if (approval instanceof Map<?, ?> map) {
            Object c = map.get("comment");
            return c != null ? c.toString() : "";
        }
        return "";
    }
}

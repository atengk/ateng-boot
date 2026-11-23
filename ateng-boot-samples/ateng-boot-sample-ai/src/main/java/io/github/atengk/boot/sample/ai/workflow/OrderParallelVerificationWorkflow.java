package io.github.atengk.boot.sample.ai.workflow;

import io.github.atengk.boot.ai.graph.parallel.BranchFailureStrategy;
import io.github.atengk.boot.ai.graph.parallel.ParallelBranch;
import io.github.atengk.boot.ai.graph.parallel.ParallelFanOutWorkflow;
import io.github.atengk.boot.ai.graph.parallel.ParallelState;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.sample.ai.model.OrderParallelVerificationState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 电商订单下单前多维并发核验认知工作流 (ParallelFanOutWorkflow)
 * <p>
 * 并发扇出执行交易反欺诈、库存锁定与会员授信三大独立分支，采用 {@link BranchFailureStrategy#BEST_EFFORT}
 * 策略实现高可用超时降级，并汇总生成最终订单准入裁决。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-22
 */
@Slf4j
@Component
public class OrderParallelVerificationWorkflow extends ParallelFanOutWorkflow<OrderParallelVerificationState> {

    public static final String WORKFLOW_NAME = "orderParallelVerificationWorkflow";
    public static final String BRANCH_RISK = "risk_audit";
    public static final String BRANCH_INVENTORY = "inventory_lock";
    public static final String BRANCH_CREDIT = "credit_quota";

    public OrderParallelVerificationWorkflow() {
        super(WORKFLOW_NAME, OrderParallelVerificationState::new, BranchFailureStrategy.BEST_EFFORT);
    }

    @Override
    protected List<ParallelBranch<OrderParallelVerificationState>> registerBranches() {
        return List.of(
                ParallelBranch.<OrderParallelVerificationState>builder()
                        .name(BRANCH_RISK)
                        .description("交易反欺诈与风险规则引擎并发核验")
                        .timeout(Duration.ofMillis(400))
                        .handler(state -> {
                            long delay = state.getRiskDelayMs() != null ? state.getRiskDelayMs() : 0L;
                            if (delay > 0) {
                                Thread.sleep(delay);
                            }
                            boolean highRisk = state.getAmount().compareTo(new BigDecimal("5000")) > 0;
                            String verdict = highRisk ? "HIGH_RISK" : "LOW_RISK";
                            log.info("[PARALLEL-BRANCH:{}] tenant={}, orderId={}, verdict={}",
                                    BRANCH_RISK, TenantContextHolder.getTenantId(), state.getOrderId(), verdict);
                            return verdict;
                        })
                        .build(),

                ParallelBranch.<OrderParallelVerificationState>builder()
                        .name(BRANCH_INVENTORY)
                        .description("多仓库存水位实时核查与并发预锁")
                        .timeout(Duration.ofMillis(500))
                        .handler(state -> {
                            String result = "STOCK_LOCKED:" + state.getSkuCode();
                            log.info("[PARALLEL-BRANCH:{}] tenant={}, orderId={}, result={}",
                                    BRANCH_INVENTORY, TenantContextHolder.getTenantId(), state.getOrderId(), result);
                            return result;
                        })
                        .build(),

                ParallelBranch.<OrderParallelVerificationState>builder()
                        .name(BRANCH_CREDIT)
                        .description("会员信用额度与风控授信余额核查")
                        .timeout(Duration.ofMillis(500))
                        .handler(state -> {
                            String result = "QUOTA_SUFFICIENT:" + state.getUserId();
                            log.info("[PARALLEL-BRANCH:{}] tenant={}, orderId={}, result={}",
                                    BRANCH_CREDIT, TenantContextHolder.getTenantId(), state.getOrderId(), result);
                            return result;
                        })
                        .build()
        );
    }

    @Override
    protected Object aggregate(OrderParallelVerificationState state, Map<String, Object> branchResults) {
        Map<String, ParallelState.BranchError> errors = state.getBranchErrors();
        String riskVerdict = branchResults.containsKey(BRANCH_RISK)
                ? String.valueOf(branchResults.get(BRANCH_RISK))
                : "DEGRADED_MANUAL_REVIEW";
        Object inventoryVerdict = branchResults.getOrDefault(BRANCH_INVENTORY, "STOCK_UNKNOWN");
        Object creditVerdict = branchResults.getOrDefault(BRANCH_CREDIT, "CREDIT_UNKNOWN");

        boolean approved = errors.isEmpty() && "LOW_RISK".equals(riskVerdict);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(OrderParallelVerificationState.APPROVED.name(), approved);
        patch.put(OrderParallelVerificationState.FINAL_OUTPUT.name(),
                String.format("订单[%s]并发核验完成(approved=%s, degradedCount=%d, risk=%s, inventory=%s, credit=%s)",
                        state.getOrderId(), approved, errors.size(), riskVerdict, inventoryVerdict, creditVerdict));
        return patch;
    }
}

package io.github.atengk.boot.sample.ai.workflow;

import io.github.atengk.boot.ai.graph.GraphWorkflow;
import io.github.atengk.boot.ai.graph.TypedKey;
import io.github.atengk.boot.sample.ai.model.OrderReviewState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 订单风控审核认知状态图工作流
 * <p>
 * 拓扑包含以下关键节点与路由控制：
 * <ul>
 *     <li><b>riskCheck (初审)</b>：小额高信用订单自动放行至终态 END；大额或低信用订单标记高危并路由至人工审核；</li>
 *     <li><b>humanReview (人工复审)</b>：静态中断挂起节点（{@code interruptBefore}），等待人工审批结果注入；</li>
 *     <li><b>docSupplement (反思补充)</b>：若审批要求补充资质，则进入反思循环补正并重入人工复审（限重试 2 次）；</li>
 *     <li><b>archive (终审归档)</b>：记录归档时间与审计结论后闭合流转。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Component
public class OrderReviewGraphWorkflow extends GraphWorkflow<OrderReviewState> {

    public static final String WORKFLOW_NAME = "orderReviewWorkflow";

    public OrderReviewGraphWorkflow() {
        super(WORKFLOW_NAME, OrderReviewState::new);
    }

    @Override
    protected void initGraph(StateGraph<OrderReviewState> graph) throws GraphStateException {
        // 1. 初审节点：规则自动化校验
        graph.addNode("riskCheck", node_async(state -> {
            BigDecimal amount = state.get(OrderReviewState.AMOUNT, BigDecimal.ZERO);
            Integer creditScore = state.get(OrderReviewState.CREDIT_SCORE, 700);

            Map<String, Object> patch = new LinkedHashMap<>();
            if (amount.compareTo(new BigDecimal("1000")) <= 0 && creditScore >= 700) {
                log.info("[WORKFLOW] 订单初审自动通过: amount={}, creditScore={}", amount, creditScore);
                patch.put(OrderReviewState.RISK_LEVEL.name(), "LOW");
                patch.put(OrderReviewState.FINAL_STATUS.name(), "APPROVED");
                patch.put(OrderReviewState.REASON.name(), "小额优质订单自动初审通过");
            } else if (amount.compareTo(new BigDecimal("5000")) > 0 || creditScore < 600) {
                log.info("[WORKFLOW] 订单判定为高危: amount={}, creditScore={}", amount, creditScore);
                patch.put(OrderReviewState.RISK_LEVEL.name(), "HIGH");
                patch.put(OrderReviewState.REASON.name(), "大额或低信用高危订单，挂起进入人工审核");
            } else {
                log.info("[WORKFLOW] 订单判定为中危: amount={}, creditScore={}", amount, creditScore);
                patch.put(OrderReviewState.RISK_LEVEL.name(), "MEDIUM");
                patch.put(OrderReviewState.REASON.name(), "中等风险订单，挂起进入人工审核");
            }
            return patch;
        }));

        // 2. 人工审核节点（静态挂起中断）
        graph.addNode("humanReview", node_async(state -> {
            Object approval = state.data().get(TypedKey.APPROVAL_DATA.name());
            String action = extractAction(approval);
            log.info("[WORKFLOW] 人工审核节点执行: approvalAction={}", action);

            Map<String, Object> patch = new LinkedHashMap<>();
            if ("PASSED".equalsIgnoreCase(action)) {
                patch.put(OrderReviewState.FINAL_STATUS.name(), "APPROVED");
                patch.put(OrderReviewState.REASON.name(), "人工审核通过");
            } else if ("REJECTED".equalsIgnoreCase(action)) {
                patch.put(OrderReviewState.FINAL_STATUS.name(), "REJECTED");
                patch.put(OrderReviewState.REASON.name(), "人工审核拒绝");
            } else if ("REQUEST_DOCS".equalsIgnoreCase(action)) {
                patch.put(OrderReviewState.REASON.name(), "审核员要求补充资质证明材料");
            }
            return patch;
        }));

        // 3. 补充资质反思节点（循环回流）
        graph.addNode("docSupplement", node_async(state -> {
            int currentRetry = state.get(OrderReviewState.DOC_RETRY_COUNT, 0);
            int newRetry = currentRetry + 1;
            log.info("[WORKFLOW] 资质反思补正中: retryCount={}", newRetry);

            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put(OrderReviewState.DOC_RETRY_COUNT.name(), newRetry);

            if (newRetry > 2) {
                log.warn("[WORKFLOW] 资质补充次数超限熔断: retryCount={}", newRetry);
                patch.put(OrderReviewState.FINAL_STATUS.name(), "REJECTED");
                patch.put(OrderReviewState.REASON.name(), "补充资质次数超限（超过2次），审核终止拒绝");
            } else {
                patch.put(OrderReviewState.REASON.name(), "资质材料补充完成第 " + newRetry + " 次，返回人工审核复核");
            }
            return patch;
        }));

        // 4. 终审归档节点
        graph.addNode("archive", node_async(state -> {
            String orderId = state.get(OrderReviewState.ORDER_ID, "UNKNOWN");
            String finalStatus = state.get(OrderReviewState.FINAL_STATUS, "REJECTED");
            String reason = state.get(OrderReviewState.REASON, "");
            log.info("[WORKFLOW] 订单审核归档完成 [orderId={}, status={}, reason={}]", orderId, finalStatus, reason);

            return Map.of(
                    "archiveTime", LocalDateTime.now().toString(),
                    OrderReviewState.FINAL_STATUS.name(), finalStatus
            );
        }));

        // 拓扑连线
        graph.addEdge(START, "riskCheck");

        // 条件路由 1: 初审按风险等级分流
        graph.addConditionalEdges("riskCheck", edge_async(state -> {
            String riskLevel = state.get(OrderReviewState.RISK_LEVEL, "HIGH");
            return "LOW".equalsIgnoreCase(riskLevel) ? END : "humanReview";
        }), Map.of(
                "humanReview", "humanReview",
                END, END
        ));

        // 条件路由 2: 人工审核决策分流
        graph.addConditionalEdges("humanReview", edge_async(state -> {
            Object approval = state.data().get(TypedKey.APPROVAL_DATA.name());
            String action = extractAction(approval);
            if ("REQUEST_DOCS".equalsIgnoreCase(action)) {
                return "docSupplement";
            }
            return "archive";
        }), Map.of(
                "docSupplement", "docSupplement",
                "archive", "archive"
        ));

        // 条件路由 3: 资质反思循环分流
        graph.addConditionalEdges("docSupplement", edge_async(state -> {
            int retry = state.get(OrderReviewState.DOC_RETRY_COUNT, 0);
            if (retry > 2) {
                return "archive";
            }
            return "humanReview";
        }), Map.of(
                "humanReview", "humanReview",
                "archive", "archive"
        ));

        // 归档连线至终态
        graph.addEdge("archive", END);
    }

    @Override
    protected List<String> interruptBeforeNodes() {
        return List.of("humanReview");
    }

    @Override
    protected int maxIterations() {
        return 25;
    }

    private String extractAction(Object approval) {
        if (approval == null) {
            return "";
        }
        if (approval instanceof Map<?, ?> map) {
            Object act = map.get("action");
            return act != null ? act.toString() : "";
        }
        return approval.toString();
    }
}

package io.github.atengk.boot.liteflow.trace;

import com.yomahub.liteflow.flow.LiteflowResponse;
import com.yomahub.liteflow.flow.entity.CmpStep;
import io.github.atengk.boot.core.util.JsonUtil;
import lombok.Data;
import org.slf4j.MDC;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/**
 * LiteFlow 结构化时序执行链路轨迹模型
 * 记录单次规则执行的链路元数据、有序步骤清单、耗时统计与错误摘要，
 * 支持格式化输出、JSON 序列化与 MDC 审计日志无缝注入
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
public class LiteflowExecutionTrace implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 默认 MDC 链路轨迹注入 Key
     */
    public static final String DEFAULT_MDC_TRACE_KEY = "liteflow_trace";

    /**
     * 规则链标识 ID
     */
    private String chainId;

    /**
     * LiteFlow 全局请求标识 ID
     */
    private String requestId;

    /**
     * 规则链是否执行成功
     */
    private boolean success;

    /**
     * 规则链整体执行总耗时（毫秒）
     */
    private long totalTimeSpentMs;

    /**
     * 按时序执行的组件步骤列表
     */
    private List<ExecutionStepItem> steps = new ArrayList<>();

    /**
     * 失败异常摘要
     */
    private String errorSummary;

    /**
     * 全链路追踪 TraceId
     */
    private String traceId;

    public LiteflowExecutionTrace() {
    }

    /**
     * 从 LiteflowResponse 构建结构化时序轨迹
     *
     * @param chainId  规则链 ID
     * @param response LiteFlow 执行响应
     * @return 结构化时序轨迹对象
     */
    public static LiteflowExecutionTrace fromResponse(String chainId, LiteflowResponse response) {
        return fromResponse(chainId, response, -1L);
    }

    /**
     * 从 LiteflowResponse 与外部测算耗时构建结构化时序轨迹
     *
     * @param chainId    规则链 ID
     * @param response   LiteFlow 执行响应
     * @param durationMs 外部测算总耗时（毫秒，传入负数则按各节点步骤累加）
     * @return 结构化时序轨迹对象
     */
    public static LiteflowExecutionTrace fromResponse(String chainId, LiteflowResponse response, long durationMs) {
        LiteflowExecutionTrace trace = new LiteflowExecutionTrace();
        trace.setChainId(chainId);
        trace.setTraceId(resolveCurrentTraceId());

        if (response == null) {
            trace.setSuccess(false);
            trace.setErrorSummary("LiteflowResponse 为 null");
            trace.setTotalTimeSpentMs(Math.max(durationMs, 0L));
            return trace;
        }

        trace.setRequestId(response.getRequestId());
        trace.setSuccess(response.isSuccess());

        // 1. 提取失败错误摘要
        if (!response.isSuccess()) {
            if (response.getMessage() != null && !response.getMessage().isBlank()) {
                trace.setErrorSummary(response.getMessage());
            } else if (response.getCause() != null) {
                String causeMsg = response.getCause().getMessage();
                trace.setErrorSummary((causeMsg != null && !causeMsg.isBlank())
                        ? causeMsg
                        : response.getCause().getClass().getSimpleName());
            }
        }

        // 2. 预解析 Saga 回滚节点 ID 集合
        Set<String> rollbackNodeIds = resolveRollbackNodeIds(response);

        // 3. 提取超时节点清单
        List<String> timeoutItems = null;
        try {
            timeoutItems = response.getTimeoutItems();
        } catch (Throwable ignored) {
        }

        // 4. 解析按时序执行的步骤队列
        Queue<CmpStep> stepQueue = null;
        try {
            stepQueue = response.getExecuteStepQueue();
        } catch (Throwable ignored) {
        }

        long stepTimeSum = 0L;
        if (stepQueue != null && !stepQueue.isEmpty()) {
            for (CmpStep step : stepQueue) {
                if (step == null) {
                    continue;
                }
                ExecutionStepItem item = new ExecutionStepItem();
                item.setNodeId(step.getNodeId());
                item.setTag(step.getTag());
                long spent = step.getTimeSpent() != null ? step.getTimeSpent() : 0L;
                item.setTimeSpentMs(spent);
                stepTimeSum += spent;
                item.setThreadName(step.getThreadName());
                if (step.getStepType() != null) {
                    item.setStepType(step.getStepType().name());
                }

                // 判断步骤状态
                if (!step.isSuccess() || step.getException() != null) {
                    item.setStatus(ExecutionStepStatus.FAIL);
                    if (step.getException() != null) {
                        String exMsg = step.getException().getMessage();
                        item.setErrorSummary((exMsg != null && !exMsg.isBlank())
                                ? exMsg
                                : step.getException().getClass().getSimpleName());
                    } else if (timeoutItems != null && timeoutItems.contains(step.getNodeId())) {
                        item.setErrorSummary("Node execution timeout");
                    }
                } else if (rollbackNodeIds.contains(step.getNodeId())
                        || (step.getRollbackTimeSpent() != null && step.getRollbackTimeSpent() > 0)) {
                    item.setStatus(ExecutionStepStatus.ROLLBACK);
                    item.setRollbackTimeSpentMs(step.getRollbackTimeSpent());
                } else {
                    item.setStatus(ExecutionStepStatus.SUCCESS);
                }

                trace.getSteps().add(item);
            }
        }

        // 5. 校验总耗时：优先使用外部高精度耗时，未提供则使用步骤耗时总和
        trace.setTotalTimeSpentMs(durationMs >= 0 ? durationMs : stepTimeSum);

        return trace;
    }

    /**
     * 获取第一个匹配指定组件 ID 的步骤
     *
     * @param nodeId 组件节点 ID
     * @return 匹配的步骤项，若未找到返回 null
     */
    public ExecutionStepItem getStep(String nodeId) {
        if (nodeId == null || steps == null) {
            return null;
        }
        return steps.stream()
                .filter(step -> Objects.equals(nodeId, step.getNodeId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取全部匹配指定组件 ID 的步骤（常用于循环节点）
     *
     * @param nodeId 组件节点 ID
     * @return 匹配的步骤项列表，无匹配保底返回空列表
     */
    public List<ExecutionStepItem> getSteps(String nodeId) {
        if (nodeId == null || steps == null) {
            return Collections.emptyList();
        }
        return steps.stream()
                .filter(step -> Objects.equals(nodeId, step.getNodeId()))
                .toList();
    }

    /**
     * 判断当前链路轨迹中是否存在失败节点
     *
     * @return 是否有失败步骤
     */
    public boolean hasFailedStep() {
        return getFailedStep() != null;
    }

    /**
     * 获取首个失败的步骤项
     *
     * @return 首个失败步骤项，全成功则返回 null
     */
    public ExecutionStepItem getFailedStep() {
        if (steps == null) {
            return null;
        }
        return steps.stream()
                .filter(step -> step.getStatus() == ExecutionStepStatus.FAIL)
                .findFirst()
                .orElse(null);
    }

    /**
     * 将时序轨迹格式化输出为紧凑字符串摘要，便于日志与 MDC 审计
     * 例如: [chainId=orderFlow, success=true, totalTime=25ms, steps: validate[10ms,SUCCESS] -> calc[15ms,SUCCESS]]
     *
     * @return 紧凑字符串摘要
     */
    public String formatSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("[chainId=").append(chainId != null ? chainId : "UNKNOWN")
                .append(", success=").append(success)
                .append(", totalTime=").append(totalTimeSpentMs).append("ms");
        if (traceId != null && !traceId.isBlank()) {
            sb.append(", traceId=").append(traceId);
        }
        if (requestId != null && !requestId.isBlank()) {
            sb.append(", requestId=").append(requestId);
        }
        if (errorSummary != null && !errorSummary.isBlank()) {
            sb.append(", error=").append(errorSummary);
        }
        sb.append(", steps: ");
        if (steps.isEmpty()) {
            sb.append("none");
        } else {
            for (int i = 0; i < steps.size(); i++) {
                if (i > 0) {
                    sb.append(" -> ");
                }
                ExecutionStepItem step = steps.get(i);
                sb.append(step.getNodeId());
                if (step.getTag() != null && !step.getTag().isBlank()) {
                    sb.append("(").append(step.getTag()).append(")");
                }
                sb.append("[").append(step.getTimeSpentMs()).append("ms,")
                        .append(step.getStatus());
                if (step.getErrorSummary() != null && !step.getErrorSummary().isBlank()) {
                    sb.append(":").append(step.getErrorSummary());
                }
                sb.append("]");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 将当前链路轨迹摘要注入 MDC 审计日志（默认 Key: liteflow_trace）
     */
    public void injectToMdc() {
        injectToMdc(DEFAULT_MDC_TRACE_KEY);
    }

    /**
     * 将当前链路轨迹摘要注入 MDC 审计日志
     *
     * @param mdcKey MDC 键名称
     */
    public void injectToMdc(String mdcKey) {
        String key = (mdcKey != null && !mdcKey.isBlank()) ? mdcKey : DEFAULT_MDC_TRACE_KEY;
        MDC.put(key, formatSummary());
    }

    /**
     * 从 MDC 中清理默认链路轨迹摘要
     */
    public static void clearFromMdc() {
        clearFromMdc(DEFAULT_MDC_TRACE_KEY);
    }

    /**
     * 从 MDC 中清理指定键的链路轨迹摘要
     *
     * @param mdcKey MDC 键名称
     */
    public static void clearFromMdc(String mdcKey) {
        String key = (mdcKey != null && !mdcKey.isBlank()) ? mdcKey : DEFAULT_MDC_TRACE_KEY;
        MDC.remove(key);
    }

    /**
     * 转换为 JSON 格式字符串
     *
     * @return JSON 字符串
     */
    public String toJson() {
        return JsonUtil.toJsonString(this);
    }

    /**
     * 解析当前线程上下文中的 TraceId
     */
    private static String resolveCurrentTraceId() {
        try {
            String traceId = MDC.get("traceId");
            if (traceId != null && !traceId.isBlank()) {
                return traceId;
            }
            traceId = MDC.get("trace_id");
            if (traceId != null && !traceId.isBlank()) {
                return traceId;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 提取发生过 Saga 回滚的节点 ID 集合
     */
    private static Set<String> resolveRollbackNodeIds(LiteflowResponse response) {
        if (response == null) {
            return Collections.emptySet();
        }
        try {
            Queue<CmpStep> rollbackQueue = response.getRollbackStepQueue();
            if (rollbackQueue != null && !rollbackQueue.isEmpty()) {
                Set<String> set = new HashSet<>();
                for (CmpStep step : rollbackQueue) {
                    if (step != null && step.getNodeId() != null) {
                        set.add(step.getNodeId());
                    }
                }
                return set;
            }
        } catch (Throwable ignored) {
        }
        return Collections.emptySet();
    }
}

package io.github.atengk.boot.ai.tool.approval;

import dev.langchain4j.service.tool.DefaultToolExecutor;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.properties.ToolApprovalProperties;
import io.github.atengk.boot.ai.tool.approval.store.ToolApprovalStore;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 高危工具人机协同审批生命周期治理统一操作门面
 * <p>
 * 提供工单快照提交、查询、人工审批受管反射重放与拒绝清理等全套对称操作。
 * 在审批通过时，自动还原原始调用方的链路追踪（MDC traceId）与租户上下文，
 * 执行反射重放并持久化执行结果，具备确定性的资源清理与 CAS 状态防伪机制。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
public class ToolApprovalTemplate {

    private static final Logger log = LoggerFactory.getLogger(ToolApprovalTemplate.class);

    private static final Method PREPARE_ARGUMENTS_METHOD;
    private static final Method ARGUMENTS_AS_MAP_METHOD;

    static {
        try {
            Class<?> utilCls = Class.forName("dev.langchain4j.service.tool.ToolExecutionRequestUtil");
            ARGUMENTS_AS_MAP_METHOD = utilCls.getDeclaredMethod("argumentsAsMap", String.class);
            ARGUMENTS_AS_MAP_METHOD.setAccessible(true);

            PREPARE_ARGUMENTS_METHOD = DefaultToolExecutor.class.getDeclaredMethod(
                    "prepareArguments", Method.class, Map.class, Object.class);
            PREPARE_ARGUMENTS_METHOD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("初始化 LangChain4j 工具参数解析反射方法句柄失败", e);
        }
    }

    private final ToolApprovalStore store;
    private final ToolApprovalProperties properties;
    private final ApplicationContext applicationContext;
    private final Map<String, Object> targetBeanRegistry = new ConcurrentHashMap<>();

    public ToolApprovalTemplate(ToolApprovalStore store,
                                ToolApprovalProperties properties,
                                ApplicationContext applicationContext) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.properties = properties != null ? properties : new ToolApprovalProperties();
        this.applicationContext = applicationContext;
    }

    /**
     * 注册本地目标工具 Bean 实例（用于非 Spring 容器或单元测试环境）
     *
     * @param bean 工具实例
     */
    public void registerTargetBean(Object bean) {
        if (bean != null) {
            targetBeanRegistry.put(bean.getClass().getName(), bean);
        }
    }

    /**
     * 获取工单默认超时时间
     *
     * @return 默认超时 Duration
     */
    public Duration getDefaultTimeout() {
        return properties.getDefaultTimeout() != null ? properties.getDefaultTimeout() : Duration.ofMinutes(30);
    }

    /**
     * 提交高危工具审批工单并持久化快照
     *
     * @param ticket 工单快照
     * @param ttl    超时失效生存时间
     * @return 持久化后的工单快照
     */
    public ToolApprovalTicket submit(ToolApprovalTicket ticket, Duration ttl) {
        Objects.requireNonNull(ticket, "ticket must not be null");
        Duration effectiveTtl = (ttl != null && !ttl.isNegative() && !ttl.isZero()) ? ttl : getDefaultTimeout();
        if (ticket.getCreateTime() == null) {
            ticket.setCreateTime(LocalDateTime.now());
        }
        if (ticket.getExpireTime() == null) {
            ticket.setExpireTime(LocalDateTime.now().plus(effectiveTtl));
        }
        if (ticket.getStatus() == null) {
            ticket.setStatus(ToolApprovalStatus.PENDING);
        }
        store.save(ticket, effectiveTtl);
        log.info("[AI-APPROVAL] 成功提交高危工具审批工单 [ticketId={}, tool={}, risk={}, expireAt={}]",
                ticket.getTicketId(), ticket.getToolName(), ticket.getRiskLevel(), ticket.getExpireTime());
        return ticket;
    }

    /**
     * 查询指定标识的审批工单快照详情
     *
     * @param ticketId 工单标识
     * @return 工单快照 Optional
     */
    public Optional<ToolApprovalTicket> getTicket(String ticketId) {
        return store.findById(ticketId);
    }

    /**
     * 查询当前所有处于 PENDING 待审批状态的有效工单列表
     *
     * @return 待审批工单列表
     */
    public List<ToolApprovalTicket> listPending() {
        return store.listPending();
    }

    /**
     * 审批通过并受管安全反射重放目标高危工具方法
     *
     * @param ticketId 工单标识
     * @param comment  审批批注与核准意见
     * @return 审批与执行结果
     */
    @SuppressWarnings("unchecked")
    public ToolApprovalResult approve(String ticketId, String comment) {
        ToolApprovalTicket ticket = store.findById(ticketId).orElseThrow(() ->
                new AiException(AiResultCode.AI_TOOL_APPROVAL_NOT_FOUND, "未找到指定的工具审批工单: " + ticketId));

        if (ticket.getStatus() != ToolApprovalStatus.PENDING) {
            throw new AiException(AiResultCode.AI_TOOL_APPROVAL_INVALID_STATUS,
                    "工单 [" + ticketId + "] 处于非待审批状态: " + ticket.getStatus());
        }

        if (ticket.getExpireTime() != null && LocalDateTime.now().isAfter(ticket.getExpireTime())) {
            store.updateStatus(ticketId, ToolApprovalStatus.PENDING, ToolApprovalStatus.EXPIRED,
                    "审批已超时失效", null, "工单已过期");
            throw new AiException(AiResultCode.AI_TOOL_APPROVAL_EXPIRED, "工单 [" + ticketId + "] 已超时失效");
        }

        // 1. 保存当前线程上下文现场
        String originalTraceId = MDC.get("traceId");
        String originalTenantId = TenantContextHolder.getTenantId();

        try {
            // 2. 还原工单创建时的四维上下文
            if (StringUtils.hasText(ticket.getTraceId())) {
                MDC.put("traceId", ticket.getTraceId());
            }
            if (StringUtils.hasText(ticket.getTenantId())) {
                TenantContextHolder.setTenantId(ticket.getTenantId());
            }

            // 3. 定位目标 Bean 实例与方法
            Object targetBean = resolveTargetBean(ticket);
            Method method = resolveTargetMethod(targetBean, ticket.getMethodName());
            method.setAccessible(true);

            // 4. 反序列化参数
            Map<String, Object> argumentsMap = Collections.emptyMap();
            if (StringUtils.hasText(ticket.getArgumentsJson())) {
                argumentsMap = (Map<String, Object>) ARGUMENTS_AS_MAP_METHOD.invoke(null, ticket.getArgumentsJson());
            }
            Object[] args = (Object[]) PREPARE_ARGUMENTS_METHOD.invoke(null, method, argumentsMap, null);

            // 5. 反射重放执行
            long start = System.currentTimeMillis();
            Object result;
            try {
                result = method.invoke(targetBean, args);
            } catch (InvocationTargetException ite) {
                long duration = System.currentTimeMillis() - start;
                Throwable targetEx = ite.getTargetException();
                String errorMsg = targetEx != null ? targetEx.getMessage() : "Invocation error";
                store.updateStatus(ticketId, ToolApprovalStatus.PENDING, ToolApprovalStatus.APPROVED,
                        comment, null, errorMsg);
                log.error("[AI-APPROVAL] 工单 [{}] 审批重放目标方法执行异常 (耗时 {} ms): {}", ticketId, duration, errorMsg);
                return ToolApprovalResult.failed(ticketId, errorMsg, duration);
            }

            long duration = System.currentTimeMillis() - start;
            String resultJson = (result instanceof String text) ? text : JsonUtil.toJsonString(result);

            // 6. 原子更新工单状态为 APPROVED
            boolean updated = store.updateStatus(ticketId, ToolApprovalStatus.PENDING, ToolApprovalStatus.APPROVED,
                    comment, resultJson, null);
            if (!updated) {
                throw new AiException(AiResultCode.AI_TOOL_APPROVAL_INVALID_STATUS,
                        "并发竞争导致工单状态跃迁失败，可能已被其他线程处理: " + ticketId);
            }

            log.info("[AI-APPROVAL] 工单 [{}] 审批通过并重放执行成功 (耗时 {} ms)", ticketId, duration);
            return ToolApprovalResult.success(ticketId, result, duration);

        } catch (AiException ae) {
            throw ae;
        } catch (Throwable t) {
            log.error("[AI-APPROVAL] 工单 [{}] 审批恢复执行失败: {}", ticketId, t.getMessage(), t);
            store.updateStatus(ticketId, ToolApprovalStatus.PENDING, ToolApprovalStatus.APPROVED,
                    comment, null, t.getMessage());
            return ToolApprovalResult.failed(ticketId, t.getMessage(), 0L);
        } finally {
            // 7. 严格恢复当前调用线程的原始上下文现场
            if (originalTraceId != null) {
                MDC.put("traceId", originalTraceId);
            } else {
                MDC.remove("traceId");
            }
            if (originalTenantId != null) {
                TenantContextHolder.setTenantId(originalTenantId);
            } else {
                TenantContextHolder.clear();
            }
        }
    }

    /**
     * 审核拒绝指定的工具审批工单
     *
     * @param ticketId 工单标识
     * @param reason   拒绝原因说明
     * @return 审批结果
     */
    public ToolApprovalResult reject(String ticketId, String reason) {
        ToolApprovalTicket ticket = store.findById(ticketId).orElseThrow(() ->
                new AiException(AiResultCode.AI_TOOL_APPROVAL_NOT_FOUND, "未找到指定的工具审批工单: " + ticketId));

        if (ticket.getStatus() != ToolApprovalStatus.PENDING) {
            throw new AiException(AiResultCode.AI_TOOL_APPROVAL_INVALID_STATUS,
                    "工单 [" + ticketId + "] 处于非待审批状态: " + ticket.getStatus());
        }

        boolean updated = store.updateStatus(ticketId, ToolApprovalStatus.PENDING, ToolApprovalStatus.REJECTED,
                reason, null, "人工审批拒绝执行");
        if (!updated) {
            throw new AiException(AiResultCode.AI_TOOL_APPROVAL_INVALID_STATUS,
                    "并发竞争导致工单状态跃迁失败: " + ticketId);
        }

        log.warn("[AI-APPROVAL] 工单 [{}] 已被人工审核拒绝: {}", ticketId, reason);
        return ToolApprovalResult.rejected(ticketId, reason);
    }

    /**
     * 主动撤销或关闭工单
     *
     * @param ticketId 工单标识
     * @return 是否成功撤销
     */
    public boolean cancel(String ticketId) {
        return store.updateStatus(ticketId, ToolApprovalStatus.PENDING, ToolApprovalStatus.CANCELLED,
                "调用方主动撤销", null, null);
    }

    private Object resolveTargetBean(ToolApprovalTicket ticket) {
        if (StringUtils.hasText(ticket.getTargetClassName()) && targetBeanRegistry.containsKey(ticket.getTargetClassName())) {
            return targetBeanRegistry.get(ticket.getTargetClassName());
        }

        if (applicationContext != null) {
            if (StringUtils.hasText(ticket.getTargetBeanName()) && applicationContext.containsBean(ticket.getTargetBeanName())) {
                return applicationContext.getBean(ticket.getTargetBeanName());
            }
            if (StringUtils.hasText(ticket.getTargetClassName())) {
                try {
                    Class<?> clazz = Class.forName(ticket.getTargetClassName());
                    return applicationContext.getBean(clazz);
                } catch (ClassNotFoundException ignored) {
                    // fall through
                }
            }
        }

        throw new AiException(AiResultCode.AI_AGENT_CONFIG_ERROR,
                "无法定位高危工具审批工单 [" + ticket.getTicketId() + "] 的目标执行 Bean: "
                        + ticket.getTargetClassName() + " (" + ticket.getTargetBeanName() + ")");
    }

    private Method resolveTargetMethod(Object targetBean, String methodName) {
        for (Method m : targetBean.getClass().getMethods()) {
            if (m.getName().equals(methodName)) {
                return m;
            }
        }
        for (Method m : targetBean.getClass().getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                return m;
            }
        }
        throw new AiException(AiResultCode.AI_AGENT_CONFIG_ERROR,
                "在目标类 [" + targetBean.getClass().getName() + "] 中未找到工具方法: " + methodName);
    }
}

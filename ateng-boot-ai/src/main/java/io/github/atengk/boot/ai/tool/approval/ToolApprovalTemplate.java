package io.github.atengk.boot.ai.tool.approval;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.memory.ChatMemoryManager;
import io.github.atengk.boot.ai.properties.ToolApprovalProperties;
import io.github.atengk.boot.ai.template.AiTemplate;
import io.github.atengk.boot.ai.tool.SandboxedToolExecutor;
import io.github.atengk.boot.ai.tool.approval.store.ToolApprovalStore;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
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
    private final Map<String, Object> agentBeanRegistry = new ConcurrentHashMap<>();
    private volatile AiTemplate aiTemplate;
    private volatile ChatMemoryManager chatMemoryManager;

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
     * 注册本地目标智能体 Agent Bean 实例（用于非 Spring 容器或单元测试环境）
     *
     * @param name 智能体 Bean 名称或接口全限定名
     * @param bean 智能体实例
     */
    public void registerAgentBean(String name, Object bean) {
        if (StringUtils.hasText(name) && bean != null) {
            agentBeanRegistry.put(name, bean);
        }
    }

    /**
     * 显式设置 AiTemplate 门面（用于单元测试或非 Spring 环境）
     *
     * @param aiTemplate AI 核心门面
     */
    public void setAiTemplate(AiTemplate aiTemplate) {
        this.aiTemplate = aiTemplate;
    }

    /**
     * 显式设置 ChatMemoryManager 门面（用于单元测试或非 Spring 环境）
     *
     * @param chatMemoryManager 会话记忆管理门面
     */
    public void setChatMemoryManager(ChatMemoryManager chatMemoryManager) {
        this.chatMemoryManager = chatMemoryManager;
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

        // 1. 先进行 CAS 状态流转（PENDING -> APPROVED），防止并发竞争导致高危工具被重复重放执行
        boolean claimed = store.updateStatus(ticketId, ToolApprovalStatus.PENDING, ToolApprovalStatus.APPROVED,
                comment, null, null);
        if (!claimed) {
            throw new AiException(AiResultCode.AI_TOOL_APPROVAL_INVALID_STATUS,
                    "并发竞争导致工单状态跃迁失败，工单可能已被其他线程处理: " + ticketId);
        }

        // 2. 保存当前线程上下文现场
        String originalTraceId = MDC.get("traceId");
        String originalTenantId = TenantContextHolder.getTenantId();
        String originalUserId = UserContextHolder.getUserId();

        try {
            // 3. 还原工单创建时的四维上下文
            if (StringUtils.hasText(ticket.getTraceId())) {
                MDC.put("traceId", ticket.getTraceId());
            }
            if (StringUtils.hasText(ticket.getTenantId())) {
                TenantContextHolder.setTenantId(ticket.getTenantId());
            }
            if (StringUtils.hasText(ticket.getCallerUserId())) {
                UserContextHolder.setUserId(ticket.getCallerUserId());
            }

            // 4. 定位目标 Bean 实例与方法
            Object targetBean = resolveTargetBean(ticket);
            Method method = resolveTargetMethod(targetBean, ticket.getMethodName());
            method.setAccessible(true);

            // 5. 反序列化参数
            Map<String, Object> argumentsMap = Collections.emptyMap();
            if (StringUtils.hasText(ticket.getArgumentsJson())) {
                argumentsMap = (Map<String, Object>) ARGUMENTS_AS_MAP_METHOD.invoke(null, ticket.getArgumentsJson());
            }
            Object[] args = (Object[]) PREPARE_ARGUMENTS_METHOD.invoke(null, method, argumentsMap, ticket.getMemoryId());

            // 6. 受管安全反射重放执行
            long start = System.currentTimeMillis();
            Object result;
            try {
                result = method.invoke(targetBean, args);
            } catch (InvocationTargetException ite) {
                long duration = System.currentTimeMillis() - start;
                Throwable targetEx = ite.getTargetException();
                String errorMsg = targetEx != null ? targetEx.getMessage() : "Invocation error";
                store.updateStatus(ticketId, ToolApprovalStatus.APPROVED, ToolApprovalStatus.APPROVED,
                        comment, null, errorMsg);
                log.error("[AI-APPROVAL] 工单 [{}] 审批重放目标方法执行异常 (耗时 {} ms): {}", ticketId, duration, errorMsg);
                return ToolApprovalResult.failed(ticketId, errorMsg, duration);
            }

            long duration = System.currentTimeMillis() - start;
            String resultJson = (result instanceof String text) ? text : JsonUtil.toJsonString(result);

            // 7. 更新执行结果快照
            store.updateStatus(ticketId, ToolApprovalStatus.APPROVED, ToolApprovalStatus.APPROVED,
                    comment, resultJson, null);

            log.info("[AI-APPROVAL] 工单 [{}] 审批通过并重放执行成功 (耗时 {} ms)", ticketId, duration);
            return ToolApprovalResult.success(ticketId, result, duration);

        } catch (AiException ae) {
            throw ae;
        } catch (Throwable t) {
            log.error("[AI-APPROVAL] 工单 [{}] 审批恢复执行失败: {}", ticketId, t.getMessage(), t);
            store.updateStatus(ticketId, ToolApprovalStatus.APPROVED, ToolApprovalStatus.APPROVED,
                    comment, null, t.getMessage());
            return ToolApprovalResult.failed(ticketId, t.getMessage(), 0L);
        } finally {
            // 8. 严格恢复当前调用线程的原始四维上下文现场
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
            if (originalUserId != null) {
                UserContextHolder.setUserId(originalUserId);
            } else {
                UserContextHolder.clear();
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

    /**
     * 审批通过并驱动智能体主动续跑完成后续推理作答
     * <p>
     * 1. 触发底层高危工具受管安全反射重放，获取真实执行结果；
     * 2. 基于工单快照捕获的智能体元数据（agentBeanName、memoryId 等）定位原智能体或会话记忆；
     * 3. 构造审批通过的通知消息注入会话记忆，驱动智能体继续后续推理；
     * 4. 自动识别续跑过程是否触发了二次高危拦截，返回已完成响应或级联新工单标识。
     * </p>
     *
     * @param ticketId 工单全局唯一标识
     * @param comment  审批批注与核准意见
     * @return 智能体续跑执行结果
     */
    public AgentResumeResult approveAndResumeAgent(String ticketId, String comment) {
        ToolApprovalResult approvalResult = approve(ticketId, comment);
        long duration = approvalResult.getExecutionTimeMs() != null ? approvalResult.getExecutionTimeMs() : 0L;
        if (!approvalResult.isSuccess()) {
            return AgentResumeResult.failed(ticketId, approvalResult.getErrorMessage(), duration);
        }

        ToolApprovalTicket ticket = store.findById(ticketId).orElse(null);
        if (ticket == null) {
            return AgentResumeResult.failed(ticketId, "未找到工单快照: " + ticketId, duration);
        }

        Object toolResult = approvalResult.getResult();

        String resumeInstruction = String.format(
                "【系统通知：人工审批已通过】工单 [%s] 对应的操作 [%s] 已成功执行，返回结果为：%s。审批批注：%s。请基于该执行结果继续回答用户的原始问题，给出完整明确的最终结论。",
                ticketId,
                ticket.getToolName(),
                toolResult != null ? toolResult.toString() : "Success",
                StringUtils.hasText(comment) ? comment : "无"
        );

        String resumeOriginalTraceId = MDC.get("traceId");
        String resumeOriginalTenantId = TenantContextHolder.getTenantId();
        String resumeOriginalUserId = UserContextHolder.getUserId();

        try {
            if (StringUtils.hasText(ticket.getTraceId())) {
                MDC.put("traceId", ticket.getTraceId());
            }
            if (StringUtils.hasText(ticket.getTenantId())) {
                TenantContextHolder.setTenantId(ticket.getTenantId());
            }
            if (StringUtils.hasText(ticket.getCallerUserId())) {
                UserContextHolder.setUserId(ticket.getCallerUserId());
            }

            String finalResponse = doResumeAgent(ticket, resumeInstruction);
            if (SandboxedToolExecutor.isApprovalRequired(finalResponse)) {
                Optional<String> nextTicketOpt = SandboxedToolExecutor.extractTicketId(finalResponse);
                log.info("[AI-APPROVAL] 工单 [{}] 续跑后触发级联二次高危工具拦截，新工单标识: {}", ticketId, nextTicketOpt.orElse("未知"));
                return AgentResumeResult.pendingNext(ticketId, nextTicketOpt.orElse(null), toolResult, duration);
            }
            log.info("[AI-APPROVAL] 工单 [{}] 审批通过并驱动智能体续跑成功完成终态作答", ticketId);
            return AgentResumeResult.completed(ticketId, finalResponse, toolResult, duration);
        } catch (Exception e) {
            log.error("[AI-APPROVAL] 工单 [{}] 审批重放成功但驱动智能体续跑异常: {}", ticketId, e.getMessage(), e);
            return AgentResumeResult.builder()
                    .ticketId(ticketId)
                    .completed(false)
                    .errorMessage("智能体续跑执行失败: " + e.getMessage())
                    .toolExecutionResult(toolResult)
                    .duration(duration)
                    .build();
        } finally {
            if (resumeOriginalTraceId != null) {
                MDC.put("traceId", resumeOriginalTraceId);
            } else {
                MDC.remove("traceId");
            }
            if (resumeOriginalTenantId != null) {
                TenantContextHolder.setTenantId(resumeOriginalTenantId);
            } else {
                TenantContextHolder.clear();
            }
            if (resumeOriginalUserId != null) {
                UserContextHolder.setUserId(resumeOriginalUserId);
            } else {
                UserContextHolder.clear();
            }
        }
    }

    private String doResumeAgent(ToolApprovalTicket ticket, String resumeInstruction) throws Exception {
        // 1. 尝试通过注册的 Agent Bean 或 Spring 容器中的 Agent 接口代理续跑
        Object agentBean = resolveAgentBean(ticket);
        if (agentBean != null) {
            try {
                String response = invokeAgentBean(agentBean, ticket.getAgentInterfaceName(), ticket.getMemoryId(), resumeInstruction);
                if (response != null) {
                    return response;
                }
            } catch (Exception e) {
                log.warn("[AI-APPROVAL] 通过 Agent Bean 续跑执行失败，尝试降级到 ChatMemoryManager / AiTemplate: {}", e.getMessage());
            }
        }

        // 2. 尝试通过 ChatMemoryManager 漫游历史并驱动 AiTemplate / ChatLanguageModel
        ChatMemoryManager memoryMgr = resolveChatMemoryManager();
        AiTemplate template = resolveAiTemplate();

        if (memoryMgr != null && StringUtils.hasText(ticket.getMemoryId())) {
            if (StringUtils.hasText(ticket.getToolExecutionId())) {
                String toolResultStr = ticket.getExecutionResultJson() != null
                        ? ticket.getExecutionResultJson()
                        : "Success";
                memoryMgr.appendMessage(ticket.getMemoryId(),
                        ToolExecutionResultMessage.from(ticket.getToolExecutionId(), ticket.getToolName(), toolResultStr));
            } else {
                memoryMgr.appendMessage(ticket.getMemoryId(), UserMessage.from(resumeInstruction));
            }
            List<ChatMessage> messages = memoryMgr.getMessages(ticket.getMemoryId());
            if (template != null) {
                String response = template.chat(messages);
                memoryMgr.appendMessage(ticket.getMemoryId(), AiMessage.from(response));
                return response;
            } else if (applicationContext != null) {
                ChatLanguageModel model = applicationContext.getBeanProvider(ChatLanguageModel.class).getIfAvailable();
                if (model != null) {
                    String response = model.generate(messages).content().text();
                    memoryMgr.appendMessage(ticket.getMemoryId(), AiMessage.from(response));
                    return response;
                }
            }
        }

        // 3. 无会话记忆时单轮提示词直接问答
        if (template != null) {
            return template.chat(resumeInstruction);
        }

        if (applicationContext != null) {
            ChatLanguageModel model = applicationContext.getBeanProvider(ChatLanguageModel.class).getIfAvailable();
            if (model != null) {
                return model.generate(resumeInstruction);
            }
        }

        throw new AiException(AiResultCode.AI_AGENT_CONFIG_ERROR,
                "无法找到用于智能体续跑执行的 Agent 实例、AiTemplate 或 ChatLanguageModel");
    }

    private AiTemplate resolveAiTemplate() {
        if (this.aiTemplate != null) {
            return this.aiTemplate;
        }
        if (applicationContext != null) {
            try {
                return applicationContext.getBeanProvider(AiTemplate.class).getIfAvailable();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private ChatMemoryManager resolveChatMemoryManager() {
        if (this.chatMemoryManager != null) {
            return this.chatMemoryManager;
        }
        if (applicationContext != null) {
            try {
                return applicationContext.getBeanProvider(ChatMemoryManager.class).getIfAvailable();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Object resolveAgentBean(ToolApprovalTicket ticket) {
        if (StringUtils.hasText(ticket.getAgentBeanName()) && agentBeanRegistry.containsKey(ticket.getAgentBeanName())) {
            return agentBeanRegistry.get(ticket.getAgentBeanName());
        }
        if (StringUtils.hasText(ticket.getAgentInterfaceName()) && agentBeanRegistry.containsKey(ticket.getAgentInterfaceName())) {
            return agentBeanRegistry.get(ticket.getAgentInterfaceName());
        }

        if (applicationContext != null) {
            if (StringUtils.hasText(ticket.getAgentBeanName()) && applicationContext.containsBean(ticket.getAgentBeanName())) {
                return applicationContext.getBean(ticket.getAgentBeanName());
            }
            if (StringUtils.hasText(ticket.getAgentInterfaceName())) {
                try {
                    Class<?> clazz = Class.forName(ticket.getAgentInterfaceName());
                    return applicationContext.getBean(clazz);
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private String invokeAgentBean(Object agentBean, String agentInterfaceName, String memoryId, String resumeInstruction) throws Exception {
        Class<?> agentClass = null;
        if (StringUtils.hasText(agentInterfaceName)) {
            try {
                agentClass = Class.forName(agentInterfaceName);
            } catch (ClassNotFoundException ignored) {
            }
        }
        if (agentClass == null) {
            Class<?>[] interfaces = agentBean.getClass().getInterfaces();
            if (interfaces.length > 0) {
                agentClass = interfaces[0];
            } else {
                agentClass = agentBean.getClass();
            }
        }

        Method[] methods = agentClass.getMethods();
        Method candidateMethod = null;
        boolean hasMemoryParam = false;
        int memoryIndex = -1;
        int messageIndex = -1;

        if (StringUtils.hasText(memoryId)) {
            for (Method m : methods) {
                if (m.getDeclaringClass() == Object.class) {
                    continue;
                }
                if (TokenStream.class.isAssignableFrom(m.getReturnType())) {
                    continue;
                }
                if (m.getParameterCount() == 2) {
                    Parameter[] params = m.getParameters();
                    int memIdx = -1;
                    int msgIdx = -1;

                    // 1. 优先通过 LangChain4j 显式契约注解确定
                    for (int i = 0; i < 2; i++) {
                        if (params[i].isAnnotationPresent(MemoryId.class)) {
                            memIdx = i;
                            msgIdx = 1 - i;
                            break;
                        } else if (params[i].isAnnotationPresent(dev.langchain4j.service.UserMessage.class)) {
                            msgIdx = i;
                            memIdx = 1 - i;
                            break;
                        }
                    }

                    // 2. 若无注解，尝试通过类型差异判断 (如 Object/Long 等非 String 类型作为 memoryId)
                    if (memIdx == -1) {
                        if (params[0].getType() != String.class && params[1].getType() == String.class) {
                            memIdx = 0;
                            msgIdx = 1;
                        } else if (params[0].getType() == String.class && params[1].getType() != String.class) {
                            memIdx = 1;
                            msgIdx = 0;
                        }
                    }

                    // 3. 若类型均为 String 或相同，通过形参命名启发式探测 (防御 -parameters 开启的场景)
                    if (memIdx == -1) {
                        String name0 = params[0].getName().toLowerCase();
                        String name1 = params[1].getName().toLowerCase();
                        if (name0.contains("memory") || name0.contains("session")) {
                            memIdx = 0;
                            msgIdx = 1;
                        } else if (name1.contains("memory") || name1.contains("session")) {
                            memIdx = 1;
                            msgIdx = 0;
                        } else if (name0.contains("message") || name0.contains("prompt") || name0.contains("text")) {
                            msgIdx = 0;
                            memIdx = 1;
                        } else if (name1.contains("message") || name1.contains("prompt") || name1.contains("text")) {
                            msgIdx = 1;
                            memIdx = 0;
                        }
                    }

                    // 4. 若未开启 -parameters (形如 arg0, arg1) 且均为 String，按行业默认标准规范第 0 位为 memoryId、第 1 位为 message
                    if (memIdx == -1 && params[0].getType() == String.class && params[1].getType() == String.class) {
                        memIdx = 0;
                        msgIdx = 1;
                    }

                    if (memIdx != -1 && msgIdx != -1 && memIdx != msgIdx) {
                        candidateMethod = m;
                        hasMemoryParam = true;
                        memoryIndex = memIdx;
                        messageIndex = msgIdx;
                        break;
                    }
                }
            }
        }

        if (candidateMethod == null) {
            for (Method m : methods) {
                if (m.getDeclaringClass() == Object.class) {
                    continue;
                }
                if (TokenStream.class.isAssignableFrom(m.getReturnType())) {
                    continue;
                }
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(String.class)) {
                    candidateMethod = m;
                    hasMemoryParam = false;
                    break;
                }
            }
        }

        if (candidateMethod != null) {
            candidateMethod.setAccessible(true);
            Object res;
            if (hasMemoryParam) {
                Object[] args = new Object[2];
                args[memoryIndex] = memoryId;
                args[messageIndex] = resumeInstruction;
                res = candidateMethod.invoke(agentBean, args);
            } else {
                res = candidateMethod.invoke(agentBean, resumeInstruction);
            }
            if (res == null) {
                return "Success";
            }
            if (res instanceof String s) {
                return s;
            }
            return JsonUtil.toJsonString(res);
        }

        return null;
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

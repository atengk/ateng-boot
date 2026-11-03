package io.github.atengk.boot.ai.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;

/**
 * 局部受管智能体工具执行沙箱执行器
 * <p>
 * 包装每一个受管工具方法，具备以下特性：
 * 1. 结构化可观测性日志记录（DEBUG 级别打印入参，INFO 级别统计耗时，WARN 级别记录业务拦截，自动关联 MDC traceId）
 * 2. 异常自愈反思转译：拦截 {@link BusinessException} 与 {@link IllegalArgumentException}，转译为语义化异常反馈文本返回给大模型
 * 3. 致命错误 Fail-Fast：对于 JVM 严重错误（如 {@link Error}、{@link OutOfMemoryError}）坚决向外抛出阻断执行
 * 4. 强类型返回序列化：使用 {@link JsonUtil} 统一序列化非字符串返回值
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class SandboxedToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(SandboxedToolExecutor.class);

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

    private final Object target;
    private final Method method;
    private final String toolName;

    /**
     * 构造沙箱工具执行器
     *
     * @param target   目标工具 Bean 实例
     * @param method   工具执行方法
     * @param toolName 工具方法名称
     */
    public SandboxedToolExecutor(Object target, Method method, String toolName) {
        this.target = Objects.requireNonNull(target, "工具目标实例不能为空");
        this.method = Objects.requireNonNull(method, "工具目标方法不能为空");
        this.toolName = StringUtils.hasText(toolName) ? toolName : method.getName();
        this.method.setAccessible(true);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(ToolExecutionRequest request, Object memoryId) {
        String traceId = MDC.get("traceId");
        String arguments = request != null ? request.arguments() : null;
        log.debug("[AI-TOOL-EXEC] [traceId: {}] Calling tool: [{}] with args: {}", traceId, toolName, arguments);

        long start = System.currentTimeMillis();
        try {
            // 1. 解析入参 JSON Map 并按方法签名对齐准备入参
            Map<String, Object> argumentsMap = (Map<String, Object>) ARGUMENTS_AS_MAP_METHOD.invoke(null, arguments);
            Object[] args = (Object[]) PREPARE_ARGUMENTS_METHOD.invoke(null, method, argumentsMap, memoryId);

            // 2. 反射执行目标工具方法
            Object result = method.invoke(target, args);
            long duration = System.currentTimeMillis() - start;
            log.info("[AI-TOOL-EXEC] [traceId: {}] Tool [{}] executed successfully in {} ms", traceId, toolName, duration);

            // 3. 结果统一转换为字符串
            if (method.getReturnType() == void.class) {
                return "Success";
            }
            if (result == null) {
                return "null";
            }
            if (result instanceof String text) {
                return text;
            }
            return JsonUtil.toJsonString(result);
        } catch (InvocationTargetException ite) {
            long duration = System.currentTimeMillis() - start;
            Throwable targetEx = ite.getTargetException();

            // 致命系统级严重错误坚决 Fail-Fast
            if (targetEx instanceof Error error) {
                log.error("[AI-TOOL-EXEC] [traceId: {}] Fatal system error executing tool [{}] in {} ms: {}",
                        traceId, toolName, duration, error.getMessage());
                throw error;
            }

            log.warn("[AI-TOOL-EXEC] [traceId: {}] Tool [{}] execution intercepted exception in {} ms: {}",
                    traceId, toolName, duration, targetEx.getMessage(), targetEx);

            // 业务受控异常转译为语义化自愈反馈给大模型反思修正
            if (targetEx instanceof BusinessException be) {
                return "工具执行业务拦截: [" + be.getCode() + "] " + be.getMessage();
            }
            if (targetEx instanceof IllegalArgumentException iae) {
                return "工具入参校验失败: " + iae.getMessage();
            }
            return "工具执行失败: " + targetEx.getMessage();
        } catch (Throwable t) {
            long duration = System.currentTimeMillis() - start;
            if (t instanceof Error error) {
                throw error;
            }
            log.warn("[AI-TOOL-EXEC] [traceId: {}] Tool [{}] invocation error in {} ms: {}",
                    traceId, toolName, duration, t.getMessage(), t);
            return "工具执行失败: " + t.getMessage();
        }
    }

    public Object getTarget() {
        return target;
    }

    public Method getMethod() {
        return method;
    }

    public String getToolName() {
        return toolName;
    }
}

package io.github.atengk.boot.ai.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.ToolExecutor;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.exception.AiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.*;

/**
 * 局部受管智能体工具执行沙箱工厂
 * <p>
 * 负责解析智能体绑定的工具类、从 Spring 上下文中检索工具 Bean、执行启动期 Fail-Fast 校验，
 * 并将工具方法统一包装为具备异常自愈反思能力与可观测性日志记录的沙箱执行器 {@link SandboxedToolExecutor}。
 *
 * @author Ateng
 * @since 2026-11-03
 */
public final class ToolExecutionSandbox {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionSandbox.class);

    private ToolExecutionSandbox() {
        // utility class
    }

    /**
     * 依据声明的工具类数组，从 Spring 容器解析受管 Bean 并构建沙箱工具映射
     *
     * @param applicationContext Spring 上下文容器
     * @param toolClasses        工具类声明数组
     * @param agentName          智能体简短标识（用于错误信息溯源）
     * @return 智能体工具规格到沙箱执行器的映射字典
     */
    public static Map<ToolSpecification, ToolExecutor> buildSandboxedTools(
            ApplicationContext applicationContext,
            Class<?>[] toolClasses,
            String agentName) {
        if (toolClasses == null || toolClasses.length == 0) {
            return Collections.emptyMap();
        }

        Map<ToolSpecification, ToolExecutor> sandboxedTools = new LinkedHashMap<>();
        Set<String> registeredToolNames = new HashSet<>();

        for (Class<?> toolClass : toolClasses) {
            if (toolClass == null) {
                continue;
            }

            // 1. 从 Spring 容器检索工具 Bean（启动期 Fail-Fast）
            Object toolBean;
            try {
                toolBean = applicationContext.getBean(toolClass);
            } catch (BeansException e) {
                throw new AiException(AiResultCode.AI_AGENT_CONFIG_ERROR,
                        "智能体 [" + agentName + "] 声明绑定的工具类 [" + toolClass.getName()
                                + "] 在 Spring 容器中未找到对应 Bean，请确保该类标注了 @Component 并被 Spring 容器托管", e);
            }

            // 2. 解析工具类中的所有 @Tool 方法并包装沙箱
            wrapToolBean(toolBean, toolClass, registeredToolNames, sandboxedTools, agentName);
        }

        return Collections.unmodifiableMap(sandboxedTools);
    }

    /**
     * 包装指定的单个工具实例为沙箱工具映射（主要用于编程式调用与单测验证）
     *
     * @param toolBean 工具实例
     * @return 智能体工具规格到沙箱执行器的映射字典
     */
    public static Map<ToolSpecification, ToolExecutor> wrapToolBean(Object toolBean) {
        if (toolBean == null) {
            return Collections.emptyMap();
        }
        Map<ToolSpecification, ToolExecutor> sandboxedTools = new LinkedHashMap<>();
        Set<String> registeredToolNames = new HashSet<>();
        wrapToolBean(toolBean, toolBean.getClass(), registeredToolNames, sandboxedTools, "AnonymousAgent");
        return Collections.unmodifiableMap(sandboxedTools);
    }

    private static void wrapToolBean(
            Object toolBean,
            Class<?> toolClass,
            Set<String> registeredToolNames,
            Map<ToolSpecification, ToolExecutor> sandboxedTools,
            String agentName) {

        Method[] methods = toolClass.getDeclaredMethods();
        for (Method method : methods) {
            Tool toolAnnotation = AnnotatedElementUtils.findMergedAnnotation(method, Tool.class);
            if (toolAnnotation == null) {
                continue;
            }

            ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
            String toolName = specification.name();
            if (!StringUtils.hasText(toolName)) {
                toolName = method.getName();
            }

            // 防御重名冲突
            if (!registeredToolNames.add(toolName)) {
                throw new AiException(AiResultCode.AI_AGENT_CONFIG_ERROR,
                        "智能体 [" + agentName + "] 声明的工具方法存在同名冲突: [" + toolName + "]，请调整方法名称或 @Tool 注解声明");
            }

            SandboxedToolExecutor executor = new SandboxedToolExecutor(toolBean, method, toolName);
            sandboxedTools.put(specification, executor);
            log.debug("智能体 [{}] 成功注册受管沙箱工具: [{}] -> {}.{}()",
                    agentName, toolName, toolClass.getSimpleName(), method.getName());
        }
    }
}

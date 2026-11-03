package io.github.atengk.boot.liteflow.core;

import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.enums.FlowParserTypeEnum;
import com.yomahub.liteflow.flow.FlowBus;
import com.yomahub.liteflow.flow.LiteflowResponse;
import com.yomahub.liteflow.flow.entity.CmpStep;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.liteflow.exception.LiteflowExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * LiteFlow 强类型统一操作门面
 * 提供自动校验解包、受管因果异常转译、受管异步流式编排、动态 EL 即时执行与规则热重载能力，
 * 封装底层 {@link FlowExecutor} 原始调用并保留逃生舱
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class LiteflowTemplate {

    private static final Logger log = LoggerFactory.getLogger(LiteflowTemplate.class);

    private final FlowExecutor flowExecutor;
    private final Executor asyncExecutor;

    public LiteflowTemplate(FlowExecutor flowExecutor) {
        this(flowExecutor, null);
    }

    public LiteflowTemplate(FlowExecutor flowExecutor, Executor asyncExecutor) {
        this.flowExecutor = Objects.requireNonNull(flowExecutor, "flowExecutor 不能为空");
        this.asyncExecutor = asyncExecutor;
    }

    /**
     * 同步执行规则链并自动解包强类型上下文（无入参重载）
     *
     * @param chainId      规则链 ID
     * @param contextClass 上下文类型
     * @param <T>          上下文泛型
     * @return 强类型上下文实例
     */
    public <T> T execute(String chainId, Class<T> contextClass) {
        return execute(chainId, null, contextClass);
    }

    /**
     * 同步执行规则链并自动解包强类型上下文
     * 若执行失败自动转译并抛出 {@link LiteflowExecutionException}，成功则直接提取上下文对象
     *
     * @param chainId      规则链 ID
     * @param param        流程入参
     * @param contextClass 上下文类型
     * @param <T>          上下文泛型
     * @return 强类型上下文实例
     */
    public <T> T execute(String chainId, Object param, Class<T> contextClass) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId 不能为空");
        }
        if (contextClass == null) {
            throw new IllegalArgumentException("contextClass 不能为空");
        }
        LiteflowResponse response = this.flowExecutor.execute2Resp(chainId, param, contextClass);
        checkResponseSuccess(chainId, response);
        return response.getContextBean(contextClass);
    }

    /**
     * 同步执行规则链（预置上下文实例重载）
     *
     * @param chainId     规则链 ID
     * @param contextBean 已初始化的上下文实例
     * @param <T>         上下文泛型
     * @return 同一强类型上下文实例
     */
    public <T> T executeWithContext(String chainId, T contextBean) {
        return executeWithContext(chainId, null, contextBean);
    }

    /**
     * 同步执行规则链（预置上下文实例）
     *
     * @param chainId     规则链 ID
     * @param param       流程入参
     * @param contextBean 已初始化的上下文实例
     * @param <T>         上下文泛型
     * @return 同一强类型上下文实例
     */
    public <T> T executeWithContext(String chainId, Object param, T contextBean) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId 不能为空");
        }
        Objects.requireNonNull(contextBean, "contextBean 不能为空");
        LiteflowResponse response = this.flowExecutor.execute2Resp(chainId, param, contextBean);
        checkResponseSuccess(chainId, response);
        return contextBean;
    }

    /**
     * 执行多上下文规则链，若失败自动转译受管异常，成功返回原始响应包装
     *
     * @param chainId             规则链 ID
     * @param param               流程入参
     * @param firstContextClass   首个上下文类型
     * @param secondContextClass  第二个上下文类型
     * @param otherContextClasses 其余上下文类型
     * @return 执行成功响应包装
     */
    public LiteflowResponse execute(String chainId, Object param, Class<?> firstContextClass, Class<?> secondContextClass, Class<?>... otherContextClasses) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId 不能为空");
        }
        int total = 2 + (otherContextClasses != null ? otherContextClasses.length : 0);
        Class<?>[] classes = new Class<?>[total];
        classes[0] = firstContextClass;
        classes[1] = secondContextClass;
        if (otherContextClasses != null && otherContextClasses.length > 0) {
            System.arraycopy(otherContextClasses, 0, classes, 2, otherContextClasses.length);
        }
        LiteflowResponse response = this.flowExecutor.execute2Resp(chainId, param, classes);
        checkResponseSuccess(chainId, response);
        return response;
    }

    /**
     * 执行多上下文规则链（数组/变长参数重载）
     *
     * @param chainId        规则链 ID
     * @param param          流程入参
     * @param contextClasses 多个上下文类型
     * @return 执行成功响应包装
     */
    public LiteflowResponse executeMultiple(String chainId, Object param, Class<?>... contextClasses) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId 不能为空");
        }
        LiteflowResponse response = this.flowExecutor.execute2Resp(chainId, param, contextClasses);
        checkResponseSuccess(chainId, response);
        return response;
    }

    /**
     * 受管异步流式执行规则链（无入参重载）
     *
     * @param chainId      规则链 ID
     * @param contextClass 上下文类型
     * @param <T>          上下文泛型
     * @return 承载强类型上下文的 CompletableFuture
     */
    public <T> CompletableFuture<T> executeAsync(String chainId, Class<T> contextClass) {
        return executeAsync(chainId, null, contextClass);
    }

    /**
     * 受管异步流式执行规则链
     * 自动挂载 {@link AsyncContext} 四维上下文跨线程快照透传与清理，失败时通过 CompletableFuture 抛出受管异常
     *
     * @param chainId      规则链 ID
     * @param param        流程入参
     * @param contextClass 上下文类型
     * @param <T>          上下文泛型
     * @return 承载强类型上下文的 CompletableFuture
     */
    public <T> CompletableFuture<T> executeAsync(String chainId, Object param, Class<T> contextClass) {
        Supplier<T> task = AsyncContext.wrapSupplier(() -> execute(chainId, param, contextClass));
        if (this.asyncExecutor != null) {
            return CompletableFuture.supplyAsync(task, this.asyncExecutor);
        } else {
            return CompletableFuture.supplyAsync(task);
        }
    }

    /**
     * 直接即时执行动态 EL 表达式（无入参重载）
     *
     * @param el           动态 EL 表达式
     * @param contextClass 上下文类型
     * @param <T>          上下文泛型
     * @return 强类型上下文实例
     */
    public <T> T executeEl(String el, Class<T> contextClass) {
        return executeEl(el, null, contextClass);
    }

    /**
     * 直接即时执行动态 EL 表达式并自动解包强类型上下文
     *
     * @param el           动态 EL 表达式
     * @param param        流程入参
     * @param contextClass 上下文类型
     * @param <T>          上下文泛型
     * @return 强类型上下文实例
     */
    public <T> T executeEl(String el, Object param, Class<T> contextClass) {
        if (el == null || el.isBlank()) {
            throw new IllegalArgumentException("el 表达式不能为空");
        }
        if (contextClass == null) {
            throw new IllegalArgumentException("contextClass 不能为空");
        }
        LiteflowResponse response = this.flowExecutor.execute2RespWithEL(el, param, null, contextClass);
        checkResponseSuccess("DYNAMIC_EL", response);
        return response.getContextBean(contextClass);
    }

    /**
     * 原生透传执行规则链（不自动校验失败，保留官方逃生通道）
     *
     * @param chainId        规则链 ID
     * @param param          流程入参
     * @param contextClasses 上下文 Class
     * @return 原始 LiteflowResponse
     */
    public LiteflowResponse execute2Resp(String chainId, Object param, Class<?>... contextClasses) {
        return this.flowExecutor.execute2Resp(chainId, param, contextClasses);
    }

    /**
     * 原生透传执行动态 EL 表达式（不自动校验失败，保留官方逃生通道）
     *
     * @param el             动态 EL 表达式
     * @param param          流程入参
     * @param contextClasses 上下文 Class
     * @return 原始 LiteflowResponse
     */
    public LiteflowResponse execute2RespWithEL(String el, Object param, Class<?>... contextClasses) {
        return this.flowExecutor.execute2RespWithEL(el, param, null, contextClasses);
    }

    /**
     * 主动触发规则重载（从已配置的全部规则源重新拉取全量规则）
     */
    public void reloadRule() {
        this.flowExecutor.reloadRule();
    }

    /**
     * 动态热重载全量规则定义内容
     * 自动推导 XML / JSON / YML 解析器并热生效至 FlowBus
     *
     * @param ruleContent 规则定义文本内容
     */
    public void reloadRule(String ruleContent) {
        if (ruleContent == null || ruleContent.isBlank()) {
            this.flowExecutor.reloadRule();
            return;
        }
        try {
            String trimmed = ruleContent.trim();
            FlowParserTypeEnum type;
            if (trimmed.startsWith("<")) {
                type = FlowParserTypeEnum.TYPE_EL_XML;
            } else if (trimmed.startsWith("{")) {
                type = FlowParserTypeEnum.TYPE_EL_JSON;
            } else {
                type = FlowParserTypeEnum.TYPE_EL_YML;
            }
            FlowBus.refreshFlowMetaData(type, ruleContent);
            log.info("ateng-boot-liteflow: 成功热更新全量规则定义 [format={}]", type.getName());
        } catch (Exception e) {
            throw new LiteflowExecutionException("DYNAMIC_RULE_RELOAD", null,
                    "动态重载 LiteFlow 规则内容失败: " + e.getMessage(), e);
        }
    }

    /**
     * 动态热更新或新建单条规则流程链
     *
     * @param chainId 目标规则链 ID
     * @param el      新的 EL 表达式定义
     */
    public void reloadChain(String chainId, String el) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId 不能为空");
        }
        if (el == null || el.isBlank()) {
            throw new IllegalArgumentException("el 表达式不能为空");
        }
        try {
            FlowBus.reloadChain(chainId, el);
            log.info("ateng-boot-liteflow: 成功动态更新/新建规则链 [{}] -> [{}]", chainId, el);
        } catch (Exception e) {
            throw new LiteflowExecutionException(chainId, null,
                    "动态重载 LiteFlow 流程链 [" + chainId + "] 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取官方底层 {@link FlowExecutor} 原生实例（高阶操作逃生舱）
     *
     * @return 官方 FlowExecutor 实例
     */
    public FlowExecutor getFlowExecutor() {
        return this.flowExecutor;
    }

    /**
     * 检查响应执行状态，若失败则转译抛出受管业务异常
     */
    private void checkResponseSuccess(String chainId, LiteflowResponse response) {
        if (response != null && !response.isSuccess()) {
            Throwable cause = response.getCause();
            String failedNodeId = resolveFailedNodeId(response);
            String message = formatErrorMessage(chainId, failedNodeId, response);
            throw new LiteflowExecutionException(chainId, failedNodeId, message, cause);
        }
    }

    /**
     * 从响应执行步骤队列中解析第一个失败的组件节点标识
     */
    private String resolveFailedNodeId(LiteflowResponse response) {
        if (response == null || response.getSlot() == null) {
            return null;
        }
        try {
            Queue<CmpStep> queue = response.getExecuteStepQueue();
            if (queue != null && !queue.isEmpty()) {
                for (CmpStep step : queue) {
                    if (!step.isSuccess() || step.getException() != null) {
                        return step.getNodeId();
                    }
                }
            }
        } catch (Throwable ignored) {
            // 防御性忽略步骤解析次生异常
        }
        return null;
    }

    /**
     * 格式化失败异常提示信息
     */
    private String formatErrorMessage(String chainId, String failedNodeId, LiteflowResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("LiteFlow 规则链执行失败 [chainId=").append(chainId).append("]");
        if (failedNodeId != null && !failedNodeId.isBlank()) {
            sb.append(" [failedNodeId=").append(failedNodeId).append("]");
        }
        if (response != null) {
            if (response.getMessage() != null && !response.getMessage().isBlank()) {
                sb.append(": ").append(response.getMessage());
            } else if (response.getCause() != null && response.getCause().getMessage() != null) {
                sb.append(": ").append(response.getCause().getMessage());
            }
        }
        return sb.toString();
    }
}

package io.github.atengk.boot.liteflow.core;

import com.yomahub.liteflow.builder.el.LiteFlowChainELBuilder;
import com.yomahub.liteflow.common.entity.ValidationResp;
import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.enums.FlowParserTypeEnum;
import com.yomahub.liteflow.exception.NoMatchedRouteChainException;
import com.yomahub.liteflow.exception.RouteChainNotFoundException;
import com.yomahub.liteflow.flow.FlowBus;
import com.yomahub.liteflow.flow.LiteflowResponse;
import com.yomahub.liteflow.flow.element.Chain;
import com.yomahub.liteflow.flow.entity.CmpStep;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.util.SpringUtil;
import io.github.atengk.boot.liteflow.exception.LiteflowExecutionException;
import io.github.atengk.boot.liteflow.properties.AtengLiteflowProperties;
import io.github.atengk.boot.liteflow.route.LiteflowRouteResult;
import io.github.atengk.boot.liteflow.thread.FlowThreadTracker;
import io.github.atengk.boot.liteflow.trace.LiteflowExecutionTrace;
import io.github.atengk.boot.liteflow.trace.LiteflowTraceResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * LiteFlow 强类型统一操作门面
 * 提供自动校验解包、受管因果异常转译、结构化时序轨迹提取、受管异步流式编排、动态 EL 即时执行与规则热重载能力，
 * 封装底层 {@link FlowExecutor} 原始调用并保留逃生舱
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class LiteflowTemplate {

    private static final Logger log = LoggerFactory.getLogger(LiteflowTemplate.class);

    private final FlowExecutor flowExecutor;
    private final Executor asyncExecutor;
    private final AtengLiteflowProperties properties;
    private final ConcurrentMap<String, Deque<String>> chainHistorySnapshots = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> activeChainEls = new ConcurrentHashMap<>();

    public LiteflowTemplate(FlowExecutor flowExecutor) {
        this(flowExecutor, null, null);
    }

    public LiteflowTemplate(FlowExecutor flowExecutor, Executor asyncExecutor) {
        this(flowExecutor, asyncExecutor, null);
    }

    public LiteflowTemplate(FlowExecutor flowExecutor, Executor asyncExecutor, AtengLiteflowProperties properties) {
        this.flowExecutor = Objects.requireNonNull(flowExecutor, "flowExecutor 不能为空");
        this.asyncExecutor = asyncExecutor;
        this.properties = properties;
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
        long start = System.currentTimeMillis();
        LiteflowResponse response = this.flowExecutor.execute2Resp(chainId, param, contextClass);
        long duration = System.currentTimeMillis() - start;
        LiteflowExecutionTrace trace = handleTraceAndAudit(chainId, response, duration);
        checkResponseSuccess(chainId, response, trace);
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
        long start = System.currentTimeMillis();
        LiteflowResponse response = this.flowExecutor.execute2Resp(chainId, param, contextBean);
        long duration = System.currentTimeMillis() - start;
        LiteflowExecutionTrace trace = handleTraceAndAudit(chainId, response, duration);
        checkResponseSuccess(chainId, response, trace);
        return contextBean;
    }

    /**
     * 同步执行规则链（传递多个已预初始化的上下文实例）
     * 适用于需要跨多个领域上下文协同编排的复杂业务场景
     *
     * @param chainId      规则链 ID
     * @param param        流程入参（可为 null）
     * @param contextBeans 已初始化的多个上下文实例
     * @return 传入的原样上下文实例数组
     */
    public Object[] executeMultipleWithContext(String chainId, Object param, Object... contextBeans) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId 不能为空");
        }
        if (contextBeans == null || contextBeans.length == 0) {
            throw new IllegalArgumentException("contextBeans 不能为空");
        }
        long start = System.currentTimeMillis();
        LiteflowResponse response = this.flowExecutor.execute2Resp(chainId, param, contextBeans);
        long duration = System.currentTimeMillis() - start;
        LiteflowExecutionTrace trace = handleTraceAndAudit(chainId, response, duration);
        checkResponseSuccess(chainId, response, trace);
        return contextBeans;
    }

    /**
     * 同步执行规则链（传递多个已预初始化的上下文实例）并返回带有结构化链路轨迹的结果包装
     *
     * @param chainId      规则链 ID
     * @param param        流程入参（可为 null）
     * @param contextBeans 已初始化的多个上下文实例
     * @return 包含上下文实例数组与时序轨迹的结果包装
     */
    public LiteflowTraceResult<Object[]> executeMultipleWithContextWithTrace(String chainId, Object param, Object... contextBeans) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId 不能为空");
        }
        if (contextBeans == null || contextBeans.length == 0) {
            throw new IllegalArgumentException("contextBeans 不能为空");
        }
        long start = System.currentTimeMillis();
        LiteflowResponse response = this.flowExecutor.execute2Resp(chainId, param, contextBeans);
        long duration = System.currentTimeMillis() - start;
        LiteflowExecutionTrace trace = handleTraceAndAudit(chainId, response, duration);
        checkResponseSuccess(chainId, response, trace);
        return new LiteflowTraceResult<>(contextBeans, trace);
    }

    /**
     * 同步执行规则链并返回带有结构化链路轨迹的结果包装（无入参重载）
     *
     * @param chainId      规则链 ID
     * @param contextClass 上下文类型
     * @param <T>          上下文泛型
     * @return 包含强类型上下文与时序轨迹的结果包装
     */
    public <T> LiteflowTraceResult<T> executeWithTrace(String chainId, Class<T> contextClass) {
        return executeWithTrace(chainId, null, contextClass);
    }

    /**
     * 同步执行规则链并返回带有结构化链路轨迹的结果包装
     * 若失败抛出携带时序轨迹的受管异常，成功返回强类型上下文与时序轨迹
     *
     * @param chainId      规则链 ID
     * @param param        流程入参
     * @param contextClass 上下文类型
     * @param <T>          上下文泛型
     * @return 包含强类型上下文与时序轨迹的结果包装
     */
    public <T> LiteflowTraceResult<T> executeWithTrace(String chainId, Object param, Class<T> contextClass) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId 不能为空");
        }
        if (contextClass == null) {
            throw new IllegalArgumentException("contextClass 不能为空");
        }
        long start = System.currentTimeMillis();
        LiteflowResponse response = this.flowExecutor.execute2Resp(chainId, param, contextClass);
        long duration = System.currentTimeMillis() - start;
        LiteflowExecutionTrace trace = handleTraceAndAudit(chainId, response, duration);
        checkResponseSuccess(chainId, response, trace);
        T context = response.getContextBean(contextClass);
        return new LiteflowTraceResult<>(context, trace);
    }

    /**
     * 同步执行规则链（预置上下文实例重载）并返回带有结构化链路轨迹的结果包装
     *
     * @param chainId     规则链 ID
     * @param contextBean 已初始化的上下文实例
     * @param <T>         上下文泛型
     * @return 包含同一上下文实例与时序轨迹的结果包装
     */
    public <T> LiteflowTraceResult<T> executeWithTrace(String chainId, T contextBean) {
        return executeWithTrace(chainId, null, contextBean);
    }

    /**
     * 同步执行规则链（预置上下文实例）并返回带有结构化链路轨迹的结果包装
     *
     * @param chainId     规则链 ID
     * @param param       流程入参
     * @param contextBean 已初始化的上下文实例
     * @param <T>         上下文泛型
     * @return 包含同一上下文实例与时序轨迹的结果包装
     */
    public <T> LiteflowTraceResult<T> executeWithTrace(String chainId, Object param, T contextBean) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId 不能为空");
        }
        Objects.requireNonNull(contextBean, "contextBean 不能为空");
        long start = System.currentTimeMillis();
        LiteflowResponse response = this.flowExecutor.execute2Resp(chainId, param, contextBean);
        long duration = System.currentTimeMillis() - start;
        LiteflowExecutionTrace trace = handleTraceAndAudit(chainId, response, duration);
        checkResponseSuccess(chainId, response, trace);
        return new LiteflowTraceResult<>(contextBean, trace);
    }

    /**
     * 执行多上下文规则链（无入参重载）
     *
     * @param chainId             规则链 ID
     * @param firstContextClass   首个上下文类型
     * @param secondContextClass  第二个上下文类型
     * @param otherContextClasses 其余上下文类型
     * @return 执行成功响应包装
     */
    public LiteflowResponse execute(String chainId, Class<?> firstContextClass, Class<?> secondContextClass, Class<?>... otherContextClasses) {
        return execute(chainId, null, firstContextClass, secondContextClass, otherContextClasses);
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
        long start = System.currentTimeMillis();
        LiteflowResponse response = this.flowExecutor.execute2Resp(chainId, param, classes);
        long duration = System.currentTimeMillis() - start;
        LiteflowExecutionTrace trace = handleTraceAndAudit(chainId, response, duration);
        checkResponseSuccess(chainId, response, trace);
        return response;
    }

    /**
     * 执行多上下文规则链（无入参重载）
     *
     * @param chainId        规则链 ID
     * @param contextClasses 多个上下文类型
     * @return 执行成功响应包装
     */
    public LiteflowResponse executeMultiple(String chainId, Class<?>... contextClasses) {
        return executeMultiple(chainId, null, contextClasses);
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
        long start = System.currentTimeMillis();
        LiteflowResponse response = this.flowExecutor.execute2Resp(chainId, param, contextClasses);
        long duration = System.currentTimeMillis() - start;
        LiteflowExecutionTrace trace = handleTraceAndAudit(chainId, response, duration);
        checkResponseSuccess(chainId, response, trace);
        return response;
    }

    /**
     * 执行多上下文规则链并返回带有结构化链路轨迹的响应包装
     *
     * @param chainId        规则链 ID
     * @param param          流程入参
     * @param contextClasses 多个上下文类型
     * @return 包含原始响应与时序轨迹的结果包装
     */
    public LiteflowTraceResult<LiteflowResponse> executeMultipleWithTrace(String chainId, Object param, Class<?>... contextClasses) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId 不能为空");
        }
        long start = System.currentTimeMillis();
        LiteflowResponse response = this.flowExecutor.execute2Resp(chainId, param, contextClasses);
        long duration = System.currentTimeMillis() - start;
        LiteflowExecutionTrace trace = handleTraceAndAudit(chainId, response, duration);
        checkResponseSuccess(chainId, response, trace);
        return new LiteflowTraceResult<>(response, trace);
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
        return executeAsync(chainId, param, contextClass, null);
    }

    /**
     * 受管异步流式执行规则链，带超时时间上限约束
     * 自动挂载 {@link AsyncContext} 四维上下文跨线程快照透传与清理，失败或超时时通过 CompletableFuture 抛出受管异常
     *
     * @param chainId      规则链 ID
     * @param param        流程入参
     * @param contextClass 上下文类型
     * @param timeout      异步执行硬超时时长约束（为 null 或 <= 0 则不设上限）
     * @param <T>          上下文泛型
     * @return 承载强类型上下文的 CompletableFuture
     */
    public <T> CompletableFuture<T> executeAsync(String chainId, Object param, Class<T> contextClass, Duration timeout) {
        Supplier<T> task = AsyncContext.wrapSupplier(() -> execute(chainId, param, contextClass));
        CompletableFuture<T> future = CompletableFuture.supplyAsync(task, resolveAsyncExecutor());
        if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
            return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        return future;
    }

    /**
     * 受管异步流式执行规则链（预置上下文实例无参重载）
     *
     * @param chainId     规则链 ID
     * @param contextBean 已初始化的上下文实例
     * @param <T>         上下文泛型
     * @return 承载同一强类型上下文的 CompletableFuture
     */
    public <T> CompletableFuture<T> executeAsyncWithContext(String chainId, T contextBean) {
        return executeAsyncWithContext(chainId, null, contextBean, null);
    }

    /**
     * 受管异步流式执行规则链（预置上下文实例）
     * 自动挂载 {@link AsyncContext} 四维上下文跨线程快照透传与清理，失败时通过 CompletableFuture 抛出受管异常
     *
     * @param chainId     规则链 ID
     * @param param       流程入参
     * @param contextBean 已初始化的上下文实例
     * @param <T>         上下文泛型
     * @return 承载同一强类型上下文的 CompletableFuture
     */
    public <T> CompletableFuture<T> executeAsyncWithContext(String chainId, Object param, T contextBean) {
        return executeAsyncWithContext(chainId, param, contextBean, null);
    }

    /**
     * 受管异步流式执行规则链（预置上下文实例），带超时时间上限约束
     * 自动挂载 {@link AsyncContext} 四维上下文跨线程快照透传与清理，失败或超时时通过 CompletableFuture 抛出受管异常
     *
     * @param chainId     规则链 ID
     * @param param       流程入参
     * @param contextBean 已初始化的上下文实例
     * @param timeout     异步执行硬超时时长约束（为 null 或 <= 0 则不设上限）
     * @param <T>         上下文泛型
     * @return 承载同一强类型上下文的 CompletableFuture
     */
    public <T> CompletableFuture<T> executeAsyncWithContext(String chainId, Object param, T contextBean, Duration timeout) {
        Supplier<T> task = AsyncContext.wrapSupplier(() -> executeWithContext(chainId, param, contextBean));
        CompletableFuture<T> future = CompletableFuture.supplyAsync(task, resolveAsyncExecutor());
        if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
            return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        return future;
    }

    /**
     * 受管异步流式执行规则链并返回带有结构化链路轨迹的结果包装（无入参重载）
     *
     * @param chainId      规则链 ID
     * @param contextClass 上下文类型
     * @param <T>          上下文泛型
     * @return 包含强类型上下文与时序轨迹包装的 CompletableFuture
     */
    public <T> CompletableFuture<LiteflowTraceResult<T>> executeAsyncWithTrace(String chainId, Class<T> contextClass) {
        return executeAsyncWithTrace(chainId, null, contextClass, null);
    }

    /**
     * 受管异步流式执行规则链并返回带有结构化链路轨迹的结果包装
     *
     * @param chainId      规则链 ID
     * @param param        流程入参
     * @param contextClass 上下文类型
     * @param <T>          上下文泛型
     * @return 包含强类型上下文与时序轨迹包装的 CompletableFuture
     */
    public <T> CompletableFuture<LiteflowTraceResult<T>> executeAsyncWithTrace(String chainId, Object param, Class<T> contextClass) {
        return executeAsyncWithTrace(chainId, param, contextClass, null);
    }

    /**
     * 受管异步流式执行规则链并返回带有结构化链路轨迹的结果包装，带超时时间上限约束
     *
     * @param chainId      规则链 ID
     * @param param        流程入参
     * @param contextClass 上下文类型
     * @param timeout      异步执行硬超时时长约束（为 null 或 <= 0 则不设上限）
     * @param <T>          上下文泛型
     * @return 包含强类型上下文与时序轨迹包装的 CompletableFuture
     */
    public <T> CompletableFuture<LiteflowTraceResult<T>> executeAsyncWithTrace(String chainId, Object param, Class<T> contextClass, Duration timeout) {
        Supplier<LiteflowTraceResult<T>> task = AsyncContext.wrapSupplier(() -> executeWithTrace(chainId, param, contextClass));
        CompletableFuture<LiteflowTraceResult<T>> future = CompletableFuture.supplyAsync(task, resolveAsyncExecutor());
        if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
            return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        return future;
    }

    /**
     * 受管异步流式执行规则链（预置上下文实例无参重载）并返回带有结构化链路轨迹的结果包装
     *
     * @param chainId     规则链 ID
     * @param contextBean 已初始化的上下文实例
     * @param <T>         上下文泛型
     * @return 包含同一上下文实例与时序轨迹包装的 CompletableFuture
     */
    public <T> CompletableFuture<LiteflowTraceResult<T>> executeAsyncWithTrace(String chainId, T contextBean) {
        return executeAsyncWithTrace(chainId, null, contextBean, null);
    }

    /**
     * 受管异步流式执行规则链（预置上下文实例）并返回带有结构化链路轨迹的结果包装
     *
     * @param chainId     规则链 ID
     * @param param       流程入参
     * @param contextBean 已初始化的上下文实例
     * @param <T>         上下文泛型
     * @return 包含同一上下文实例与时序轨迹包装的 CompletableFuture
     */
    public <T> CompletableFuture<LiteflowTraceResult<T>> executeAsyncWithTrace(String chainId, Object param, T contextBean) {
        return executeAsyncWithTrace(chainId, param, contextBean, null);
    }

    /**
     * 受管异步流式执行规则链（预置上下文实例）并返回带有结构化链路轨迹的结果包装，带超时时间上限约束
     *
     * @param chainId     规则链 ID
     * @param param       流程入参
     * @param contextBean 已初始化的上下文实例
     * @param timeout     异步执行硬超时时长约束（为 null 或 <= 0 则不设上限）
     * @param <T>         上下文泛型
     * @return 包含同一上下文实例与时序轨迹包装的 CompletableFuture
     */
    public <T> CompletableFuture<LiteflowTraceResult<T>> executeAsyncWithTrace(String chainId, Object param, T contextBean, Duration timeout) {
        Supplier<LiteflowTraceResult<T>> task = AsyncContext.wrapSupplier(() -> executeWithTrace(chainId, param, contextBean));
        CompletableFuture<LiteflowTraceResult<T>> future = CompletableFuture.supplyAsync(task, resolveAsyncExecutor());
        if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
            return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        return future;
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
        long start = System.currentTimeMillis();
        LiteflowResponse response = this.flowExecutor.execute2RespWithEL(el, param, null, contextClass);
        long duration = System.currentTimeMillis() - start;
        String chainId = resolveDynamicChainId(el);
        LiteflowExecutionTrace trace = handleTraceAndAudit(chainId, response, duration);
        checkResponseSuccess(chainId, response, trace);
        return response.getContextBean(contextClass);
    }

    /**
     * 直接即时执行动态 EL 表达式（预置上下文实例无参重载）
     *
     * @param el          动态 EL 表达式
     * @param contextBean 已初始化的上下文实例
     * @param <T>         上下文泛型
     * @return 同一强类型上下文实例
     */
    public <T> T executeElWithContext(String el, T contextBean) {
        return executeElWithContext(el, null, contextBean);
    }

    /**
     * 直接即时执行动态 EL 表达式（预置上下文实例）
     *
     * @param el          动态 EL 表达式
     * @param param       流程入参
     * @param contextBean 已初始化的上下文实例
     * @param <T>         上下文泛型
     * @return 同一强类型上下文实例
     */
    public <T> T executeElWithContext(String el, Object param, T contextBean) {
        if (el == null || el.isBlank()) {
            throw new IllegalArgumentException("el 表达式不能为空");
        }
        Objects.requireNonNull(contextBean, "contextBean 不能为空");
        long start = System.currentTimeMillis();
        LiteflowResponse response = this.flowExecutor.execute2RespWithEL(el, param, null, contextBean);
        long duration = System.currentTimeMillis() - start;
        String chainId = resolveDynamicChainId(el);
        LiteflowExecutionTrace trace = handleTraceAndAudit(chainId, response, duration);
        checkResponseSuccess(chainId, response, trace);
        return contextBean;
    }

    /**
     * 直接即时执行动态 EL 表达式并返回带有结构化链路轨迹的结果包装（无入参重载）
     *
     * @param el           动态 EL 表达式
     * @param contextClass 上下文类型
     * @param <T>          上下文泛型
     * @return 包含强类型上下文与时序轨迹的结果包装
     */
    public <T> LiteflowTraceResult<T> executeElWithTrace(String el, Class<T> contextClass) {
        return executeElWithTrace(el, null, contextClass);
    }

    /**
     * 直接即时执行动态 EL 表达式并返回带有结构化链路轨迹的结果包装
     *
     * @param el           动态 EL 表达式
     * @param param        流程入参
     * @param contextClass 上下文类型
     * @param <T>          上下文泛型
     * @return 包含强类型上下文与时序轨迹的结果包装
     */
    public <T> LiteflowTraceResult<T> executeElWithTrace(String el, Object param, Class<T> contextClass) {
        if (el == null || el.isBlank()) {
            throw new IllegalArgumentException("el 表达式不能为空");
        }
        if (contextClass == null) {
            throw new IllegalArgumentException("contextClass 不能为空");
        }
        long start = System.currentTimeMillis();
        LiteflowResponse response = this.flowExecutor.execute2RespWithEL(el, param, null, contextClass);
        long duration = System.currentTimeMillis() - start;
        String chainId = resolveDynamicChainId(el);
        LiteflowExecutionTrace trace = handleTraceAndAudit(chainId, response, duration);
        checkResponseSuccess(chainId, response, trace);
        T context = response.getContextBean(contextClass);
        return new LiteflowTraceResult<>(context, trace);
    }

    /**
     * 直接即时执行动态 EL 表达式（预置上下文实例无参重载）并返回带有结构化链路轨迹的结果包装
     *
     * @param el          动态 EL 表达式
     * @param contextBean 已初始化的上下文实例
     * @param <T>         上下文泛型
     * @return 包含同一强类型上下文与时序轨迹的结果包装
     */
    public <T> LiteflowTraceResult<T> executeElWithTrace(String el, T contextBean) {
        return executeElWithTrace(el, null, contextBean);
    }

    /**
     * 直接即时执行动态 EL 表达式（预置上下文实例）并返回带有结构化链路轨迹的结果包装
     *
     * @param el          动态 EL 表达式
     * @param param       流程入参
     * @param contextBean 已初始化的上下文实例
     * @param <T>         上下文泛型
     * @return 包含同一强类型上下文与时序轨迹的结果包装
     */
    public <T> LiteflowTraceResult<T> executeElWithTrace(String el, Object param, T contextBean) {
        if (el == null || el.isBlank()) {
            throw new IllegalArgumentException("el 表达式不能为空");
        }
        Objects.requireNonNull(contextBean, "contextBean 不能为空");
        long start = System.currentTimeMillis();
        LiteflowResponse response = this.flowExecutor.execute2RespWithEL(el, param, null, contextBean);
        long duration = System.currentTimeMillis() - start;
        String chainId = resolveDynamicChainId(el);
        LiteflowExecutionTrace trace = handleTraceAndAudit(chainId, response, duration);
        checkResponseSuccess(chainId, response, trace);
        return new LiteflowTraceResult<>(contextBean, trace);
    }

    /**
     * 同步执行决策路由表（Route Chain）全链路编排并聚合至共享强类型上下文（无入参重载）
     * 自动在指定业务命名空间下根据所有路由条件进行多规则并行/串行匹配，
     * 所有命中的流程链将按序在同一强类型上下文实例上累计应用业务状态变更；
     * 若未匹配到任何路由链，保底静默返回原始未修改上下文
     *
     * @param namespace   业务路由命名空间（可选，留空则匹配默认命名空间下的所有路由链）
     * @param contextBean 共享预置强类型业务上下文实例
     * @param <T>         上下文泛型
     * @return 聚合所有命中规则变更后的同一强类型业务上下文实例
     */
    public <T> T executeRoute(String namespace, T contextBean) {
        return executeRoute(namespace, null, contextBean);
    }

    /**
     * 同步执行决策路由表（Route Chain）全链路编排并聚合至共享强类型上下文
     * 自动在指定业务命名空间下根据所有路由条件进行多规则匹配，
     * 所有命中的流程链将按序在同一强类型上下文实例上累计应用业务状态变更；
     * 若未匹配到任何路由链，保底静默返回原始未修改上下文；若任何命中规则执行失败，立即中断并抛出携带完整因果链的受管业务异常
     *
     * @param namespace   业务路由命名空间（可选，留空则匹配默认命名空间下的所有路由链）
     * @param param       流程通用入参
     * @param contextBean 共享预置强类型业务上下文实例
     * @param <T>         上下文泛型
     * @return 聚合所有命中规则变更后的同一强类型业务上下文实例
     */
    public <T> T executeRoute(String namespace, Object param, T contextBean) {
        if (contextBean == null) {
            throw new IllegalArgumentException("contextBean 不能为空");
        }
        List<LiteflowResponse> responses;
        try {
            responses = (namespace != null && !namespace.isBlank())
                    ? this.flowExecutor.executeRouteChain(namespace.trim(), param, contextBean)
                    : this.flowExecutor.executeRouteChain(param, contextBean);
        } catch (RouteChainNotFoundException | NoMatchedRouteChainException e) {
            log.debug("ateng-boot-liteflow: 命名空间 [{}] 未匹配到任何决策路由链，保底返回原始上下文: {}", namespace, e.getMessage());
            responses = Collections.emptyList();
        }

        if (responses == null || responses.isEmpty()) {
            log.debug("ateng-boot-liteflow: 决策路由表在命名空间 [{}] 未匹配到任何有效流程链，保底返回原始业务上下文", namespace);
            return contextBean;
        }

        for (LiteflowResponse response : responses) {
            String chainId = response != null ? response.getChainId() : "ROUTE_CHAIN";
            LiteflowExecutionTrace trace = handleTraceAndAudit(chainId, response, -1L);
            checkResponseSuccess(chainId, response, trace);
        }
        return contextBean;
    }

    /**
     * 同步执行决策路由表（Route Chain）全链路编排并返回携带结构化时序轨迹与命中元数据的聚合结果包装（无入参重载）
     *
     * @param namespace   业务路由命名空间（可选，留空则匹配默认命名空间下的所有路由链）
     * @param contextBean 共享预置强类型业务上下文实例
     * @param <T>         上下文泛型
     * @return 决策路由聚合执行结果包装（包含聚合上下文、命中流程链列表、全时序轨迹与耗时）
     */
    public <T> LiteflowRouteResult<T> executeRouteWithTrace(String namespace, T contextBean) {
        return executeRouteWithTrace(namespace, null, contextBean);
    }

    /**
     * 同步执行决策路由表（Route Chain）全链路编排并返回携带结构化时序轨迹与命中元数据的聚合结果包装
     *
     * @param namespace   业务路由命名空间（可选，留空则匹配默认命名空间下的所有路由链）
     * @param param       流程通用入参
     * @param contextBean 共享预置强类型业务上下文实例
     * @param <T>         上下文泛型
     * @return 决策路由聚合执行结果包装（包含聚合上下文、命中流程链列表、全时序轨迹与耗时）
     */
    public <T> LiteflowRouteResult<T> executeRouteWithTrace(String namespace, Object param, T contextBean) {
        if (contextBean == null) {
            throw new IllegalArgumentException("contextBean 不能为空");
        }
        long start = System.currentTimeMillis();
        List<LiteflowResponse> responses;
        try {
            responses = (namespace != null && !namespace.isBlank())
                    ? this.flowExecutor.executeRouteChain(namespace.trim(), param, contextBean)
                    : this.flowExecutor.executeRouteChain(param, contextBean);
        } catch (RouteChainNotFoundException | NoMatchedRouteChainException e) {
            log.debug("ateng-boot-liteflow: 命名空间 [{}] 未匹配到任何决策路由链: {}", namespace, e.getMessage());
            responses = Collections.emptyList();
        }
        long totalDuration = System.currentTimeMillis() - start;

        if (responses == null || responses.isEmpty()) {
            log.debug("ateng-boot-liteflow: 决策路由表在命名空间 [{}] 未匹配到任何有效流程链", namespace);
            return new LiteflowRouteResult<>(contextBean, Collections.emptyList(), Collections.emptyList(), totalDuration);
        }

        List<String> matchedChainIds = new ArrayList<>(responses.size());
        List<LiteflowExecutionTrace> traces = new ArrayList<>(responses.size());

        for (LiteflowResponse response : responses) {
            String chainId = response != null ? response.getChainId() : "ROUTE_CHAIN";
            LiteflowExecutionTrace trace = handleTraceAndAudit(chainId, response, -1L);
            checkResponseSuccess(chainId, response, trace);
            matchedChainIds.add(chainId);
            traces.add(trace);
        }
        return new LiteflowRouteResult<>(contextBean, matchedChainIds, traces, totalDuration);
    }

    /**
     * 从原始 LiteflowResponse 中提取结构化时序轨迹
     *
     * @param response LiteFlow 执行响应
     * @return 结构化时序轨迹
     */
    public LiteflowExecutionTrace extractTrace(LiteflowResponse response) {
        String chainId = response != null ? response.getChainId() : "UNKNOWN";
        return extractTrace(chainId, response, -1L);
    }

    /**
     * 从原始 LiteflowResponse 中提取结构化时序轨迹
     *
     * @param chainId  流程链 ID
     * @param response LiteFlow 执行响应
     * @return 结构化时序轨迹
     */
    public LiteflowExecutionTrace extractTrace(String chainId, LiteflowResponse response) {
        return extractTrace(chainId, response, -1L);
    }

    /**
     * 从原始 LiteflowResponse 中提取结构化时序轨迹
     *
     * @param chainId    流程链 ID
     * @param response   LiteFlow 执行响应
     * @param durationMs 总耗时（毫秒）
     * @return 结构化时序轨迹
     */
    public LiteflowExecutionTrace extractTrace(String chainId, LiteflowResponse response, long durationMs) {
        return handleTraceAndAudit(chainId, response, durationMs);
    }

    /**
     * 清理当前线程 MDC 中残留的 LiteFlow 链路轨迹信息
     */
    public void clearTraceMdc() {
        String mdcKey = (this.properties != null && this.properties.getTrace() != null)
                ? this.properties.getTrace().getMdcKey() : null;
        LiteflowExecutionTrace.clearFromMdc(mdcKey);
    }

    /**
     * 清理当前线程 MDC 中默认键的 LiteFlow 链路轨迹信息
     */
    public static void clearMdcTrace() {
        LiteflowExecutionTrace.clearFromMdc();
    }

    /**
     * 开启受管 MDC 轨迹注入作用域（支持 try-with-resources 自动清理）
     *
     * @param trace 时序链路轨迹
     * @return 自动清理的 AutoCloseable 实例
     */
    public AutoCloseable withTraceMdcScope(LiteflowExecutionTrace trace) {
        if (trace == null) {
            return () -> {
            };
        }
        String mdcKey = (this.properties != null && this.properties.getTrace() != null)
                ? this.properties.getTrace().getMdcKey() : LiteflowExecutionTrace.DEFAULT_MDC_TRACE_KEY;
        trace.injectToMdc(mdcKey);
        return () -> LiteflowExecutionTrace.clearFromMdc(mdcKey);
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
     * 快速校验 EL 规则编排表达式语法是否合法有效
     *
     * @param el 待校验的 EL 表达式
     * @return true 表示合法有效，false 表示存在语法或组件缺失等错误
     */
    public boolean isValidEl(String el) {
        if (el == null || el.isBlank()) {
            return false;
        }
        try {
            return LiteFlowChainELBuilder.validate(el);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 校验 EL 规则编排表达式语法并返回结构化诊断结果
     *
     * @param el 待校验的 EL 表达式
     * @return 结构化校验结果（包含成功状态与详细失败原因）
     */
    public LiteflowElValidationResult validateEl(String el) {
        if (el == null || el.isBlank()) {
            return LiteflowElValidationResult.fail(el, "EL 表达式不能为空");
        }
        try {
            ValidationResp resp = LiteFlowChainELBuilder.validateWithEx(el);
            if (resp != null && resp.isSuccess()) {
                return LiteflowElValidationResult.success(el);
            }
            String errorMsg = (resp != null && resp.getCause() != null)
                    ? resp.getCause().getMessage()
                    : "EL 表达式语法校验未通过";
            return LiteflowElValidationResult.fail(el, errorMsg);
        } catch (Throwable t) {
            return LiteflowElValidationResult.fail(el, "EL 表达式校验异常: " + t.getMessage());
        }
    }

    /**
     * 获取当前系统已编译加载就绪的全部规则流程链 ID 集合
     *
     * @return 规则流程链 ID 集合，保底返回不可变空集合
     */
    public Set<String> getLoadedChainIds() {
        try {
            Map<String, ?> chainMap = FlowBus.getChainMap();
            if (chainMap == null || chainMap.isEmpty()) {
                return Collections.emptySet();
            }
            return Collections.unmodifiableSet(new TreeSet<>(chainMap.keySet()));
        } catch (Throwable t) {
            log.warn("ateng-boot-liteflow: 获取已加载流程链列表异常: {}", t.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * 动态热更新或新建单条规则流程链
     * 先行校验 EL 表达式合法性，并在热更新前自动捕获有界历史版本快照，防御因非法表达式导致线上既有规则链被破坏
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
        LiteflowElValidationResult validationResult = validateEl(el);
        if (!validationResult.isValid()) {
            throw new LiteflowExecutionException(chainId, null,
                    "动态重载 LiteFlow 流程链 [" + chainId + "] 失败，EL 表达式语法校验未通过: " + validationResult.getErrorMessage(), null);
        }

        // 1. 若当前规则链已有运行中定义，自动拍摄有界历史版本快照入栈
        String existingEl = resolveCurrentChainEl(chainId);
        if (existingEl != null && !existingEl.equals(el)) {
            recordSnapshot(chainId, existingEl);
        }

        // 2. 编译更新并同步记录当前活跃 EL
        try {
            FlowBus.reloadChain(chainId, el);
            activeChainEls.put(chainId, el);
            log.info("ateng-boot-liteflow: 成功动态更新/新建规则链 [{}] -> [{}]", chainId, el);
        } catch (Exception e) {
            throw new LiteflowExecutionException(chainId, null,
                    "动态重载 LiteFlow 流程链 [" + chainId + "] 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 动态热更新或新建带有决策路由条件的规则流程链（默认命名空间）
     *
     * @param chainId 目标规则链 ID
     * @param routeEl 决策路由条件表达式
     * @param bodyEl  规则流程主体 EL 表达式
     */
    public void reloadRouteChain(String chainId, String routeEl, String bodyEl) {
        reloadRouteChain(chainId, null, routeEl, bodyEl);
    }

    /**
     * 动态热更新或新建带有决策路由条件的规则流程链
     * 先行校验主体 EL 与路由条件表达式语法合法性，并在热更新前自动捕获有界历史版本快照，
     * 防御因非法表达式导致线上既有规则链被破坏
     *
     * @param chainId   目标规则链 ID
     * @param namespace 业务路由命名空间（可选）
     * @param routeEl   决策路由条件表达式
     * @param bodyEl    规则流程主体 EL 表达式
     */
    public void reloadRouteChain(String chainId, String namespace, String routeEl, String bodyEl) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId 不能为空");
        }
        if (bodyEl == null || bodyEl.isBlank()) {
            throw new IllegalArgumentException("bodyEl 表达式不能为空");
        }
        if (routeEl == null || routeEl.isBlank()) {
            throw new IllegalArgumentException("routeEl 表达式不能为空");
        }

        LiteflowElValidationResult bodyValidation = validateEl(bodyEl);
        if (!bodyValidation.isValid()) {
            throw new LiteflowExecutionException(chainId, null,
                    "动态重载决策路由链 [" + chainId + "] 失败，bodyEl 表达式语法校验未通过: " + bodyValidation.getErrorMessage(), null);
        }
        LiteflowElValidationResult routeValidation = validateEl(routeEl);
        if (!routeValidation.isValid()) {
            throw new LiteflowExecutionException(chainId, null,
                    "动态重载决策路由链 [" + chainId + "] 失败，routeEl 表达式语法校验未通过: " + routeValidation.getErrorMessage(), null);
        }

        // 1. 若当前规则链已有运行中定义，自动拍摄有界历史版本快照入栈
        String existingEl = resolveCurrentChainEl(chainId);
        if (existingEl != null && !existingEl.equals(bodyEl)) {
            recordSnapshot(chainId, existingEl);
        }

        // 2. 编译决策路由链并记录活跃 EL
        try {
            LiteFlowChainELBuilder builder = LiteFlowChainELBuilder.createChain()
                    .setChainId(chainId.trim())
                    .setRoute(routeEl.trim())
                    .setEL(bodyEl.trim());
            if (namespace != null && !namespace.isBlank()) {
                builder.setNamespace(namespace.trim());
            }
            builder.build();
            activeChainEls.put(chainId, bodyEl);
            log.info("ateng-boot-liteflow: 成功动态更新/新建决策路由链 [{}] [namespace={}] [route={}] -> [{}]",
                    chainId, namespace, routeEl, bodyEl);
        } catch (Exception e) {
            throw new LiteflowExecutionException(chainId, null,
                    "动态重载决策路由链 [" + chainId + "] 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将指定流程链一键故障回退至上一个有效的历史版本快照
     *
     * @param chainId 流程链 ID
     * @return true 表示成功回退至上一个快照；false 表示无历史快照可供回退
     */
    public boolean rollbackChain(String chainId) {
        if (chainId == null || chainId.isBlank()) {
            return false;
        }
        Deque<String> history = chainHistorySnapshots.get(chainId);
        if (history == null || history.isEmpty()) {
            log.warn("ateng-boot-liteflow: 流程链 [{}] 无历史快照可供回退", chainId);
            return false;
        }
        String previousEl;
        synchronized (history) {
            if (history.isEmpty()) {
                return false;
            }
            previousEl = history.removeFirst();
        }
        try {
            FlowBus.reloadChain(chainId, previousEl);
            activeChainEls.put(chainId, previousEl);
            log.info("ateng-boot-liteflow: 成功将流程链 [{}] 故障回退至历史版本 -> [{}]", chainId, previousEl);
            return true;
        } catch (Exception e) {
            log.error("ateng-boot-liteflow: 流程链 [{}] 回退至历史版本 [{}] 失败: {}", chainId, previousEl, e.getMessage(), e);
            throw new LiteflowExecutionException(chainId, null,
                    "流程链 [" + chainId + "] 回退至历史版本失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取指定流程链的历史版本 EL 快照列表（按时间倒序排列，最新快照在前）
     *
     * @param chainId 流程链 ID
     * @return 历史快照列表，若无快照则保底返回空列表
     */
    public List<String> getChainHistorySnapshots(String chainId) {
        if (chainId == null || chainId.isBlank()) {
            return Collections.emptyList();
        }
        Deque<String> history = chainHistorySnapshots.get(chainId);
        if (history == null || history.isEmpty()) {
            return Collections.emptyList();
        }
        synchronized (history) {
            return Collections.unmodifiableList(new ArrayList<>(history));
        }
    }

    private int getMaxHistorySnapshots() {
        if (properties != null && properties.getDynamicRule() != null) {
            return properties.getDynamicRule().getMaxHistorySnapshots();
        }
        return 5;
    }

    private String resolveCurrentChainEl(String chainId) {
        String active = activeChainEls.get(chainId);
        if (active != null && !active.isBlank()) {
            return active;
        }
        if (FlowBus.containChain(chainId)) {
            Chain chain = FlowBus.getChain(chainId);
            if (chain != null && chain.getEl() != null && !chain.getEl().isBlank()) {
                return chain.getEl();
            }
        }
        return null;
    }

    private void recordSnapshot(String chainId, String existingEl) {
        if (existingEl == null || existingEl.isBlank()) {
            return;
        }
        int max = getMaxHistorySnapshots();
        if (max <= 0) {
            return;
        }
        Deque<String> history = chainHistorySnapshots.computeIfAbsent(chainId, k -> new ConcurrentLinkedDeque<>());
        synchronized (history) {
            history.addFirst(existingEl);
            while (history.size() > max) {
                history.removeLast();
            }
        }
    }

    /**
     * 判断当前是否已加载指定规则流程链
     *
     * @param chainId 流程链 ID
     * @return 是否存在
     */
    public boolean containChain(String chainId) {
        if (chainId == null || chainId.isBlank()) {
            return false;
        }
        return FlowBus.containChain(chainId);
    }

    /**
     * 动态下线/移除指定的规则流程链
     *
     * @param chainId 流程链 ID
     * @return 是否成功移除
     */
    public boolean removeChain(String chainId) {
        if (chainId == null || chainId.isBlank()) {
            return false;
        }
        activeChainEls.remove(chainId);
        try {
            boolean removed = FlowBus.removeChain(chainId);
            log.info("ateng-boot-liteflow: 成功动态下线/移除规则链 [{}]", chainId);
            return removed;
        } catch (Exception e) {
            log.warn("ateng-boot-liteflow: 动态移除规则链 [{}] 失败: {}", chainId, e.getMessage(), e);
            return false;
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
     * 处理结构化时序轨迹构建与可选 MDC 审计日志自动注入
     */
    private LiteflowExecutionTrace handleTraceAndAudit(String chainId, LiteflowResponse response, long durationMs) {
        LiteflowExecutionTrace trace = LiteflowExecutionTrace.fromResponse(chainId, response, durationMs);
        if (this.properties != null && this.properties.getTrace() != null && this.properties.getTrace().isEnableMdc()) {
            trace.injectToMdc(this.properties.getTrace().getMdcKey());
        }
        return trace;
    }

    /**
     * 检查响应执行状态，若失败则转译抛出受管业务异常
     */
    private void checkResponseSuccess(String chainId, LiteflowResponse response, LiteflowExecutionTrace trace) {
        if (response != null && !response.isSuccess()) {
            Throwable cause = response.getCause();
            String failedNodeId = (trace != null && trace.getFailedStep() != null)
                    ? trace.getFailedStep().getNodeId()
                    : resolveFailedNodeId(response);
            String message = formatErrorMessage(chainId, failedNodeId, response);
            throw new LiteflowExecutionException(chainId, failedNodeId, trace, message, cause);
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

    /**
     * 构建动态 EL 链标识（保留完整 EL 表达式语句）
     */
    private String resolveDynamicChainId(String el) {
        return "DYNAMIC_EL[" + el + "]";
    }

    /**
     * 解析可用的受管异步线程池
     * 严禁在未配置受管 Executor 时直接沦陷至 ForkJoinPool.commonPool()
     */
    private Executor resolveAsyncExecutor() {
        if (this.asyncExecutor != null) {
            return this.asyncExecutor;
        }
        if (SpringUtil.isInitialized()) {
            try {
                Executor bean = SpringUtil.getBean("taskExecutor", Executor.class);
                if (bean != null) {
                    return bean;
                }
            } catch (Exception ignored) {
            }
            try {
                Executor bean = SpringUtil.getBean(Executor.class);
                if (bean != null) {
                    return bean;
                }
            } catch (Exception ignored) {
            }
        }
        throw new IllegalStateException("未注入受管异步线程池 Executor，禁止使用 ForkJoinPool 等野线程池执行 executeAsync；请向 LiteflowTemplate 注入受管 Executor (如 ThreadPoolTaskExecutor)");
    }
}

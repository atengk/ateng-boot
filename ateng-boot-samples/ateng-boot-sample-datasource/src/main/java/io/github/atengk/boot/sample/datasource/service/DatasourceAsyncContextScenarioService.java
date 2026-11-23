package io.github.atengk.boot.sample.datasource.service;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.sample.datasource.dto.AsyncContextPropagateRequest;
import io.github.atengk.boot.sample.datasource.vo.AsyncContextPropagateResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 异步上下文透传场景服务
 * <p>
 * 演示在多线程或异步调度时，通过 Core 受管线程池与 AsyncContext 将租户与数据源上下文透明透传至子线程。
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Slf4j
@Service
public class DatasourceAsyncContextScenarioService {

    private final DynamicDatasourceTemplate dynamicDatasourceTemplate;
    private final Executor taskExecutor;

    public DatasourceAsyncContextScenarioService(DynamicDatasourceTemplate dynamicDatasourceTemplate,
                                                 @Qualifier("taskExecutor") Executor taskExecutor) {
        this.dynamicDatasourceTemplate = dynamicDatasourceTemplate;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 在受管子线程中执行并验证数据源与租户上下文透传
     *
     * @return 异步执行结果记录（格式：dsKey:tenantId）
     */
    public CompletableFuture<String> executeAsyncTaskWithContext() {
        String currentDs = dynamicDatasourceTemplate.getCurrentDataSourceKey();
        String currentTenant = TenantContextHolder.getTenantId();
        log.info("[AsyncContextScenario] 主线程准备异步任务, 当前 dsKey: {}, tenantId: {}", currentDs, currentTenant);

        // 通过 AsyncContext.wrapSupplier 包装并透传当前上下文至受管线程池
        return CompletableFuture.supplyAsync(AsyncContext.wrapSupplier(() -> {
            String subDs = DynamicDataSourceContextHolder.peek();
            String subTenant = TenantContextHolder.getTenantId();
            log.info("[AsyncContextScenario] 异步子线程执行, 透传 dsKey: {}, tenantId: {}", subDs, subTenant);
            return (subDs != null ? subDs : "NONE") + ":" + (subTenant != null ? subTenant : "NONE");
        }), taskExecutor);
    }

    /**
     * 在受管子线程中验证五维异步上下文（TraceId / UserId / TenantId / IgnoreTenant / DataSourceKey）全链路透传
     *
     * @param request 五维上下文透传请求
     * @return 子线程实际观测到的五维上下文视图
     */
    public AsyncContextPropagateResponse propagateFiveDimensionalContext(AsyncContextPropagateRequest request) {
        String callerThreadName = Thread.currentThread().getName();
        String previousTraceId = MDC.get("traceId");
        boolean overrideTraceId = StringUtils.hasText(request.getTraceId());
        if (overrideTraceId) {
            MDC.put("traceId", request.getTraceId());
        }
        try {
            CompletableFuture<AsyncContextPropagateResponse> future = UserContextHolder.runWithUser(request.getUserId(), () ->
                    TenantContextHolder.runWithTenant(request.getTenantId(), () -> {
                        if (request.isIgnoreTenant()) {
                            return TenantContextHolder.runWithoutTenant(() ->
                                    submitFiveDimensionalTask(callerThreadName, request.getDsKey()));
                        }
                        return submitFiveDimensionalTask(callerThreadName, request.getDsKey());
                    }));
            return awaitPropagationResult(future);
        } finally {
            if (overrideTraceId) {
                if (StringUtils.hasText(previousTraceId)) {
                    MDC.put("traceId", previousTraceId);
                } else {
                    MDC.remove("traceId");
                }
            }
        }
    }

    private CompletableFuture<AsyncContextPropagateResponse> submitFiveDimensionalTask(String callerThreadName, String dsKey) {
        return dynamicDatasourceTemplate.callInDataSource(dsKey, () ->
                CompletableFuture.supplyAsync(AsyncContext.wrapSupplier(() -> {
                    String workerThreadName = Thread.currentThread().getName();
                    return AsyncContextPropagateResponse.builder()
                            .callerThreadName(callerThreadName)
                            .workerThreadName(workerThreadName)
                            .traceId(MDC.get("traceId"))
                            .userId(UserContextHolder.getUserId())
                            .tenantId(TenantContextHolder.getTenantId())
                            .ignoreTenant(TenantContextHolder.isIgnoreTenant())
                            .dsKey(DynamicDataSourceContextHolder.peek())
                            .crossThreadVerified(!callerThreadName.equals(workerThreadName))
                            .build();
                }), taskExecutor));
    }

    private AsyncContextPropagateResponse awaitPropagationResult(CompletableFuture<AsyncContextPropagateResponse> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(CommonResultCode.SERVICE_UNAVAILABLE, "五维异步上下文透传等待被中断", e);
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                    "五维异步上下文透传执行异常: " + e.getMessage(), e);
        }
    }
}

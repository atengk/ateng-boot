package io.github.atengk.boot.sample.datasource.service;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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
}

package io.github.atengk.boot.sample.datasource.service;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 异步上下文透传场景服务
 * <p>
 * 演示在多线程或异步调度时，通过 AsyncContext 将租户与数据源上下文透明透传至子线程。
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceAsyncContextScenarioService {

    private final DynamicDatasourceTemplate dynamicDatasourceTemplate;

    /**
     * 在子线程中执行并验证数据源与租户上下文透传
     *
     * @return 异步执行结果记录（格式：[dsKey:..., tenantId:...]）
     */
    public CompletableFuture<String> executeAsyncTaskWithContext() {
        String currentDs = dynamicDatasourceTemplate.getCurrentDataSourceKey();
        String currentTenant = TenantContextHolder.getTenantId();
        log.info("[AsyncContextScenario] 主线程准备异步任务, 当前 dsKey: {}, tenantId: {}", currentDs, currentTenant);

        // 使用单线程池模拟后台异步调度
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // 通过 AsyncContext.wrap 包装并透传当前上下文
            Runnable task = AsyncContext.wrap(() -> {
                String subDs = DynamicDataSourceContextHolder.peek();
                String subTenant = TenantContextHolder.getTenantId();
                log.info("[AsyncContextScenario] 异步子线程执行, 透传 dsKey: {}, tenantId: {}", subDs, subTenant);
            });

            return CompletableFuture.supplyAsync(() -> {
                task.run();
                return "SUCCESS";
            }, executor);
        } finally {
            executor.shutdown();
        }
    }
}

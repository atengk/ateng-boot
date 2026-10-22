package io.github.atengk.boot.sample.core.service;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Core 场景：多业务受管异步线程池与舱壁隔离服务
 * 演示使用 @Async("export") 路由至专有 export 业务线程池执行耗时报表导出，
 * 实现与在线核心任务的舱壁隔离（Bulkhead Isolation），并透明穿透四维上下文
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Service
public class CoreReportExportScenarioService {

    /**
     * 调度 export 专属线程池异步生成报表，并验证四维上下文流转与执行线程名称
     *
     * @param reportName 报表名称
     * @return 包含执行线程名、报表名与抓取到的上下文镜像
     */
    @Async("export")
    public CompletableFuture<Map<String, String>> exportReportAsync(String reportName) {
        String currentThread = Thread.currentThread().getName();
        String traceId = MDC.get("traceId");
        String userId = UserContextHolder.getUserId();
        String tenantId = TenantContextHolder.getTenantId();
        boolean ignoreTenant = TenantContextHolder.isIgnoreTenant();

        log.info("专属 export 线程池异步导出报表 [{}], 工作线程: {}, 上下文: traceId={}, userId={}, tenantId={}, ignoreTenant={}",
                reportName, currentThread, traceId, userId, tenantId, ignoreTenant);

        return CompletableFuture.completedFuture(Map.of(
                "threadName", currentThread,
                "reportName", reportName,
                "traceId", traceId != null ? traceId : "",
                "userId", userId != null ? userId : "",
                "tenantId", tenantId != null ? tenantId : "",
                "ignoreTenant", String.valueOf(ignoreTenant)
        ));
    }
}

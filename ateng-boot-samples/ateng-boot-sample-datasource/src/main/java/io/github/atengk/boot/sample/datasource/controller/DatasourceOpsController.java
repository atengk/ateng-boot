package io.github.atengk.boot.sample.datasource.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.datasource.loader.SyncResult;
import io.github.atengk.boot.datasource.model.DataSourcePoolStats;
import io.github.atengk.boot.sample.datasource.service.DatasourceDynamicLoaderScenarioService;
import io.github.atengk.boot.sample.datasource.service.DatasourceMetricsScenarioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 动态多数据源运维度量与差量同步控制器
 * <p>
 * 提供实时物理连接池 Micrometer 运行快照查询、外部配置三向差量同步与核心白名单校验。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@RestController
@RequestMapping("/sample/datasource/ops")
@RequiredArgsConstructor
public class DatasourceOpsController {

    private final DatasourceMetricsScenarioService metricsScenarioService;
    private final DatasourceDynamicLoaderScenarioService dynamicLoaderScenarioService;

    /**
     * 1. 查询所有已注册物理数据源连接池的实时运行指标快照 (Active/Idle/Total/Awaiting)
     *
     * @return 各数据源状态快照字典
     */
    @GetMapping("/pool-stats")
    public Result<Map<String, DataSourcePoolStats>> getAllPoolStats() {
        Map<String, DataSourcePoolStats> statsMap = metricsScenarioService.getAllPoolStats();
        return Result.success(statsMap);
    }

    /**
     * 2. 查询指定物理数据源的连接池运行快照
     *
     * @param dsKey 数据源标识 (如 master, slave)
     * @return 该连接池运行指标快照
     */
    @GetMapping("/pool-stats/{dsKey}")
    public Result<DataSourcePoolStats> getPoolStats(@PathVariable String dsKey) {
        DataSourcePoolStats stats = metricsScenarioService.getPoolStats(dsKey);
        return Result.success(stats);
    }

    /**
     * 3. 手动触发外部动态数据源智能三向差量同步 (Smart Diff)
     *
     * @return 差量同步结果模型 (包含 added, updated, removed 数据源列表)
     */
    @PostMapping("/sync-external")
    public Result<SyncResult> triggerExternalSync() {
        SyncResult result = dynamicLoaderScenarioService.triggerSync();
        log.info("[OpsController] 外部数据源差量同步触发完成: {}", result);
        return Result.success(result);
    }

    /**
     * 4. 校验指定数据源是否受核心白名单保护 (防误删防排空)
     *
     * @param dsKey 数据源标识
     * @return true 表示受保护
     */
    @GetMapping("/core-whitelist/check")
    public Result<Boolean> checkCoreWhitelist(@RequestParam String dsKey) {
        boolean protectedKey = dynamicLoaderScenarioService.isCoreProtected(dsKey);
        return Result.success(protectedKey);
    }
}

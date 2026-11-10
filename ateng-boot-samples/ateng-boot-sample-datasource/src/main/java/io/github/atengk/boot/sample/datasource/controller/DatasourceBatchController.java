package io.github.atengk.boot.sample.datasource.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.datasource.enums.BatchExecutionPolicy;
import io.github.atengk.boot.sample.datasource.service.DatasourceBatchScenarioService;
import io.github.atengk.boot.sample.datasource.vo.BatchInspectionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 编程式多数据源批量巡检与连接池并发预热控制器
 * <p>
 * 提供全物理库容错巡检统计、全租户数据遍历以及连接池并发预热平滑挂载。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@RestController
@RequestMapping("/sample/datasource/batch")
@RequiredArgsConstructor
public class DatasourceBatchController {

    private final DatasourceBatchScenarioService batchScenarioService;

    /**
     * 1. 全物理数据源容错批量巡检与订单统计
     *
     * @param policy 批处理容错策略 (FAIL_SAFE: 单库失败隔离继续其余库; FAIL_FAST: 遇异常即中断; 默认 FAIL_SAFE)
     * @return 强类型巡检汇总展示结果
     */
    @PostMapping("/inspect-all")
    public Result<BatchInspectionResponse> inspectAllDataSources(
            @RequestParam(defaultValue = "FAIL_SAFE") BatchExecutionPolicy policy) {
        log.info("[BatchController] 触发全库批量巡检: policy={}", policy);
        BatchInspectionResponse response = batchScenarioService.inspectAllDataSources(policy);
        return Result.success(response);
    }

    /**
     * 2. 全租户数据遍历巡检与订单统计
     *
     * @return 强类型租户巡检汇总展示结果
     */
    @PostMapping("/inspect-tenants")
    public Result<BatchInspectionResponse> inspectAllTenants() {
        log.info("[BatchController] 触发全租户遍历巡检");
        BatchInspectionResponse response = batchScenarioService.inspectAllTenants();
        return Result.success(response);
    }

    /**
     * 3. 编程式连接池并发预热
     *
     * @param dsKey       目标物理数据源标识
     * @param connections 预热物理连接数 (默认 5)
     * @return 实际建立并验证放回连接池的连接数
     */
    @PostMapping("/pre-warm")
    public Result<Integer> preWarmDataSource(@RequestParam String dsKey,
                                             @RequestParam(defaultValue = "5") int connections) {
        log.info("[BatchController] 触发连接池并发预热: dsKey={}, connections={}", dsKey, connections);
        int actual = batchScenarioService.preWarmDataSource(dsKey, connections);
        return Result.success(actual);
    }
}

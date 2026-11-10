package io.github.atengk.boot.sample.datasource.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.datasource.enums.BatchExecutionPolicy;
import io.github.atengk.boot.datasource.template.BatchExecutionResult;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.sample.datasource.entity.OrderEntity;
import io.github.atengk.boot.sample.datasource.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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

    private final DynamicDatasourceTemplate dynamicDatasourceTemplate;
    private final OrderMapper orderMapper;

    /**
     * 1. 全物理数据源容错批量巡检与订单统计
     *
     * @param policy 批处理容错策略 (FAIL_SAFE: 单库失败隔离继续其余库; FAIL_FAST: 遇异常即中断; 默认 FAIL_SAFE)
     * @return 巡检汇总结果
     */
    @PostMapping("/inspect-all")
    public Result<Map<String, Object>> inspectAllDataSources(@RequestParam(defaultValue = "FAIL_SAFE") String policy) {
        BatchExecutionPolicy execPolicy = BatchExecutionPolicy.valueOf(policy.trim().toUpperCase());
        ConcurrentMap<String, Long> countMap = new ConcurrentHashMap<>();

        BatchExecutionResult result = dynamicDatasourceTemplate.forEachDataSource(execPolicy, dsKey -> {
            Long count = orderMapper.selectCount(new LambdaQueryWrapper<OrderEntity>());
            countMap.put(dsKey, count != null ? count : 0L);
        });

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("allSuccess", result.isAllSuccess());
        response.put("totalCount", result.getTotalCount());
        response.put("successCount", result.getSuccessCount());
        response.put("failureCount", result.getFailureCount());
        response.put("counts", countMap);
        response.put("failures", result.getFailures());

        return Result.success(response);
    }

    /**
     * 2. 全租户数据遍历巡检与订单统计
     *
     * @return 租户巡检汇总结果
     */
    @PostMapping("/inspect-tenants")
    public Result<Map<String, Object>> inspectAllTenants() {
        ConcurrentMap<String, Long> countMap = new ConcurrentHashMap<>();

        BatchExecutionResult result = dynamicDatasourceTemplate.forEachTenant((tenantId, dsKey) -> {
            Long count = orderMapper.selectCount(new LambdaQueryWrapper<OrderEntity>());
            countMap.put(tenantId, count != null ? count : 0L);
        });

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("allSuccess", result.isAllSuccess());
        response.put("totalCount", result.getTotalCount());
        response.put("successCount", result.getSuccessCount());
        response.put("failureCount", result.getFailureCount());
        response.put("tenantCounts", countMap);
        response.put("failures", result.getFailures());

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
        int actual = dynamicDatasourceTemplate.preWarmDataSource(dsKey, connections);
        log.info("[BatchController] 数据源 [{}] 成功预热 {} 个物理连接", dsKey, actual);
        return Result.success(actual);
    }
}

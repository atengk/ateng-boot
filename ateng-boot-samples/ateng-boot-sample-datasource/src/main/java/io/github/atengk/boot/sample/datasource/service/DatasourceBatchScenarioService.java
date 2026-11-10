package io.github.atengk.boot.sample.datasource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.atengk.boot.datasource.enums.BatchExecutionPolicy;
import io.github.atengk.boot.datasource.template.BatchExecutionResult;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.sample.datasource.entity.OrderEntity;
import io.github.atengk.boot.sample.datasource.mapper.OrderMapper;
import io.github.atengk.boot.sample.datasource.vo.BatchInspectionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 动态多数据源批量容错巡检场景服务
 * <p>
 * 演示使用 DynamicDatasourceTemplate 对全库与各租户执行全自动容错批处理。
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceBatchScenarioService {

    private final DynamicDatasourceTemplate dynamicDatasourceTemplate;
    private final OrderMapper orderMapper;

    /**
     * 全物理数据源容错批量巡检与订单统计
     *
     * @param policy 批处理容错策略
     * @return 巡检结构化响应对象
     */
    public BatchInspectionResponse inspectAllDataSources(BatchExecutionPolicy policy) {
        log.info("[BatchScenario] 开始全库巡检订单总数 (策略: {})", policy);
        ConcurrentMap<String, Long> countMap = new ConcurrentHashMap<>();

        BatchExecutionResult result = dynamicDatasourceTemplate.forEachDataSource(policy, dsKey -> {
            Long count = orderMapper.selectCount(new LambdaQueryWrapper<OrderEntity>());
            countMap.put(dsKey, count != null ? count : 0L);
        });

        return BatchInspectionResponse.builder()
                .allSuccess(result.isAllSuccess())
                .totalCount(result.getTotalCount())
                .successCount(result.getSuccessCount())
                .failureCount(result.getFailureCount())
                .counts(countMap)
                .failures(result.getFailures())
                .build();
    }

    /**
     * 全租户数据遍历巡检与订单统计
     *
     * @return 租户巡检结构化响应对象
     */
    public BatchInspectionResponse inspectAllTenants() {
        log.info("[BatchScenario] 开始遍历所有租户库巡检订单总数");
        ConcurrentMap<String, Long> countMap = new ConcurrentHashMap<>();

        BatchExecutionResult result = dynamicDatasourceTemplate.forEachTenant((tenantId, dsKey) -> {
            Long count = orderMapper.selectCount(new LambdaQueryWrapper<OrderEntity>());
            countMap.put(tenantId, count != null ? count : 0L);
        });

        return BatchInspectionResponse.builder()
                .allSuccess(result.isAllSuccess())
                .totalCount(result.getTotalCount())
                .successCount(result.getSuccessCount())
                .failureCount(result.getFailureCount())
                .counts(countMap)
                .failures(result.getFailures())
                .build();
    }

    /**
     * 编程式连接池并发预热
     *
     * @param dsKey       目标物理数据源标识
     * @param connections 预热物理连接数
     * @return 实际建立并验证放回连接池的连接数
     */
    public int preWarmDataSource(String dsKey, int connections) {
        int actual = dynamicDatasourceTemplate.preWarmDataSource(dsKey, connections);
        log.info("[BatchScenario] 数据源 [{}] 成功预热 {} 个物理连接", dsKey, actual);
        return actual;
    }

    /**
     * 批量遍历所有已注册数据源并统计订单总数
     *
     * @param countMap 统计映射
     * @return 批处理执行结果
     */
    public BatchExecutionResult countOrdersPerDataSource(ConcurrentMap<String, Long> countMap) {
        log.info("[BatchScenario] 开始全库巡检订单总数");
        return dynamicDatasourceTemplate.forEachDataSource(dsKey -> {
            Long count = orderMapper.selectCount(new LambdaQueryWrapper<>());
            log.info("[BatchScenario] 数据源 [{}] 当前订单数: {}", dsKey, count);
            countMap.put(dsKey, count);
        });
    }

    /**
     * 批量遍历所有租户数据源并统计订单总数
     *
     * @param countMap 统计映射
     * @return 租户批处理执行结果
     */
    public BatchExecutionResult countOrdersPerTenant(ConcurrentMap<String, Long> countMap) {
        log.info("[BatchScenario] 开始遍历所有租户库巡检订单总数");
        return dynamicDatasourceTemplate.forEachTenant((tenantId, dsKey) -> {
            Long count = orderMapper.selectCount(new LambdaQueryWrapper<>());
            log.info("[BatchScenario] 租户 [{}] (数据源: {}) 当前订单数: {}", tenantId, dsKey, count);
            countMap.put(tenantId, count);
        });
    }

    /**
     * 编程式在指定租户执行业务操作
     *
     * @param tenantId 租户标识
     * @return 订单总数
     */
    public Long countInTenant(String tenantId) {
        return dynamicDatasourceTemplate.callInTenantDataSource(tenantId, () ->
                orderMapper.selectCount(new LambdaQueryWrapper<>())
        );
    }
}

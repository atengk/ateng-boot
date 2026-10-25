package io.github.atengk.boot.sample.datasource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.atengk.boot.datasource.template.BatchExecutionResult;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.sample.datasource.entity.OrderEntity;
import io.github.atengk.boot.sample.datasource.mapper.OrderMapper;
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
     * 批量遍历所有已注册数据源并统计订单总数
     *
     * @return 批处理执行结果与统计映射
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
     * @return 租户批处理执行结果与统计映射
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

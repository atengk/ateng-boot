package io.github.atengk.boot.sample.datasource.service;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import io.github.atengk.boot.datasource.model.DataSourcePoolStats;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 连接池预热与冷启动防抖动场景服务
 * <p>
 * 演示动态数据源挂载后的物理连接并发预热与冷启动零抖动平滑挂载。
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourcePreWarmScenarioService {

    private final DynamicDatasourceTemplate dynamicDatasourceTemplate;

    /**
     * 动态挂载数据源并执行连接池并发预热
     *
     * @param dsKey       数据源键名
     * @param jdbcUrl     JDBC 连接串
     * @param targetCount 目标预热连接数
     * @return 实际成功预热的物理连接数
     */
    public int registerAndPreWarm(String dsKey, String jdbcUrl, int targetCount) {
        log.info("[PreWarmScenario] 动态挂载并预热数据源 [dsKey={}, targetCount={}]", dsKey, targetCount);
        DataSourceProperty property = new DataSourceProperty();
        property.setPoolName(dsKey);
        property.setUrl(jdbcUrl);
        property.setUsername("sa");
        property.setPassword("");
        property.setDriverClassName("org.h2.Driver");

        // 挂载数据源（此处显式 preWarm=false，随后通过门面手动触发预热以获取建立连接数）
        dynamicDatasourceTemplate.addDataSource(dsKey, property, false);
        int warmed = dynamicDatasourceTemplate.preWarmDataSource(dsKey, targetCount);
        log.info("[PreWarmScenario] 数据源 [{}] 成功预热 {} 个物理连接", dsKey, warmed);
        return warmed;
    }

    /**
     * 查询预热后数据源的实时连接池状态快照
     *
     * @param dsKey 数据源键名
     * @return 连接池状态快照
     */
    public DataSourcePoolStats inspectPoolStats(String dsKey) {
        return dynamicDatasourceTemplate.getPoolStats(dsKey);
    }

    /**
     * 注销并释放预热数据源
     *
     * @param dsKey 数据源键名
     */
    public void unregister(String dsKey) {
        log.info("[PreWarmScenario] 注销预热数据源: {}", dsKey);
        dynamicDatasourceTemplate.removeDataSource(dsKey, false);
    }
}

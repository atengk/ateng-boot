package io.github.atengk.boot.sample.datasource.service;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.sample.datasource.config.DatasourceSampleInitConfig;
import io.github.atengk.boot.sample.datasource.dto.DataSourceReloadRequest;
import io.github.atengk.boot.sample.datasource.entity.OrderEntity;
import io.github.atengk.boot.sample.datasource.mapper.OrderMapper;
import io.github.atengk.boot.sample.datasource.vo.DataSourceReloadResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 动态数据源生命周期场景服务
 * <p>
 * 演示运行时动态挂载新数据源与优雅排空注销。
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceLifecycleScenarioService {

    private final DynamicDatasourceTemplate dynamicDatasourceTemplate;
    private final DatasourceSampleInitConfig datasourceSampleInitConfig;
    private final OrderMapper orderMapper;

    /**
     * 运行时动态挂载新数据源
     *
     * @param dsKey    数据源键名
     * @param jdbcUrl  JDBC 连接地址
     * @param username 用户名
     * @param password 密码
     */
    public void registerDynamicDataSource(String dsKey, String jdbcUrl, String username, String password) {
        log.info("[LifecycleScenario] 正在动态注册新数据源: {}", dsKey);
        DataSourceProperty property = new DataSourceProperty();
        property.setPoolName(dsKey);
        property.setUrl(jdbcUrl);
        property.setUsername(username);
        property.setPassword(password);
        property.setDriverClassName("org.h2.Driver");

        dynamicDatasourceTemplate.addDataSource(dsKey, property);
        datasourceSampleInitConfig.initTableForDataSource(dsKey);
        log.info("[LifecycleScenario] 动态数据源 [{}] 注册并初始化完成", dsKey);
    }

    /**
     * 在动态挂载的数据源上执行业务操作
     *
     * @param dsKey   数据源键名
     * @param orderNo 订单号
     */
    public void createOrderInDynamicDataSource(String dsKey, String orderNo) {
        dynamicDatasourceTemplate.runInDataSource(dsKey, () -> {
            OrderEntity entity = new OrderEntity();
            entity.setOrderNo(orderNo);
            entity.setProductName("Dynamic Product");
            entity.setAmount(new BigDecimal("99.99"));
            entity.setStatus("DYNAMIC_CREATED");
            orderMapper.insert(entity);
            log.info("[LifecycleScenario] 成功在数据源 [{}] 创建订单: {}", dsKey, orderNo);
        });
    }

    /**
     * 无损热重载动态数据源（连通性预检、并发预热、原子切流与旧连接池超时优雅排空）
     *
     * @param request 热重载配置请求
     * @return 热重载结果展示视图
     */
    public DataSourceReloadResponse reloadDynamicDataSource(DataSourceReloadRequest request) {
        log.info("[LifecycleScenario] 开始无损热重载数据源 [dsKey={}, preWarm={}, graceful={}]",
                request.getDsKey(), request.isPreWarm(), request.isGraceful());
        DataSourceProperty property = new DataSourceProperty();
        property.setPoolName(request.getDsKey());
        property.setUrl(request.getUrl());
        property.setUsername(request.getUsername());
        property.setPassword(request.getPassword());
        property.setDriverClassName(request.getDriverClassName());

        // 1. 执行受管热重载：先建池、校验连通性并按指定连接数并发预热，再原子切换路由并按需优雅排空旧池
        int targetWarmCount = request.getPreWarmConnections();
        dynamicDatasourceTemplate.reloadDataSource(
                request.getDsKey(), property, request.isPreWarm(), targetWarmCount, request.isGraceful());

        // 2. 为新物理连接池补齐演示表结构，确保热重载后业务写入无缝衔接
        datasourceSampleInitConfig.initTableForDataSource(request.getDsKey());

        boolean active = dynamicDatasourceTemplate.containsDataSource(request.getDsKey());
        int warmedConnections = request.isPreWarm() ? targetWarmCount : 0;
        return DataSourceReloadResponse.builder()
                .dsKey(request.getDsKey())
                .preWarmed(request.isPreWarm())
                .warmedConnections(warmedConnections)
                .gracefulDrained(request.isGraceful())
                .active(active)
                .message("Datasource [" + request.getDsKey() + "] hot-reloaded successfully")
                .build();
    }

    /**
     * 优雅排空并注销数据源
     *
     * @param dsKey 数据源键名
     * @param drain 是否等待在途事务连接排空
     */
    public void unregisterDynamicDataSource(String dsKey, boolean drain) {
        log.info("[LifecycleScenario] 正在优雅排空并注销数据源: {}, drain={}", dsKey, drain);
        dynamicDatasourceTemplate.removeDataSource(dsKey, drain);
        log.info("[LifecycleScenario] 数据源 [{}] 已注销", dsKey);
    }
}

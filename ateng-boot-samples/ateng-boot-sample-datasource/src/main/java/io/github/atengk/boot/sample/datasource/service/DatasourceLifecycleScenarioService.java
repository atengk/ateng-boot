package io.github.atengk.boot.sample.datasource.service;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.sample.datasource.config.DatasourceSampleInitConfig;
import io.github.atengk.boot.sample.datasource.entity.OrderEntity;
import io.github.atengk.boot.sample.datasource.mapper.OrderMapper;
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

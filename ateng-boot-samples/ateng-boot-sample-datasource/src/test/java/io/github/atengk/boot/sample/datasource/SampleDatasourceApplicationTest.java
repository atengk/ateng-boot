package io.github.atengk.boot.sample.datasource;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.sample.datasource.controller.OrderController;
import io.github.atengk.boot.sample.datasource.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 样例工程应用上下文加载自验测试
 *
 * @author Ateng
 * @since 2026-10-24
 */
@SpringBootTest
class SampleDatasourceApplicationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DynamicDatasourceTemplate dynamicDatasourceTemplate;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderController orderController;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("上下文加载自验: 核心组件与动态数据源Bean应全部成功注入")
    void contextLoads() {
        assertThat(dataSource).isNotNull();
        assertThat(dataSource).isInstanceOf(DynamicRoutingDataSource.class);
        assertThat(dynamicDatasourceTemplate).isNotNull();
        assertThat(orderService).isNotNull();
        assertThat(orderController).isNotNull();

        DynamicRoutingDataSource dynamicRoutingDataSource = (DynamicRoutingDataSource) dataSource;
        assertThat(dynamicRoutingDataSource.getDataSources()).containsKey("master");
        assertThat(dynamicRoutingDataSource.getDataSources()).containsKey("slave");
        assertThat(dynamicRoutingDataSource.getDataSources()).containsKey("tenant_1001");
        assertThat(dynamicRoutingDataSource.getDataSources()).containsKey("tenant_1002");
    }
}

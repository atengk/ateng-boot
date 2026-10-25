package io.github.atengk.boot.datasource.template;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import com.baomidou.dynamic.datasource.creator.DefaultDataSourceCreator;
import com.baomidou.dynamic.datasource.creator.hikaricp.HikariCpConfig;
import com.baomidou.dynamic.datasource.creator.hikaricp.HikariDataSourceCreator;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import io.github.atengk.boot.datasource.exception.DataSourceSwitchException;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 动态数据源受管生命周期（热添加、连通性探测与优雅排空）单测
 *
 * @author Ateng
 * @since 2026-10-24
 */
@DisplayName("动态数据源受管生命周期单测")
class DynamicDatasourceTemplateLifecycleTest {

    private AtengDynamicRoutingDataSource routingDataSource;
    private DynamicDatasourceTemplate template;
    private DefaultDataSourceCreator defaultDataSourceCreator;

    @BeforeEach
    void setUp() {
        DynamicDataSourceContextHolder.clear();

        // 构造基础 master 数据源
        JdbcDataSource masterDs = new JdbcDataSource();
        masterDs.setURL("jdbc:h2:mem:master_lifecycle;DB_CLOSE_DELAY=-1");
        masterDs.setUser("sa");
        masterDs.setPassword("");

        routingDataSource = new AtengDynamicRoutingDataSource(
                Collections.emptyList(),
                new DatasourceProperties(),
                null,
                null,
                null
        );
        routingDataSource.addDataSource("master", masterDs);
        routingDataSource.setPrimary("master");

        // 构造 DefaultDataSourceCreator
        defaultDataSourceCreator = new DefaultDataSourceCreator();
        HikariDataSourceCreator hikariCreator = new HikariDataSourceCreator(new HikariCpConfig());
        defaultDataSourceCreator.setCreators(List.of(hikariCreator));

        DatasourceProperties properties = new DatasourceProperties();
        properties.getDynamicLoad().setProbeTimeout(Duration.ofSeconds(1));
        properties.getDynamicLoad().setDrainTimeout(Duration.ofSeconds(2));

        template = new DynamicDatasourceTemplate(
                routingDataSource,
                properties,
                null,
                null,
                defaultDataSourceCreator
        );
    }

    @AfterEach
    void tearDown() {
        DynamicDataSourceContextHolder.clear();
        if (routingDataSource != null) {
            routingDataSource.destroy();
        }
    }

    @Test
    @DisplayName("测试动态热加载数据源成功并支持即时路由查询")
    void shouldHotAddDataSourceSuccessfully() {
        DataSourceProperty property = new DataSourceProperty();
        property.setPoolName("dynamic_order_ds");
        property.setUrl("jdbc:h2:mem:dynamic_order_db;DB_CLOSE_DELAY=-1");
        property.setUsername("sa");
        property.setPassword("");
        property.setDriverClassName("org.h2.Driver");

        // 动态添加数据源
        template.addDataSource("order_ds", property);

        assertThat(template.getDataSourceKeys()).contains("order_ds");

        // 在新数据源中执行建表与查询
        String result = template.callInDataSource("order_ds", () -> {
            DataSource current = routingDataSource.determineDataSource();
            try (Connection conn = current.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE t_demo (id INT PRIMARY KEY, name VARCHAR(32))");
                stmt.execute("INSERT INTO t_demo VALUES (1, 'ateng')");
                try (ResultSet rs = stmt.executeQuery("SELECT name FROM t_demo WHERE id = 1")) {
                    rs.next();
                    return rs.getString(1);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(result).isEqualTo("ateng");
    }

    @Test
    @DisplayName("测试连通性预检失败或配置损坏时快速失败阻断，且绝不污染注册表")
    void shouldFailFastWhenConnectivityProbeFails() {
        DataSourceProperty badProperty = new DataSourceProperty();
        badProperty.setPoolName("bad_ds");
        badProperty.setUrl("jdbc:h2:tcp://localhost:9999/bad_db");
        badProperty.setUsername("bad_user");
        badProperty.setPassword("bad_pass");
        badProperty.setDriverClassName("org.h2.Driver");

        assertThatThrownBy(() -> template.addDataSource("bad_ds", badProperty))
                .isInstanceOf(DataSourceSwitchException.class);

        // 验证损坏的数据源绝对未混入路由注册表
        assertThat(template.getDataSourceKeys()).doesNotContain("bad_ds");
    }

    @Test
    @DisplayName("测试连通性预检 isValid 为 false 时触发 Fail-Fast 并销毁未通过数据源")
    void shouldFailFastWhenConnectionIsValidReturnsFalse() throws Exception {
        DataSource mockDs = org.mockito.Mockito.mock(DataSource.class);
        Connection mockConn = org.mockito.Mockito.mock(Connection.class);
        org.mockito.Mockito.when(mockDs.getConnection()).thenReturn(mockConn);
        org.mockito.Mockito.when(mockConn.isValid(org.mockito.ArgumentMatchers.anyInt())).thenReturn(false);

        assertThatThrownBy(() -> template.addDataSource("unhealthy_ds", mockDs))
                .isInstanceOf(DataSourceSwitchException.class)
                .hasMessageContaining("连通性预检失败");

        assertThat(template.getDataSourceKeys()).doesNotContain("unhealthy_ds");
    }

    @Test
    @DisplayName("测试优雅注销数据源成功从路由表中摘除")
    void shouldRemoveDataSourceGracefully() {
        JdbcDataSource tempDs = new JdbcDataSource();
        tempDs.setURL("jdbc:h2:mem:temp_remove_ds;DB_CLOSE_DELAY=-1");
        tempDs.setUser("sa");
        tempDs.setPassword("");

        template.addDataSource("temp_ds", tempDs);
        assertThat(template.getDataSourceKeys()).contains("temp_ds");

        // 优雅注销
        template.removeDataSource("temp_ds", true);
        assertThat(template.getDataSourceKeys()).doesNotContain("temp_ds");
    }

    @Test
    @DisplayName("测试尝试注销受管主库时被刚性拦截阻断")
    void shouldRefuseRemovingMasterDataSource() {
        assertThatThrownBy(() -> template.removeDataSource("master", true))
                .isInstanceOf(DataSourceSwitchException.class)
                .hasMessageContaining("受管主数据源严禁动态注销");

        assertThat(template.getDataSourceKeys()).contains("master");
    }
}

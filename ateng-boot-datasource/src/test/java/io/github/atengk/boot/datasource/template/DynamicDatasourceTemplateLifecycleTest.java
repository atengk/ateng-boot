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

    @Test
    @DisplayName("测试单参便捷注销 removeDataSource(dsKey) 默认执行优雅排空并继续受核心白名单保护")
    void shouldSupportSingleArgRemoveDataSourceWithGracefulDrain() {
        JdbcDataSource tempDs = new JdbcDataSource();
        tempDs.setURL("jdbc:h2:mem:temp_single_arg_remove;DB_CLOSE_DELAY=-1");
        tempDs.setUser("sa");
        tempDs.setPassword("");

        template.addDataSource("temp_single_ds", tempDs);
        assertThat(template.getDataSourceKeys()).contains("temp_single_ds");

        template.removeDataSource("temp_single_ds");
        assertThat(template.getDataSourceKeys()).doesNotContain("temp_single_ds");

        assertThatThrownBy(() -> template.removeDataSource("master"))
                .isInstanceOf(DataSourceSwitchException.class)
                .hasMessageContaining("受管主数据源严禁动态注销");
    }

    @Test
    @DisplayName("测试注销数据源联动摘除全部分组残余引用并清除熔断黑名单与在途主动探活")
    void shouldClearBlacklistActiveProbeAndGroupReferencesOnRemoveDataSource() {
        DatasourceProperties failoverProps = new DatasourceProperties();
        failoverProps.getReadWriteSplit().setEnabled(true);
        failoverProps.getReadWriteSplit().getFailover().setEnabled(true);
        failoverProps.getReadWriteSplit().getFailover().setActiveProbeEnabled(true);
        failoverProps.getReadWriteSplit().getFailover().setProbeInitialInterval(Duration.ofSeconds(30));
        failoverProps.getDynamicLoad().setProbeTimeout(Duration.ofSeconds(1));
        failoverProps.getDynamicLoad().setDrainTimeout(Duration.ofSeconds(1));

        JdbcDataSource localMaster = new JdbcDataSource();
        localMaster.setURL("jdbc:h2:mem:master_remove_cleanup;DB_CLOSE_DELAY=-1");
        localMaster.setUser("sa");
        localMaster.setPassword("");

        AtengDynamicRoutingDataSource failoverRouting = new AtengDynamicRoutingDataSource(
                Collections.emptyList(), failoverProps, null, null, null);
        failoverRouting.addDataSource("master", localMaster);
        failoverRouting.setPrimary("master");

        DynamicDatasourceTemplate failoverTemplate = new DynamicDatasourceTemplate(
                failoverRouting, failoverProps, null, null, defaultDataSourceCreator);

        try {
            JdbcDataSource node1 = new JdbcDataSource();
            node1.setURL("jdbc:h2:mem:ro_node_1_cleanup;DB_CLOSE_DELAY=-1");
            node1.setUser("sa");
            node1.setPassword("");

            JdbcDataSource node2 = new JdbcDataSource();
            node2.setURL("jdbc:h2:mem:ro_node_2_cleanup;DB_CLOSE_DELAY=-1");
            node2.setUser("sa");
            node2.setPassword("");

            failoverTemplate.addDataSource("ro_node_1", node1);
            failoverTemplate.addDataSource("ro_node_2", node2);
            failoverTemplate.addGroupMember("slave", "ro_node_1");
            failoverTemplate.addGroupMember("slave", "ro_node_2");
            failoverTemplate.addGroupMember("analytics_ro", "ro_node_1");
            failoverTemplate.addGroupMember("analytics_ro", "ro_node_2");
            failoverTemplate.addGroupMember("single_ro", "ro_node_1");

            // 模拟节点进入黑名单并启动主动探活
            failoverRouting.getHealthAwareStrategy().recordFailure("ro_node_1");
            assertThat(failoverRouting.getHealthAwareStrategy().isBlacklisted("ro_node_1")).isTrue();
            assertThat(failoverRouting.getHealthAwareStrategy().isProbing("ro_node_1")).isTrue();

            // 注销数据源
            failoverTemplate.removeDataSource("ro_node_1");

            // 1. 路由表、黑名单与在途主动探活全部清理
            assertThat(failoverTemplate.getDataSourceKeys()).doesNotContain("ro_node_1");
            assertThat(failoverRouting.getHealthAwareStrategy().isBlacklisted("ro_node_1")).isFalse();
            assertThat(failoverRouting.getHealthAwareStrategy().isProbing("ro_node_1")).isFalse();

            // 2. 所有包含该物理节点的分组均已摘除残余引用，健康节点保留，空分组同步清理
            assertThat(failoverRouting.getGroupDataSources().get("slave").getDataSourceMap())
                    .doesNotContainKey("ro_node_1")
                    .containsKey("ro_node_2");
            assertThat(failoverRouting.getGroupDataSources().get("analytics_ro").getDataSourceMap())
                    .doesNotContainKey("ro_node_1")
                    .containsKey("ro_node_2");
            assertThat(failoverRouting.getGroupDataSources()).doesNotContainKey("single_ro");

            // 3. 分组路由依然正常可用且只命中健康节点
            assertThat(failoverRouting.resolveWithFailover("slave")).isSameAs(node2);
        } finally {
            failoverRouting.destroy();
        }
    }

    @Test
    @DisplayName("测试热重载成功后联动解除熔断黑名单、取消在途探活并刷新分组节点引用")
    void shouldUnblacklistAndRefreshGroupReferenceOnReloadDataSource() {
        DatasourceProperties failoverProps = new DatasourceProperties();
        failoverProps.getReadWriteSplit().setEnabled(true);
        failoverProps.getReadWriteSplit().getFailover().setEnabled(true);
        failoverProps.getReadWriteSplit().getFailover().setActiveProbeEnabled(true);
        failoverProps.getReadWriteSplit().getFailover().setProbeInitialInterval(Duration.ofSeconds(30));
        failoverProps.getDynamicLoad().setProbeTimeout(Duration.ofSeconds(1));
        failoverProps.getDynamicLoad().setDrainTimeout(Duration.ofSeconds(1));

        JdbcDataSource localMaster = new JdbcDataSource();
        localMaster.setURL("jdbc:h2:mem:master_reload_unblacklist;DB_CLOSE_DELAY=-1");
        localMaster.setUser("sa");
        localMaster.setPassword("");

        AtengDynamicRoutingDataSource failoverRouting = new AtengDynamicRoutingDataSource(
                Collections.emptyList(), failoverProps, null, null, null);
        failoverRouting.addDataSource("master", localMaster);
        failoverRouting.setPrimary("master");

        DynamicDatasourceTemplate failoverTemplate = new DynamicDatasourceTemplate(
                failoverRouting, failoverProps, null, null, defaultDataSourceCreator);

        try {
            DataSourceProperty v1Prop = new DataSourceProperty();
            v1Prop.setPoolName("ro_reload_v1");
            v1Prop.setUrl("jdbc:h2:mem:ro_reload_v1;DB_CLOSE_DELAY=-1");
            v1Prop.setUsername("sa");
            v1Prop.setPassword("");
            v1Prop.setDriverClassName("org.h2.Driver");

            failoverTemplate.addDataSource("ro_reload_node", v1Prop);
            failoverTemplate.addGroupMember("slave", "ro_reload_node");
            DataSource oldDs = failoverRouting.getDataSources().get("ro_reload_node");

            // 置入黑名单并开启主动探活
            failoverRouting.getHealthAwareStrategy().recordFailure("ro_reload_node");
            assertThat(failoverRouting.getHealthAwareStrategy().isBlacklisted("ro_reload_node")).isTrue();
            assertThat(failoverRouting.getHealthAwareStrategy().isProbing("ro_reload_node")).isTrue();

            // 失败的热重载绝不提前解封黑名单
            DataSourceProperty badProp = new DataSourceProperty();
            badProp.setPoolName("ro_reload_bad");
            badProp.setUrl("jdbc:h2:tcp://127.0.0.1:19999/unreachable_reload");
            badProp.setUsername("sa");
            badProp.setPassword("");
            badProp.setDriverClassName("org.h2.Driver");

            assertThatThrownBy(() -> failoverTemplate.reloadDataSource("ro_reload_node", badProp))
                    .isInstanceOf(DataSourceSwitchException.class);
            assertThat(failoverRouting.getHealthAwareStrategy().isBlacklisted("ro_reload_node")).isTrue();

            // 健康新配置热重载成功
            DataSourceProperty v2Prop = new DataSourceProperty();
            v2Prop.setPoolName("ro_reload_v2");
            v2Prop.setUrl("jdbc:h2:mem:ro_reload_v2;DB_CLOSE_DELAY=-1");
            v2Prop.setUsername("sa");
            v2Prop.setPassword("");
            v2Prop.setDriverClassName("org.h2.Driver");

            failoverTemplate.reloadDataSource("ro_reload_node", v2Prop);

            // 1. 自动解封黑名单并取消在途探活
            assertThat(failoverRouting.getHealthAwareStrategy().isBlacklisted("ro_reload_node")).isFalse();
            assertThat(failoverRouting.getHealthAwareStrategy().isProbing("ro_reload_node")).isFalse();

            // 2. 单库注册表与分组节点引用均同步指向新连接池
            DataSource newDs = failoverRouting.getDataSources().get("ro_reload_node");
            assertThat(newDs).isNotNull().isNotSameAs(oldDs);
            assertThat(failoverRouting.getGroupDataSources().get("slave").getDataSourceMap().get("ro_reload_node"))
                    .isSameAs(newDs);

            // 3. 分组路由即刻放行至健康新池，不再降级主库
            assertThat(failoverRouting.resolveWithFailover("slave")).isSameAs(newDs);
        } finally {
            failoverRouting.destroy();
        }
    }
}

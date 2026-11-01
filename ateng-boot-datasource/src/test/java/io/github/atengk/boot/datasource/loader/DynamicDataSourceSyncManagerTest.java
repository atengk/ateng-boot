package io.github.atengk.boot.datasource.loader;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import com.baomidou.dynamic.datasource.creator.DefaultDataSourceCreator;
import com.baomidou.dynamic.datasource.creator.hikaricp.HikariCpConfig;
import com.baomidou.dynamic.datasource.creator.hikaricp.HikariDataSourceCreator;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 外部动态数据源智能三向差量同步与核心白名单保护单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
@DisplayName("外部数据源动态加载器与智能三向差量同步测试")
class DynamicDataSourceSyncManagerTest {

    private AtengDynamicRoutingDataSource routingDataSource;
    private DynamicDatasourceTemplate template;
    private DatasourceProperties properties;
    private DefaultDataSourceCreator defaultDataSourceCreator;

    @BeforeEach
    void setUp() {
        DynamicDataSourceContextHolder.clear();

        // 构造基础 master 数据源
        JdbcDataSource masterDs = new JdbcDataSource();
        masterDs.setURL("jdbc:h2:mem:master_sync_test;DB_CLOSE_DELAY=-1");
        masterDs.setUser("sa");
        masterDs.setPassword("");

        properties = new DatasourceProperties();
        properties.getDynamicLoad().setProbeTimeout(Duration.ofSeconds(1));
        properties.getDynamicLoad().setDrainTimeout(Duration.ofSeconds(1));

        routingDataSource = new AtengDynamicRoutingDataSource(
                Collections.emptyList(),
                properties,
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
    @DisplayName("测试智能三向差量同步：新增数据源平滑挂载")
    void shouldAddDiscoveredDataSources() {
        Map<String, DataSourceProperty> externalMap = new LinkedHashMap<>();
        DataSourceProperty p1 = createProperty("tenant_a", "jdbc:h2:mem:tenant_a;DB_CLOSE_DELAY=-1");
        DataSourceProperty p2 = createProperty("tenant_b", "jdbc:h2:mem:tenant_b;DB_CLOSE_DELAY=-1");
        externalMap.put("tenant_a", p1);
        externalMap.put("tenant_b", p2);

        DynamicDataSourceLoader loader = () -> externalMap;
        DynamicDataSourceSyncManager syncManager = new DynamicDataSourceSyncManager(template, properties, loader);

        SyncResult result = syncManager.sync();

        assertThat(result.getAddedCount()).isEqualTo(2);
        assertThat(result.getAddedKeys()).containsExactlyInAnyOrder("tenant_a", "tenant_b");
        assertThat(result.getRemovedCount()).isZero();
        assertThat(result.getReloadedCount()).isZero();
        assertThat(template.containsDataSource("tenant_a")).isTrue();
        assertThat(template.containsDataSource("tenant_b")).isTrue();

        syncManager.destroy();
    }

    @Test
    @DisplayName("测试智能三向差量同步：缺失数据源优雅排空并安全注销")
    void shouldGracefullyRemoveAbsentDataSources() {
        Map<String, DataSourceProperty> externalMap = new LinkedHashMap<>();
        DataSourceProperty p1 = createProperty("tenant_a", "jdbc:h2:mem:tenant_a;DB_CLOSE_DELAY=-1");
        DataSourceProperty p2 = createProperty("tenant_b", "jdbc:h2:mem:tenant_b;DB_CLOSE_DELAY=-1");
        externalMap.put("tenant_a", p1);
        externalMap.put("tenant_b", p2);

        DynamicDataSourceLoader loader = () -> externalMap;
        DynamicDataSourceSyncManager syncManager = new DynamicDataSourceSyncManager(template, properties, loader);

        // 首次同步：新增 a 与 b
        syncManager.sync();
        assertThat(template.containsDataSource("tenant_a")).isTrue();
        assertThat(template.containsDataSource("tenant_b")).isTrue();

        // 第二次同步：外部移除 b，仅保留 a
        externalMap.remove("tenant_b");
        SyncResult result = syncManager.sync();

        assertThat(result.getRemovedCount()).isEqualTo(1);
        assertThat(result.getRemovedKeys()).containsExactly("tenant_b");
        assertThat(result.getAddedCount()).isZero();
        assertThat(template.containsDataSource("tenant_a")).isTrue();
        assertThat(template.containsDataSource("tenant_b")).isFalse();

        syncManager.destroy();
    }

    @Test
    @DisplayName("测试智能三向差量同步：配置变更平滑热重载 (Hot Reload)")
    void shouldHotReloadModifiedDataSources() {
        Map<String, DataSourceProperty> externalMap = new LinkedHashMap<>();
        DataSourceProperty p1 = createProperty("tenant_a", "jdbc:h2:mem:tenant_a_v1;DB_CLOSE_DELAY=-1");
        externalMap.put("tenant_a", p1);

        DynamicDataSourceLoader loader = () -> externalMap;
        DynamicDataSourceSyncManager syncManager = new DynamicDataSourceSyncManager(template, properties, loader);

        // 首次同步挂载 v1
        syncManager.sync();
        template.callInDataSource("tenant_a", () -> {
            try (Connection conn = routingDataSource.determineDataSource().getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE t_v1 (id INT PRIMARY KEY)");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });

        // 变更配置为 v2 数据库
        DataSourceProperty p1Updated = createProperty("tenant_a", "jdbc:h2:mem:tenant_a_v2;DB_CLOSE_DELAY=-1");
        externalMap.put("tenant_a", p1Updated);

        SyncResult result = syncManager.sync();

        assertThat(result.getReloadedCount()).isEqualTo(1);
        assertThat(result.getReloadedKeys()).containsExactly("tenant_a");

        // 验证当前 tenant_a 已平滑切至 v2 数据库
        template.callInDataSource("tenant_a", () -> {
            try (Connection conn = routingDataSource.determineDataSource().getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE t_v2 (id INT PRIMARY KEY)");
                // 查询 t_v1 会因为在 v2 库中不存在而报错抛出异常，证明已成功热重载切换至新物理库
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> stmt.executeQuery("SELECT * FROM t_v1"))
                        .isInstanceOf(java.sql.SQLException.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });

        syncManager.destroy();
    }

    @Test
    @DisplayName("测试核心白名单防护：严禁外部同步覆盖或误删 master 与 slave")
    void shouldEnforceImmutablePrimaryProtectionForMasterAndSlave() {
        Map<String, DataSourceProperty> externalMap = new LinkedHashMap<>();
        // 外部尝试伪造 master 配置，或不包含 master
        DataSourceProperty maliciousMaster = createProperty("master", "jdbc:h2:mem:fake_master;DB_CLOSE_DELAY=-1");
        DataSourceProperty maliciousSlave = createProperty("slave", "jdbc:h2:mem:fake_slave;DB_CLOSE_DELAY=-1");
        DataSourceProperty validTenant = createProperty("tenant_ok", "jdbc:h2:mem:tenant_ok;DB_CLOSE_DELAY=-1");
        externalMap.put("master", maliciousMaster);
        externalMap.put("slave", maliciousSlave);
        externalMap.put("tenant_ok", validTenant);

        DynamicDataSourceLoader loader = () -> externalMap;
        DynamicDataSourceSyncManager syncManager = new DynamicDataSourceSyncManager(template, properties, loader);

        SyncResult result = syncManager.sync();

        // master 与 slave 命中核心白名单，拒绝热重载或篡改
        assertThat(result.getSkippedCount()).isGreaterThanOrEqualTo(2);
        assertThat(result.getSkippedKeys()).contains("master", "slave");
        assertThat(result.getAddedKeys()).containsExactly("tenant_ok");

        // master 依然保持原始连接池，不受污染
        assertThat(template.containsDataSource("master")).isTrue();

        // 下一次外部即使为空，master 也绝对不被删除
        externalMap.clear();
        SyncResult removeResult = syncManager.sync();
        assertThat(template.containsDataSource("master")).isTrue();
        assertThat(removeResult.getRemovedKeys()).containsExactly("tenant_ok");
        assertThat(removeResult.getRemovedKeys()).doesNotContain("master", "slave");

        syncManager.destroy();
    }

    @Test
    @DisplayName("测试周期性自动差量同步轮询执行")
    void shouldExecuteScheduledAutoSyncWhenEnabled() throws Exception {
        properties.getDynamicLoad().getAutoSync().setEnabled(true);
        properties.getDynamicLoad().getAutoSync().setInterval(Duration.ofMillis(200));

        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger callCount = new AtomicInteger(0);

        Map<String, DataSourceProperty> externalMap = new LinkedHashMap<>();
        DynamicDataSourceLoader loader = () -> {
            int count = callCount.incrementAndGet();
            if (count >= 2) {
                externalMap.put("tenant_auto", createProperty("tenant_auto", "jdbc:h2:mem:tenant_auto;DB_CLOSE_DELAY=-1"));
            }
            latch.countDown();
            return externalMap;
        };

        DynamicDataSourceSyncManager syncManager = new DynamicDataSourceSyncManager(template, properties, loader);

        boolean reached = latch.await(3, TimeUnit.SECONDS);
        assertThat(reached).isTrue();
        assertThat(callCount.get()).isGreaterThanOrEqualTo(2);

        // 等待异步后台调度线程完成 addDataSource 挂载与连通性探活
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && !template.containsDataSource("tenant_auto")) {
            Thread.sleep(50);
        }
        assertThat(template.containsDataSource("tenant_auto")).isTrue();

        syncManager.destroy();
    }

    @Test
    @DisplayName("测试 Template 门面层对 master 与 slave 核心白名单的直接刚性保护")
    void testTemplateDirectProtectionOnMasterAndSlave() {
        // 1. 直接通过 template.removeDataSource 试图删除 master 或 slave 必须被拦截抛出异常
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> template.removeDataSource("master", true))
                .isInstanceOf(io.github.atengk.boot.datasource.exception.DataSourceSwitchException.class)
                .hasMessageContaining("受管主数据源严禁动态注销");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> template.removeDataSource("slave", true))
                .isInstanceOf(io.github.atengk.boot.datasource.exception.DataSourceSwitchException.class)
                .hasMessageContaining("受管核心数据源严禁动态注销");

        // 2. 直接通过 template.reloadDataSource 试图热重载 master 或 slave 必须被拦截抛出异常
        DataSourceProperty prop = createProperty("fake", "jdbc:h2:mem:fake;DB_CLOSE_DELAY=-1");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> template.reloadDataSource("master", prop))
                .isInstanceOf(io.github.atengk.boot.datasource.exception.DataSourceSwitchException.class)
                .hasMessageContaining("受管主数据源严禁动态重载或替换");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> template.reloadDataSource("slave", prop))
                .isInstanceOf(io.github.atengk.boot.datasource.exception.DataSourceSwitchException.class)
                .hasMessageContaining("受管核心数据源严禁动态重载或替换");
    }

    private DataSourceProperty createProperty(String poolName, String url) {
        DataSourceProperty property = new DataSourceProperty();
        property.setPoolName(poolName);
        property.setUrl(url);
        property.setUsername("sa");
        property.setPassword("");
        property.setDriverClassName("org.h2.Driver");
        return property;
    }
}

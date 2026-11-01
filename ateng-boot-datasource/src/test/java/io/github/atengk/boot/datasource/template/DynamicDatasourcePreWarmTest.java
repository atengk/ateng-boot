package io.github.atengk.boot.datasource.template;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import com.baomidou.dynamic.datasource.creator.DefaultDataSourceCreator;
import com.baomidou.dynamic.datasource.creator.hikaricp.HikariCpConfig;
import com.baomidou.dynamic.datasource.creator.hikaricp.HikariDataSourceCreator;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * 动态数据源连接池并发预热与冷启动防抖动引擎单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
@DisplayName("动态数据源连接池预热防抖引擎单测")
class DynamicDatasourcePreWarmTest {

    private AtengDynamicRoutingDataSource routingDataSource;
    private DynamicDatasourceTemplate template;
    private DatasourceProperties properties;

    @BeforeEach
    void setUp() {
        DynamicDataSourceContextHolder.clear();

        JdbcDataSource masterDs = new JdbcDataSource();
        masterDs.setURL("jdbc:h2:mem:master_prewarm;DB_CLOSE_DELAY=-1");
        masterDs.setUser("sa");
        masterDs.setPassword("");

        properties = new DatasourceProperties();
        properties.getDynamicLoad().setProbeTimeout(Duration.ofSeconds(1));
        properties.getDynamicLoad().setDrainTimeout(Duration.ofSeconds(2));
        properties.getDynamicLoad().setPreWarm(false);
        properties.getDynamicLoad().setPreWarmConnections(4);

        routingDataSource = new AtengDynamicRoutingDataSource(
                Collections.emptyList(),
                properties,
                null,
                null,
                null
        );
        routingDataSource.addDataSource("master", masterDs);
        routingDataSource.setPrimary("master");

        DefaultDataSourceCreator creator = new DefaultDataSourceCreator();
        HikariDataSourceCreator hikariCreator = new HikariDataSourceCreator(new HikariCpConfig());
        creator.setCreators(List.of(hikariCreator));

        template = new DynamicDatasourceTemplate(
                routingDataSource,
                properties,
                null,
                null,
                creator
        );
    }

    @AfterEach
    void tearDown() {
        DynamicDataSourceContextHolder.clear();
        if (routingDataSource != null) {
            routingDataSource.destroy();
        }
    }

    private DataSourceProperty createH2Property(String poolName, String dbName) {
        DataSourceProperty property = new DataSourceProperty();
        property.setPoolName(poolName);
        property.setUrl("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        property.setUsername("sa");
        property.setPassword("");
        property.setDriverClassName("org.h2.Driver");
        return property;
    }

    @Test
    @DisplayName("测试配置开启 preWarm 时动态添加数据源自动触发物理连接池预热")
    void testAddDataSourceWithPreWarmEnabledInProperties() {
        properties.getDynamicLoad().setPreWarm(true);
        properties.getDynamicLoad().setPreWarmConnections(3);

        DataSourceProperty prop = createH2Property("prewarm_pool_1", "prewarm_db_1");
        template.addDataSource("prewarm_ds_1", prop);

        assertThat(template.getDataSourceKeys()).contains("prewarm_ds_1");

        // 校验数据源已就绪并可立即获取连接执行查询
        Integer result = template.callInDataSource("prewarm_ds_1", () -> {
            DataSource current = routingDataSource.determineDataSource();
            try (Connection conn = current.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1 FROM DUAL")) {
                rs.next();
                return rs.getInt(1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(result).isEqualTo(1);
    }

    @Test
    @DisplayName("测试显式传入 preWarm=true 参数在添加数据源后执行连接池预热")
    void testAddDataSourceWithExplicitPreWarmFlag() {
        properties.getDynamicLoad().setPreWarm(false); // 全局关闭

        DataSourceProperty prop = createH2Property("prewarm_pool_2", "prewarm_db_2");
        template.addDataSource("prewarm_ds_2", prop, true); // 显式开启

        assertThat(template.getDataSourceKeys()).contains("prewarm_ds_2");
    }

    @Test
    @DisplayName("测试显式调用 preWarmDataSource 建立并释放指定数量连接")
    void testExplicitPreWarmDataSourceReturnsCount() {
        DataSourceProperty prop = createH2Property("prewarm_pool_3", "prewarm_db_3");
        template.addDataSource("prewarm_ds_3", prop, false);

        int count = template.preWarmDataSource("prewarm_ds_3", 4);
        assertThat(count).isEqualTo(4);
    }

    @Test
    @DisplayName("测试预热不存在的数据源时安全返回 0 且不抛出异常")
    void testPreWarmNonExistentDataSourceReturnsZero() {
        int count = template.preWarmDataSource("non_existent_ds", 5);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("测试预热过程中遇到物理连接异常遵循 Fail-Safe 容错机制安全降级")
    void testPreWarmDataSourceFailSafeTolerance() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        Connection mockConn1 = mock(Connection.class);
        Connection mockConn2 = mock(Connection.class);
        when(mockConn1.isValid(anyInt())).thenReturn(true);
        when(mockConn2.isValid(anyInt())).thenReturn(true);

        AtomicInteger callCount = new AtomicInteger(0);
        when(mockDs.getConnection()).thenAnswer(inv -> {
            int c = callCount.incrementAndGet();
            if (c == 1) return mockConn1;
            if (c == 2) return mockConn2;
            throw new SQLException("Max physical connections reached");
        });

        routingDataSource.addDataSource("mock_partial_ds", mockDs);

        // 尝试预热 5 个连接，前 2 个成功，第 3 个抛出异常
        int warmed = template.preWarmDataSource("mock_partial_ds", 5);

        // 应当 Fail-Safe 降级，返回成功建立的 2 个连接，不向外抛出异常
        assertThat(warmed).isEqualTo(2);

        // 校验成功建立的两个连接均已在 finally 中安全关闭归还
        verify(mockConn1, times(1)).close();
        verify(mockConn2, times(1)).close();
    }

    @Test
    @DisplayName("测试 addDataSource 预热失败时遵循 Fail-Safe 保障数据源挂载不被回滚")
    void testAddDataSourceWithPreWarmFailsafeGracefulDegradation() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        Connection checkConn = mock(Connection.class);
        when(checkConn.isValid(anyInt())).thenReturn(true);

        AtomicInteger callCount = new AtomicInteger(0);
        when(mockDs.getConnection()).thenAnswer(inv -> {
            int c = callCount.incrementAndGet();
            if (c == 1) {
                // validateConnection 校验连接成功
                return checkConn;
            }
            // 预热阶段失败
            throw new SQLException("Pool connection creation failed during warm-up");
        });

        // 挂载数据源并触发预热
        template.addDataSource("mock_warm_fail_ds", mockDs, true);

        // 校验虽然预热降级，但数据源依然安全成功注册，未被中断回滚
        assertThat(template.getDataSourceKeys()).contains("mock_warm_fail_ds");
        verify(checkConn, times(1)).close();
    }

    @Test
    @DisplayName("测试 isValid 抛出异常时物理连接绝对确定性关闭防泄漏")
    void testPreWarmDataSource_ConnectionLeakDefense_WhenIsValidThrows() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        Connection leakyConn = mock(Connection.class);
        when(mockDs.getConnection()).thenReturn(leakyConn);
        when(leakyConn.isValid(anyInt())).thenThrow(new SQLException("Network timeout during validation"));

        routingDataSource.addDataSource("leak_defense_ds", mockDs);

        int count = template.preWarmDataSource("leak_defense_ds", 3);
        assertThat(count).isZero();

        // 校验异常退出时连接必定被 close()，彻底杜绝连接逃逸与池泄漏
        verify(leakyConn, atLeastOnce()).close();
    }

    @Test
    @DisplayName("测试结合 TaskExecutor 执行并发连接池预热")
    void testPreWarmDataSource_ConcurrentExecution_WithTaskExecutor() throws Exception {
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setThreadNamePrefix("test-prewarm-");
        executor.initialize();

        try {
            DefaultDataSourceCreator creator = new DefaultDataSourceCreator();
            HikariDataSourceCreator hikariCreator = new HikariDataSourceCreator(new HikariCpConfig());
            creator.setCreators(List.of(hikariCreator));

            DynamicDatasourceTemplate concurrentTemplate = new DynamicDatasourceTemplate(
                    routingDataSource,
                    properties,
                    null,
                    null,
                    creator,
                    executor
            );

            DataSourceProperty property = createH2Property("concurrent_prewarm_pool", "concurrent_prewarm_db");
            concurrentTemplate.addDataSource("concurrent_ds", property, true);

            assertThat(concurrentTemplate.getDataSourceKeys()).contains("concurrent_ds");

            int warmed = concurrentTemplate.preWarmDataSource("concurrent_ds", 4);
            assertThat(warmed).isEqualTo(4);
        } finally {
            executor.destroy();
        }
    }
}

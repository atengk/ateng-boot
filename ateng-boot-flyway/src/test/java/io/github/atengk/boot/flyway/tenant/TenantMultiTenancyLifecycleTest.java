package io.github.atengk.boot.flyway.tenant;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import com.baomidou.dynamic.datasource.creator.DefaultDataSourceCreator;
import com.baomidou.dynamic.datasource.creator.hikaricp.HikariCpConfig;
import com.baomidou.dynamic.datasource.creator.hikaricp.HikariDataSourceCreator;
import com.baomidou.dynamic.datasource.provider.DynamicDataSourceProvider;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.zaxxer.hikari.HikariDataSource;
import io.github.atengk.boot.core.autoconfigure.AtengAsyncAutoConfiguration;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.datasource.guard.DynamicDatasourceGuard;
import io.github.atengk.boot.datasource.loader.DynamicDataSourceLoader;
import io.github.atengk.boot.datasource.loader.DynamicDataSourceSyncManager;
import io.github.atengk.boot.datasource.loader.SyncResult;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.datasource.tenant.DefaultTenantDataSourceProvider;
import io.github.atengk.boot.datasource.tenant.TenantDataSourceProvider;
import io.github.atengk.boot.flyway.autoconfigure.AtengFlywayAutoConfiguration;
import io.github.atengk.boot.flyway.enums.BatchExecutionPolicy;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.event.FlywayMigrationCompletedEvent;
import io.github.atengk.boot.flyway.event.FlywayMigrationFailedEvent;
import io.github.atengk.boot.flyway.exception.FlywayException;
import io.github.atengk.boot.flyway.listener.FlywayMigrationListener;
import io.github.atengk.boot.flyway.model.BatchMigrationResult;
import io.github.atengk.boot.flyway.model.MigrationOutcome;
import io.github.atengk.boot.flyway.model.MigrationSummary;
import io.github.atengk.boot.flyway.model.MigrationTargetInfo;
import io.github.atengk.boot.flyway.observability.FlywayHealthIndicator;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L3 SaaS 物理多租户启动批量迁移与动态挂载生命周期自愈闭环测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
class TenantMultiTenancyLifecycleTest {

    private final List<HikariDataSource> openedPools = new ArrayList<>();

    @AfterEach
    void cleanupContextsAndPools() {
        DynamicDataSourceContextHolder.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
        for (HikariDataSource pool : openedPools) {
            if (!pool.isClosed()) {
                pool.close();
            }
        }
        openedPools.clear();
    }

    private HikariDataSource newH2Pool(String prefix) {
        String dbName = prefix + "_" + UUID.randomUUID().toString().replace("-", "");
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("test-" + prefix);
        ds.setJdbcUrl("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUsername("sa");
        ds.setPassword("");
        ds.setDriverClassName("org.h2.Driver");
        openedPools.add(ds);
        return ds;
    }

    private AtengDynamicRoutingDataSource buildRoutingDataSource(Map<String, DataSource> dsMap) {
        DynamicDataSourceProvider provider = () -> dsMap;
        DatasourceProperties props = new DatasourceProperties();
        props.getMultiTenancy().setEnabled(true);
        props.getReadWriteSplit().setEnabled(true);
        AtengDynamicRoutingDataSource routing = new AtengDynamicRoutingDataSource(
                List.of(provider), props, new DefaultTenantDataSourceProvider(props));
        routing.setPrimary("master");
        return routing;
    }

    private boolean tableExists(DataSource ds, String tableName) throws Exception {
        try (Connection conn = ds.getConnection();
             var rs = conn.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                if (tableName.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    return true;
                }
            }
            return false;
        }
    }

    @Test
    @DisplayName("L3：启动时 master 与 tenant_1001/tenant_1002_master/自定义租户分目录隔离迁移，不误触 *_slave，且支持全租户门面")
    void shouldMigrateMasterAndTenantsInIsolatedDirectoriesWhileExcludingSlaves() throws Exception {
        HikariDataSource masterDs = newH2Pool("master");
        HikariDataSource slaveDs = newH2Pool("slave");
        HikariDataSource tenant1001Ds = newH2Pool("tenant_1001");
        HikariDataSource tenant1002MasterDs = newH2Pool("tenant_1002_master");
        HikariDataSource tenant1002SlaveDs = newH2Pool("tenant_1002_slave");
        HikariDataSource vipTenantDs = newH2Pool("vip_tenant_alpha");
        HikariDataSource analyticsDs = newH2Pool("analytics_db");

        Map<String, DataSource> dsMap = new LinkedHashMap<>();
        dsMap.put("master", masterDs);
        dsMap.put("slave", slaveDs);
        dsMap.put("tenant_1001", tenant1001Ds);
        dsMap.put("tenant_1002_master", tenant1002MasterDs);
        dsMap.put("tenant_1002_slave", tenant1002SlaveDs);
        dsMap.put("vip_tenant_alpha", vipTenantDs);
        dsMap.put("analytics_db", analyticsDs);

        AtengDynamicRoutingDataSource routingDs = buildRoutingDataSource(dsMap);
        TenantDataSourceProvider customTenantProvider = new TenantDataSourceProvider() {
            @Override
            public String resolveDataSourceKey(String tenantId) {
                return "alpha".equals(tenantId) ? "vip_tenant_alpha" : "tenant_" + tenantId;
            }

            @Override
            public Map<String, String> getTenantDataSourceMap() {
                return Map.of("alpha", "vip_tenant_alpha");
            }
        };

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> routingDs)
                .withBean(TenantDataSourceProvider.class, () -> customTenantProvider)
                .withPropertyValues(
                        "spring.flyway.locations=classpath:db/multi/master",
                        "ateng.boot.flyway.tenant.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    // 1. 平台表仅存在于 master，租户表仅存在于各租户库
                    assertThat(tableExists(masterDs, "platform_config")).isTrue();
                    assertThat(tableExists(masterDs, "tenant_order")).isFalse();

                    assertThat(tableExists(tenant1001Ds, "tenant_order")).isTrue();
                    assertThat(tableExists(tenant1001Ds, "platform_config")).isFalse();
                    Map<String, Object> t1Row = new JdbcTemplate(tenant1001Ds).queryForMap(
                            "SELECT order_no, ds_tag, tenant_tag FROM tenant_order WHERE id = 1");
                    assertThat(t1Row.get("ds_tag")).isEqualTo("tenant_1001");
                    assertThat(t1Row.get("tenant_tag")).isEqualTo("1001");
                    assertThat(t1Row.get("order_no")).isEqualTo("ORD-1001-001");

                    // 2. 兼容 tenant_<id>_master 命名规范：剥离 _master 后缀解析 tenantId=1002
                    assertThat(tableExists(tenant1002MasterDs, "tenant_order")).isTrue();
                    Map<String, Object> t2Row = new JdbcTemplate(tenant1002MasterDs).queryForMap(
                            "SELECT ds_tag, tenant_tag FROM tenant_order WHERE id = 1");
                    assertThat(t2Row.get("ds_tag")).isEqualTo("tenant_1002_master");
                    assertThat(t2Row.get("tenant_tag")).isEqualTo("1002");

                    // 3. TenantDataSourceProvider 自定义映射的租户也纳入迁移
                    assertThat(tableExists(vipTenantDs, "tenant_order")).isTrue();
                    Map<String, Object> alphaRow = new JdbcTemplate(vipTenantDs).queryForMap(
                            "SELECT ds_tag, tenant_tag FROM tenant_order WHERE id = 1");
                    assertThat(alphaRow.get("ds_tag")).isEqualTo("vip_tenant_alpha");
                    assertThat(alphaRow.get("tenant_tag")).isEqualTo("alpha");

                    // 4. 从库及未纳管辅助库绝对零入侵
                    assertThat(tableExists(slaveDs, "flyway_schema_history")).isFalse();
                    assertThat(tableExists(tenant1002SlaveDs, "flyway_schema_history")).isFalse();
                    assertThat(tableExists(tenant1002SlaveDs, "tenant_order")).isFalse();
                    assertThat(tableExists(analyticsDs, "flyway_schema_history")).isFalse();
                    assertThat(tableExists(analyticsDs, "tenant_order")).isFalse();

                    // 5. FlywayTemplate 租户批处理门面与汇总校验
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    BatchMigrationResult migrateBatch = template.migrateAllTenants();
                    assertThat(migrateBatch.isAllSuccess()).isTrue();
                    assertThat(migrateBatch.getPolicy()).isEqualTo(BatchExecutionPolicy.FAIL_SAFE);
                    assertThat(migrateBatch.getTotalTargets()).isEqualTo(3);
                    assertThat(migrateBatch.getSuccessCount()).isEqualTo(3);

                    BatchMigrationResult validateBatch = template.validateAllTenants();
                    assertThat(validateBatch.isAllSuccess()).isTrue();
                    assertThat(validateBatch.getTotalTargets()).isEqualTo(3);

                    List<MigrationTargetInfo> infos = template.infoAll();
                    assertThat(infos).extracting(MigrationTargetInfo::getTargetKey)
                            .containsExactlyInAnyOrder("master", "vip_tenant_alpha", "tenant_1001", "tenant_1002_master");
                });
    }

    @Test
    @DisplayName("L3：租户默认 FAIL_SAFE 单库故障不拖垮整体启动与健康回执；切换 FAIL_FAST 立即阻断启动；支持租户模板表与 Baseline 覆写")
    void shouldIsolateTenantFailureInFailSafeAndBlockInFailFast(@TempDir Path tempDir) throws Exception {
        Path brokenTenantDir = tempDir.resolve("broken-tenant");
        Files.createDirectories(brokenTenantDir);
        Files.writeString(brokenTenantDir.resolve("V1__bad_tenant.sql"),
                "-- jdbc:mysql://root:TenantSecret88@10.0.0.8:3306/t?password=Leak99\nBROKEN DDL;",
                StandardCharsets.UTF_8);

        HikariDataSource masterDs = newH2Pool("master_fs");
        HikariDataSource t1Ds = newH2Pool("tenant_1001_fs");
        HikariDataSource t2BrokenDs = newH2Pool("tenant_1002_fs");
        HikariDataSource t3Ds = newH2Pool("tenant_1003_fs");
        new JdbcTemplate(t1Ds).execute("CREATE TABLE legacy_tenant_cfg (id BIGINT PRIMARY KEY);");

        Map<String, DataSource> safeMap = new LinkedHashMap<>();
        safeMap.put("master", masterDs);
        safeMap.put("tenant_1001", t1Ds);
        safeMap.put("tenant_1002", t2BrokenDs);
        safeMap.put("tenant_1003", t3Ds);
        AtengDynamicRoutingDataSource safeRouting = buildRoutingDataSource(safeMap);

        List<FlywayMigrationFailedEvent> failedEvents = new CopyOnWriteArrayList<>();

        // 1. 默认 FAIL_SAFE：tenant_1002 失败不影响 master 与 tenant_1001/1003 完成迁移
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> safeRouting)
                .withBean(ApplicationListener.class,
                        () -> (ApplicationListener<FlywayMigrationFailedEvent>) failedEvents::add)
                .withPropertyValues(
                        "spring.flyway.locations=classpath:db/multi/master",
                        "ateng.boot.flyway.tenant.enabled=true",
                        "ateng.boot.flyway.tenant.table=tenant_flyway_history",
                        "ateng.boot.flyway.datasources.tenant_1001.baseline-on-migrate=true",
                        "ateng.boot.flyway.datasources.tenant_1001.baseline-version=0",
                        "ateng.boot.flyway.datasources.tenant_1002.locations=filesystem:" + brokenTenantDir.toAbsolutePath()
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(tableExists(masterDs, "platform_config")).isTrue();
                    assertThat(tableExists(t1Ds, "tenant_order")).isTrue();
                    assertThat(tableExists(t1Ds, "tenant_flyway_history")).isTrue();
                    assertThat(tableExists(t3Ds, "tenant_order")).isTrue();
                    assertThat(tableExists(t2BrokenDs, "tenant_order")).isFalse();

                    assertThat(failedEvents).hasSize(1);
                    assertThat(failedEvents.get(0).getTargetKey()).isEqualTo("tenant_1002");
                    assertThat(failedEvents.get(0).getErrorMessage()).doesNotContain("TenantSecret88");
                    assertThat(failedEvents.get(0).getErrorMessage()).doesNotContain("Leak99");

                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    MigrationSummary startupSummary = template.getLastMigrationSummary();
                    assertThat(startupSummary.getTotalTargets()).isEqualTo(4);
                    assertThat(startupSummary.getSuccessCount()).isEqualTo(3);
                    assertThat(startupSummary.getFailedCount()).isEqualTo(1);

                    FlywayHealthIndicator healthIndicator = context.getBean(FlywayHealthIndicator.class);
                    assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.DOWN);

                    BatchMigrationResult tenantBatch = template.migrateAllTenants();
                    assertThat(tenantBatch.getPolicy()).isEqualTo(BatchExecutionPolicy.FAIL_SAFE);
                    assertThat(tenantBatch.getTotalTargets()).isEqualTo(3);
                    assertThat(tenantBatch.getSuccessCount()).isEqualTo(2);
                    assertThat(tenantBatch.getFailedCount()).isEqualTo(1);
                    assertThat(tenantBatch.getFailedTargets()).containsExactly("tenant_1002");
                    assertThat(tenantBatch.getFailures().get(0).getErrorMessage()).doesNotContain("TenantSecret88");
                });

        // 2. 切换 FAIL_FAST：任一租户失败立即中断并阻断启动
        HikariDataSource masterFfDs = newH2Pool("master_ff");
        HikariDataSource t1FfDs = newH2Pool("tenant_1001_ff");
        HikariDataSource t2FfDs = newH2Pool("tenant_1002_ff");
        Map<String, DataSource> ffMap = new LinkedHashMap<>();
        ffMap.put("master", masterFfDs);
        ffMap.put("tenant_1001", t1FfDs);
        ffMap.put("tenant_1002", t2FfDs);
        AtengDynamicRoutingDataSource ffRouting = buildRoutingDataSource(ffMap);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> ffRouting)
                .withPropertyValues(
                        "spring.flyway.locations=classpath:db/multi/master",
                        "ateng.boot.flyway.tenant.enabled=true",
                        "ateng.boot.flyway.tenant.failure-policy=FAIL_FAST",
                        "ateng.boot.flyway.datasources.tenant_1002.locations=filesystem:" + brokenTenantDir.toAbsolutePath()
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasCauseInstanceOf(FlywayException.class);
                });
    }

    @Test
    @DisplayName("L3：有界并发（concurrency > 1）与异步启动模式依托 Core 受管线程池与 AsyncContext 透传上下文且零并发串库")
    void shouldExecuteBoundedConcurrentAndAsyncStartupWithAsyncContextPropagation() throws Exception {
        HikariDataSource masterDs = newH2Pool("master_conc");
        Map<String, DataSource> dsMap = new LinkedHashMap<>();
        dsMap.put("master", masterDs);
        List<String> tenantIds = List.of("2001", "2002", "2003", "2004", "2005", "2006");
        Map<String, HikariDataSource> tenantPools = new LinkedHashMap<>();
        for (String id : tenantIds) {
            HikariDataSource pool = newH2Pool("tenant_" + id);
            dsMap.put("tenant_" + id, pool);
            tenantPools.put(id, pool);
        }
        AtengDynamicRoutingDataSource routingDs = buildRoutingDataSource(dsMap);

        AtomicInteger activeWorkers = new AtomicInteger(0);
        AtomicInteger maxObservedWorkers = new AtomicInteger(0);
        Set<String> workerThreadNames = ConcurrentHashMap.newKeySet();
        Map<String, String> observedMdcTraceIds = new ConcurrentHashMap<>();
        Map<String, String> observedUserIds = new ConcurrentHashMap<>();

        FlywayMigrationListener probeListener = new FlywayMigrationListener() {
            @Override
            public void onBeforeMigrate(String targetKey) {
                if (!targetKey.startsWith("tenant_")) {
                    return;
                }
                int current = activeWorkers.incrementAndGet();
                maxObservedWorkers.accumulateAndGet(current, Math::max);
                workerThreadNames.add(Thread.currentThread().getName());
                observedMdcTraceIds.put(targetKey, String.valueOf(MDC.get("traceId")));
                observedUserIds.put(targetKey, String.valueOf(UserContextHolder.getUserId()));
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void onMigrationSucceeded(MigrationOutcome outcome) {
                if (outcome.getTargetKey().startsWith("tenant_")) {
                    activeWorkers.decrementAndGet();
                }
            }

            @Override
            public void onMigrationFailed(String targetKey, MigrationOutcome outcome, Throwable cause) {
                if (targetKey.startsWith("tenant_")) {
                    activeWorkers.decrementAndGet();
                }
            }
        };

        // 1. 测试有界并发 (concurrency = 2) + AsyncContext 上下文透传
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengAsyncAutoConfiguration.class,
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> routingDs)
                .withBean(FlywayMigrationListener.class, () -> probeListener)
                .withPropertyValues(
                        "spring.flyway.locations=classpath:db/multi/master",
                        "ateng.boot.flyway.tenant.enabled=true",
                        "ateng.boot.flyway.tenant.migrate-on-startup=false",
                        "ateng.boot.flyway.tenant.concurrency=2"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);

                    MDC.put("traceId", "trace-l3-batch-99");
                    UserContextHolder.setUserId("admin-007");
                    try {
                        BatchMigrationResult result = template.migrateAllTenants();
                        assertThat(result.isAllSuccess()).isTrue();
                        assertThat(result.getTotalTargets()).isEqualTo(6);
                        assertThat(result.getSuccessCount()).isEqualTo(6);
                    } finally {
                        MDC.clear();
                        UserContextHolder.clear();
                    }

                    // 验证严格有界并发 <= 2 且确实并发执行 (== 2)
                    assertThat(maxObservedWorkers.get()).isBetween(2, 2);
                    assertThat(workerThreadNames).noneMatch("main"::equals);

                    // 验证 AsyncContext 完整透传 MDC 与 UserContext 至所有工作线程
                    for (String id : tenantIds) {
                        String key = "tenant_" + id;
                        assertThat(observedMdcTraceIds.get(key)).isEqualTo("trace-l3-batch-99");
                        assertThat(observedUserIds.get(key)).isEqualTo("admin-007");

                        // 验证零并发串库：每个物理库仅写入自身的 tenantId / dsKey
                        Map<String, Object> row = new JdbcTemplate(tenantPools.get(id)).queryForMap(
                                "SELECT ds_tag, tenant_tag FROM tenant_order WHERE id = 1");
                        assertThat(row.get("ds_tag")).isEqualTo(key);
                        assertThat(row.get("tenant_tag")).isEqualTo(id);
                    }
                });

        // 2. 测试启动期异步租户批量迁移 (asyncStartup = true)
        HikariDataSource asyncMaster = newH2Pool("master_async");
        Map<String, DataSource> asyncMap = new LinkedHashMap<>();
        asyncMap.put("master", asyncMaster);
        List<String> asyncTenantIds = List.of("3001", "3002", "3003", "3004");
        Map<String, HikariDataSource> asyncPools = new LinkedHashMap<>();
        for (String id : asyncTenantIds) {
            HikariDataSource p = newH2Pool("tenant_" + id);
            asyncMap.put("tenant_" + id, p);
            asyncPools.put(id, p);
        }
        AtengDynamicRoutingDataSource asyncRouting = buildRoutingDataSource(asyncMap);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengAsyncAutoConfiguration.class,
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> asyncRouting)
                .withPropertyValues(
                        "spring.flyway.locations=classpath:db/multi/master",
                        "ateng.boot.flyway.tenant.enabled=true",
                        "ateng.boot.flyway.tenant.async-startup=true",
                        "ateng.boot.flyway.tenant.concurrency=2"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(tableExists(asyncMaster, "platform_config")).isTrue();

                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    CompletableFuture<BatchMigrationResult> future = template.getStartupTenantMigrationFuture();
                    assertThat(future).isNotNull();

                    BatchMigrationResult asyncBatch = future.get(15, TimeUnit.SECONDS);
                    assertThat(asyncBatch.isAllSuccess()).isTrue();
                    assertThat(asyncBatch.getTotalTargets()).isEqualTo(4);

                    for (String id : asyncTenantIds) {
                        assertThat(tableExists(asyncPools.get(id), "tenant_order")).isTrue();
                    }
                    MigrationSummary finalSummary = template.getLastMigrationSummary();
                    assertThat(finalSummary.getTotalTargets()).isEqualTo(5);
                    assertThat(finalSummary.getSuccessCount()).isEqualTo(5);
                });
    }

    @Test
    @DisplayName("L3：运行期动态挂载 tenant_1003 与差量同步自动建表；失败时 KEEP_AND_REPORT 保留支持补迁、ROLLBACK_DATASOURCE 自动摘除")
    void shouldAutoMigrateOnDynamicMountAndApplyFailurePolicies(@TempDir Path tempDir) throws Exception {
        Path dynamicSqlDir = tempDir.resolve("dynamic-tenant");
        Files.createDirectories(dynamicSqlDir);
        Path migrationFile = dynamicSqlDir.resolve("V1__init_dynamic_tenant.sql");
        String validSql = """
                CREATE TABLE IF NOT EXISTS tenant_order (
                    id BIGINT PRIMARY KEY,
                    order_no VARCHAR(64) NOT NULL,
                    ds_tag VARCHAR(64) NOT NULL,
                    tenant_tag VARCHAR(64) NOT NULL
                );
                INSERT INTO tenant_order (id, order_no, ds_tag, tenant_tag)
                VALUES (1, 'ORD-${ateng_tenant_id}-001', '${ateng_ds_key}', '${ateng_tenant_id}');
                """;
        Files.writeString(migrationFile, validSql, StandardCharsets.UTF_8);

        HikariDataSource masterDs = newH2Pool("master_dyn");
        HikariDataSource t1001Ds = newH2Pool("tenant_1001_dyn");
        Map<String, DataSource> dsMap = new LinkedHashMap<>();
        dsMap.put("master", masterDs);
        dsMap.put("tenant_1001", t1001Ds);
        AtengDynamicRoutingDataSource routingDs = buildRoutingDataSource(dsMap);

        DefaultDataSourceCreator creator = new DefaultDataSourceCreator();
        creator.setCreators(List.of(new HikariDataSourceCreator(new HikariCpConfig())));

        DatasourceProperties dsProps = new DatasourceProperties();
        dsProps.getMultiTenancy().setEnabled(true);
        dsProps.getReadWriteSplit().setEnabled(true);
        TenantDataSourceProvider tenantProvider = new DefaultTenantDataSourceProvider(dsProps);
        DynamicDatasourceTemplate dsTemplateBean = new DynamicDatasourceTemplate(
                routingDs, dsProps, tenantProvider, new DynamicDatasourceGuard(dsProps), creator);

        List<FlywayMigrationCompletedEvent> completedEvents = new CopyOnWriteArrayList<>();
        List<FlywayMigrationFailedEvent> failedEvents = new CopyOnWriteArrayList<>();

        Map<String, DataSourceProperty> externalSources = new LinkedHashMap<>();
        DynamicDataSourceLoader loader = () -> new LinkedHashMap<>(externalSources);
        DynamicDataSourceSyncManager syncManagerBean = new DynamicDataSourceSyncManager(dsTemplateBean, dsProps, loader);

        // 1. 正常动态挂载 tenant_1003 + SyncManager 差量同步 tenant_1004 + KEEP_AND_REPORT 失败后修复补迁
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> routingDs)
                .withBean(DatasourceProperties.class, () -> dsProps)
                .withBean(TenantDataSourceProvider.class, () -> tenantProvider)
                .withBean(DynamicDatasourceTemplate.class, () -> dsTemplateBean)
                .withBean(DynamicDataSourceSyncManager.class, () -> syncManagerBean)
                .withBean("completedListener", ApplicationListener.class,
                        () -> (ApplicationListener<FlywayMigrationCompletedEvent>) completedEvents::add)
                .withBean("failedListener", ApplicationListener.class,
                        () -> (ApplicationListener<FlywayMigrationFailedEvent>) failedEvents::add)
                .withPropertyValues(
                        "spring.flyway.locations=classpath:db/multi/master",
                        "ateng.boot.flyway.tenant.enabled=true",
                        "ateng.boot.flyway.tenant.dynamic-mount-failure-policy=KEEP_AND_REPORT",
                        "ateng.boot.flyway.datasources.tenant_flaky.locations=filesystem:" + dynamicSqlDir.toAbsolutePath()
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DynamicDatasourceTemplate dsTemplate = context.getBean(DynamicDatasourceTemplate.class);
                    FlywayTemplate flywayTemplate = context.getBean(FlywayTemplate.class);
                    FlywayHealthIndicator healthIndicator = context.getBean(FlywayHealthIndicator.class);
                    DynamicDataSourceSyncManager syncManager = context.getBean(DynamicDataSourceSyncManager.class);

                    // 1a. 动态挂载 tenant_1003 -> 自动完成租户建表迁移
                    HikariDataSource t1003Ds = newH2Pool("tenant_1003_dyn");
                    dsTemplate.addDataSource("tenant_1003", t1003Ds, false);
                    assertThat(tableExists(t1003Ds, "tenant_order")).isTrue();
                    Map<String, Object> t3Row = new JdbcTemplate(t1003Ds).queryForMap(
                            "SELECT ds_tag, tenant_tag FROM tenant_order WHERE id = 1");
                    assertThat(t3Row.get("ds_tag")).isEqualTo("tenant_1003");
                    assertThat(t3Row.get("tenant_tag")).isEqualTo("1003");
                    assertThat(completedEvents).anyMatch(e -> "tenant_1003".equals(e.getOutcome().getTargetKey()));
                    assertThat(flywayTemplate.getLastMigrationSummary().getTotalTargets()).isEqualTo(3);

                    // 1b. 动态挂载未纳管辅助库与从库 -> 保持零入侵
                    HikariDataSource unmanagedDs = newH2Pool("analytics_dyn");
                    HikariDataSource tenantSlaveDs = newH2Pool("tenant_1003_slave");
                    dsTemplate.addDataSource("analytics_dyn", unmanagedDs, false);
                    dsTemplate.addDataSource("tenant_1003_slave", tenantSlaveDs, false);
                    assertThat(tableExists(unmanagedDs, "flyway_schema_history")).isFalse();
                    assertThat(tableExists(tenantSlaveDs, "flyway_schema_history")).isFalse();

                    // 1c. 通过 DynamicDataSourceSyncManager 差量同步新增 tenant_1004 -> 自动建表
                    DataSourceProperty t1004Prop = new DataSourceProperty();
                    t1004Prop.setPoolName("sync-tenant-1004");
                    t1004Prop.setUrl("jdbc:h2:mem:tenant_1004_" + UUID.randomUUID().toString().replace("-", "")
                            + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
                    t1004Prop.setUsername("sa");
                    t1004Prop.setPassword("");
                    t1004Prop.setDriverClassName("org.h2.Driver");
                    externalSources.put("tenant_1004", t1004Prop);

                    SyncResult syncResult = syncManager.sync();
                    assertThat(syncResult.getAddedKeys()).containsExactly("tenant_1004");
                    DataSource t1004Ds = dsTemplate.getDataSource("tenant_1004");
                    assertThat(tableExists(t1004Ds, "tenant_order")).isTrue();

                    // 1d. 模拟动态挂载迁移失败 (KEEP_AND_REPORT)：模拟回调窗口内并发探查先缓存旧 UP，完成后仍须立即失效并转 DOWN
                    flywayTemplate.addMigrationListener(new io.github.atengk.boot.flyway.listener.FlywayMigrationListener() {
                        @Override
                        public void onMigrationFailed(String targetKey, MigrationOutcome outcome, Throwable cause) {
                            if ("tenant_flaky".equals(targetKey)) {
                                // 在 lastMigrationSummary 尚未更新前探查一次健康状态，模拟并发窗口竞态
                                healthIndicator.health();
                            }
                        }
                    });
                    Files.writeString(migrationFile, "INVALID SQL SYNTAX FOR TENANT;", StandardCharsets.UTF_8);
                    HikariDataSource flakyDs = newH2Pool("tenant_flaky");
                    dsTemplate.addDataSource("tenant_flaky", flakyDs, false);

                    assertThat(dsTemplate.containsDataSource("tenant_flaky")).isTrue();
                    assertThat(tableExists(flakyDs, "tenant_order")).isFalse();
                    assertThat(failedEvents).anyMatch(e -> "tenant_flaky".equals(e.getTargetKey()));
                    assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.DOWN);

                    // 修复脚本后执行 repair + 手工重试补迁
                    Files.writeString(migrationFile, validSql, StandardCharsets.UTF_8);
                    flywayTemplate.repair("tenant_flaky");
                    MigrationOutcome retryOutcome = flywayTemplate.migrate("tenant_flaky");
                    assertThat(retryOutcome.isSuccess()).isTrue();
                    assertThat(tableExists(flakyDs, "tenant_order")).isTrue();
                    assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.UP);
                });

        // 2. ROLLBACK_DATASOURCE 策略：动态挂载迁移失败时，自动将未就绪数据源从路由表摘除并物理关闭
        Path brokenOnlyDir = tempDir.resolve("rollback-bad");
        Files.createDirectories(brokenOnlyDir);
        Files.writeString(brokenOnlyDir.resolve("V1__broken.sql"), "BROKEN SQL;", StandardCharsets.UTF_8);

        HikariDataSource masterRbDs = newH2Pool("master_rb");
        Map<String, DataSource> rbMap = new LinkedHashMap<>();
        rbMap.put("master", masterRbDs);
        AtengDynamicRoutingDataSource rbRouting = buildRoutingDataSource(rbMap);
        DynamicDatasourceTemplate rbDsTemplate = new DynamicDatasourceTemplate(
                rbRouting, dsProps, tenantProvider, new DynamicDatasourceGuard(dsProps), creator);
        List<FlywayMigrationFailedEvent> rbFailedEvents = new CopyOnWriteArrayList<>();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> rbRouting)
                .withBean(DatasourceProperties.class, () -> dsProps)
                .withBean(TenantDataSourceProvider.class, () -> tenantProvider)
                .withBean(DynamicDatasourceTemplate.class, () -> rbDsTemplate)
                .withBean("rbFailedListener", ApplicationListener.class,
                        () -> (ApplicationListener<FlywayMigrationFailedEvent>) rbFailedEvents::add)
                .withPropertyValues(
                        "spring.flyway.locations=classpath:db/multi/master",
                        "ateng.boot.flyway.tenant.enabled=true",
                        "ateng.boot.flyway.tenant.dynamic-mount-failure-policy=ROLLBACK_DATASOURCE",
                        "ateng.boot.flyway.datasources.tenant_rollback.locations=filesystem:" + brokenOnlyDir.toAbsolutePath()
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DynamicDatasourceTemplate dsTemplate = context.getBean(DynamicDatasourceTemplate.class);

                    HikariDataSource badDs = newH2Pool("tenant_rollback");
                    dsTemplate.addDataSource("tenant_rollback", badDs, false);

                    assertThat(dsTemplate.containsDataSource("tenant_rollback")).isFalse();
                    assertThat(rbRouting.getDataSources()).doesNotContainKey("tenant_rollback");
                    assertThat(badDs.isClosed()).isTrue();
                    assertThat(rbFailedEvents).anyMatch(e -> "tenant_rollback".equals(e.getTargetKey()));
                    assertThat(context.getBean(FlywayHealthIndicator.class).health().getStatus()).isEqualTo(Status.UP);
                });
    }

    @Test
    @DisplayName("L3：关闭租户开关时全租户门面零入侵返回空回执；validateAllTenants 支持 FAIL_SAFE 汇总与 FAIL_FAST 抛错")
    void shouldRespectDisabledFlagAndValidateAllTenantsPolicies() {
        HikariDataSource masterDs = newH2Pool("master_val");
        HikariDataSource t1Ds = newH2Pool("tenant_1001_val");
        HikariDataSource t2UnmigratedDs = newH2Pool("tenant_1002_val");
        Map<String, DataSource> dsMap = new LinkedHashMap<>();
        dsMap.put("master", masterDs);
        dsMap.put("tenant_1001", t1Ds);
        dsMap.put("tenant_1002", t2UnmigratedDs);
        AtengDynamicRoutingDataSource routingDs = buildRoutingDataSource(dsMap);

        // 1. tenant.enabled=false 时，migrateAllTenants / validateAllTenants 返回空回执且不触碰租户库
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> routingDs)
                .withPropertyValues("spring.flyway.locations=classpath:db/multi/master")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    BatchMigrationResult empty = template.migrateAllTenants();
                    assertThat(empty.getTotalTargets()).isZero();
                    assertThat(tableExists(t1Ds, "tenant_order")).isFalse();
                });

        // 2. validateAllTenants：先迁移 t1Ds，保持 t2UnmigratedDs 未迁移，验证 FAIL_SAFE 聚合与 FAIL_FAST 阻断
        HikariDataSource master2Ds = newH2Pool("master_val2");
        HikariDataSource t1MigratedDs = newH2Pool("tenant_1001_val2");
        HikariDataSource t2PendingDs = newH2Pool("tenant_1002_val2");
        Map<String, DataSource> dsMap2 = new LinkedHashMap<>();
        dsMap2.put("master", master2Ds);
        dsMap2.put("tenant_1001", t1MigratedDs);
        dsMap2.put("tenant_1002", t2PendingDs);
        AtengDynamicRoutingDataSource routing2 = buildRoutingDataSource(dsMap2);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> routing2)
                .withPropertyValues(
                        "spring.flyway.locations=classpath:db/multi/master",
                        "ateng.boot.flyway.tenant.enabled=true",
                        "ateng.boot.flyway.tenant.migrate-on-startup=false"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    template.migrate("tenant_1001");

                    BatchMigrationResult valSafe = template.validateAllTenants(BatchExecutionPolicy.FAIL_SAFE);
                    assertThat(valSafe.getTotalTargets()).isEqualTo(2);
                    assertThat(valSafe.getSuccessCount()).isEqualTo(1);
                    assertThat(valSafe.getFailedCount()).isEqualTo(1);
                    assertThat(valSafe.getFailedTargets()).containsExactly("tenant_1002");

                    assertThatThrownBy(
                                    () -> template.validateAllTenants(BatchExecutionPolicy.FAIL_FAST))
                            .isInstanceOf(FlywayException.class)
                            .satisfies(ex -> assertThat(((FlywayException) ex).getCode())
                                    .isEqualTo(FlywayResultCode.VALIDATION_FAILED.getCode()));
                });
    }

    @Test
    @DisplayName("L3：开启 asyncStartup=true 时关闭 Spring 上下文应协作式取消未决异步租户迁移任务")
    void shouldCancelPendingAsyncStartupTenantMigrationOnContextClose() throws Exception {
        HikariDataSource masterDs = newH2Pool("master_shutdown");
        Map<String, DataSource> dsMap = new LinkedHashMap<>();
        dsMap.put("master", masterDs);
        for (int i = 1; i <= 4; i++) {
            dsMap.put("tenant_s" + i, newH2Pool("tenant_s" + i));
        }
        AtengDynamicRoutingDataSource routingDs = buildRoutingDataSource(dsMap);

        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        FlywayMigrationListener slowListener = new FlywayMigrationListener() {
            @Override
            public void onBeforeMigrate(String targetKey) {
                if (targetKey.startsWith("tenant_")) {
                    workerEntered.countDown();
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        workerInterrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };

        CompletableFuture<BatchMigrationResult> capturedFuture = new CompletableFuture<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengAsyncAutoConfiguration.class,
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> routingDs)
                .withBean(FlywayMigrationListener.class, () -> slowListener)
                .withPropertyValues(
                        "spring.flyway.locations=classpath:db/multi/master",
                        "ateng.boot.flyway.tenant.enabled=true",
                        "ateng.boot.flyway.tenant.async-startup=true",
                        "ateng.boot.flyway.tenant.concurrency=1"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    CompletableFuture<BatchMigrationResult> future = template.getStartupTenantMigrationFuture();
                    assertThat(future).isNotNull();
                    assertThat(workerEntered.await(5, TimeUnit.SECONDS)).isTrue();

                    context.close();

                    assertThat(future.isDone()).isTrue();
                    assertThat(future.isCancelled()).isTrue();
                    assertThat(workerInterrupted.await(5, TimeUnit.SECONDS)).isTrue();
                    capturedFuture.complete(null);
                });
        assertThat(capturedFuture.isDone()).isTrue();
    }
}

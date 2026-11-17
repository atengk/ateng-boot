package io.github.atengk.boot.flyway.routing;

import com.baomidou.dynamic.datasource.ds.GroupDataSource;
import com.baomidou.dynamic.datasource.provider.DynamicDataSourceProvider;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.zaxxer.hikari.HikariDataSource;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import io.github.atengk.boot.datasource.tenant.DefaultTenantDataSourceProvider;
import io.github.atengk.boot.flyway.autoconfigure.AtengFlywayAutoConfiguration;
import io.github.atengk.boot.flyway.enums.BatchExecutionPolicy;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.enums.MigrationTargetKind;
import io.github.atengk.boot.flyway.exception.FlywayException;
import io.github.atengk.boot.flyway.model.BatchMigrationResult;
import io.github.atengk.boot.flyway.model.MigrationTarget;
import io.github.atengk.boot.flyway.model.MigrationTargetInfo;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import io.github.atengk.boot.flyway.spi.FlywayLockProvider;
import io.github.atengk.boot.flyway.spi.FlywayTargetResolver;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 动态多数据源 L1（锁物理主库/跳过从库）与 L2（静态多库隔离/批处理回执/SPI）集成测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
class DynamicDatasourceMultiTargetTest {

    private final List<HikariDataSource> openedPools = new ArrayList<>();

    @AfterEach
    void cleanupContextsAndPools() {
        DynamicDataSourceContextHolder.clear();
        TenantContextHolder.clear();
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
        // Spring Bean 初始化时会自动触发 InitializingBean#afterPropertiesSet()，
        // 若提前手动调用一次，Spring 再次初始化同名键会触发 DynamicRoutingDataSource 销毁旧池并关闭底层 HikariDataSource
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
    @DisplayName("L1+L2：master 与显式纳管的 log_db 各自独立迁移并自定义历史表，slave/只读组与未纳管 analytics_db 零污染")
    void shouldMigrateMasterAndManagedLogDbWhileSkippingSlavesAndUnmanagedDb() throws Exception {
        HikariDataSource masterDs = newH2Pool("master");
        HikariDataSource slaveDs = newH2Pool("slave");
        HikariDataSource slave1Ds = newH2Pool("slave_1");
        HikariDataSource logDs = newH2Pool("log_db");
        HikariDataSource analyticsDs = newH2Pool("analytics_db");

        Map<String, DataSource> dsMap = new LinkedHashMap<>();
        dsMap.put("master", masterDs);
        dsMap.put("slave", slaveDs);
        dsMap.put("slave_1", slave1Ds);
        dsMap.put("log_db", logDs);
        dsMap.put("analytics_db", analyticsDs);

        AtengDynamicRoutingDataSource routingDs = buildRoutingDataSource(dsMap);
        // 刻意压入从库路由上下文，验证物理直连解包免疫代理切源干扰
        DynamicDataSourceContextHolder.push("slave");

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> routingDs)
                .withPropertyValues(
                        "spring.flyway.locations=classpath:db/multi/master",
                        "ateng.boot.flyway.datasources.log_db.locations=classpath:db/multi/log_db",
                        "ateng.boot.flyway.datasources.log_db.table=log_flyway_history",
                        "ateng.boot.flyway.datasources.log_db.placeholders.customTag=audit-v1"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    // 1. 验证 master 成功迁移并注入上下文占位符 ateng_ds_key
                    assertThat(tableExists(masterDs, "flyway_schema_history")).isTrue();
                    JdbcTemplate masterJdbc = new JdbcTemplate(masterDs);
                    Map<String, Object> masterRow = masterJdbc.queryForMap(
                            "SELECT ds_tag, tenant_tag FROM platform_config WHERE id = 1");
                    assertThat(masterRow.get("ds_tag")).isEqualTo("master");
                    assertThat(masterRow.get("tenant_tag")).isEqualTo("");

                    // 2. 验证显式纳管 log_db 使用独立目录、自定义历史表与占位符完成迁移
                    assertThat(tableExists(logDs, "log_flyway_history")).isTrue();
                    assertThat(tableExists(logDs, "flyway_schema_history")).isFalse();
                    JdbcTemplate logJdbc = new JdbcTemplate(logDs);
                    Map<String, Object> logRow = logJdbc.queryForMap(
                            "SELECT source_ds, custom_tag FROM biz_audit_log WHERE id = 1");
                    assertThat(logRow.get("source_ds")).isEqualTo("log_db");
                    assertThat(logRow.get("custom_tag")).isEqualTo("audit-v1");

                    // 3. 验证 slave、只读 GroupDataSource 成员 slave_1 与未纳管 analytics_db 保持零污染
                    assertThat(tableExists(slaveDs, "flyway_schema_history")).isFalse();
                    assertThat(tableExists(slaveDs, "platform_config")).isFalse();
                    assertThat(tableExists(slave1Ds, "flyway_schema_history")).isFalse();
                    assertThat(tableExists(analyticsDs, "flyway_schema_history")).isFalse();
                    assertThat(tableExists(analyticsDs, "biz_audit_log")).isFalse();

                    // 4. 验证 FlywayTemplate 汇总与 infoAll/validateTargets 对称返回值
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    assertThat(template.getLastMigrationSummary().getTotalTargets()).isEqualTo(2);
                    assertThat(template.getLastMigrationSummary().getSuccessCount()).isEqualTo(2);

                    List<MigrationTargetInfo> infos = template.infoAll();
                    assertThat(infos).extracting(MigrationTargetInfo::getTargetKey)
                            .containsExactly("master", "log_db");
                    BatchMigrationResult valResult = template.validateTargets();
                    assertThat(valResult.isAllSuccess()).isTrue();
                    assertThat(valResult.getTotalTargets()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("显式同名占位符优先级高于自动注入，且 L2 目标支持 baseline-on-migrate 与独立加密 DDL 凭据")
    void shouldHonorExplicitPlaceholderPrecedenceBaselineAndIsolatedCredentialPerTarget() throws Exception {
        HikariDataSource masterDs = newH2Pool("master_ph");
        HikariDataSource baselineDs = newH2Pool("baseline_db");
        new JdbcTemplate(baselineDs).execute(
                "CREATE TABLE legacy_log (id BIGINT PRIMARY KEY, msg VARCHAR(64));");

        Map<String, DataSource> dsMap = new LinkedHashMap<>();
        dsMap.put("master", masterDs);
        dsMap.put("baseline_db", baselineDs);
        AtengDynamicRoutingDataSource routingDs = buildRoutingDataSource(dsMap);

        byte[] sm4Key = CryptoUtil.generateSm4Key();
        byte[] sm4Iv = CryptoUtil.generateIv(CryptoUtil.SM4_IV_SIZE);
        String encPwd = "enc:sm4:" + CryptoUtil.encodeBase64(sm4Key) + ":" + CryptoUtil.encodeBase64(sm4Iv)
                + ":" + CryptoUtil.encryptSm4Cbc("", sm4Key, sm4Iv);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> routingDs)
                .withPropertyValues(
                        "spring.flyway.locations=classpath:db/multi/master",
                        "spring.flyway.placeholders.ateng_ds_key=explicit-global-ds",
                        "spring.flyway.placeholders.ateng_tenant_id=explicit-tenant",
                        "ateng.boot.flyway.datasources.baseline_db.locations=classpath:db/multi/baseline_db",
                        "ateng.boot.flyway.datasources.baseline_db.baseline-on-migrate=true",
                        "ateng.boot.flyway.datasources.baseline_db.baseline-version=1",
                        "ateng.boot.flyway.datasources.baseline_db.credential.username=sa",
                        "ateng.boot.flyway.datasources.baseline_db.credential.password=" + encPwd
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    Map<String, Object> masterRow = new JdbcTemplate(masterDs).queryForMap(
                            "SELECT ds_tag, tenant_tag FROM platform_config WHERE id = 1");
                    assertThat(masterRow.get("ds_tag")).isEqualTo("explicit-global-ds");
                    assertThat(masterRow.get("tenant_tag")).isEqualTo("explicit-tenant");

                    JdbcTemplate baselineJdbc = new JdbcTemplate(baselineDs);
                    baselineJdbc.update("INSERT INTO legacy_log (id, msg) VALUES (10, 'old')");
                    String remark = baselineJdbc.queryForObject(
                            "SELECT remark FROM legacy_log WHERE id = 10", String.class);
                    assertThat(remark).isEqualTo("baselined");

                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    assertThat(template.getActiveTransientPoolCount()).isZero();
                    assertThat(template.getClosedTransientPoolCount()).isGreaterThanOrEqualTo(1L);
                });
    }

    @Test
    @DisplayName("静态核心业务库失败应按 FAIL_FAST 阻断启动，编程式 FAIL_SAFE 应聚合脱敏 BatchMigrationResult")
    void shouldFailFastOnStaticStartupErrorAndAggregateFailuresInFailSafeMode(@TempDir Path tempDir) throws Exception {
        Path badDir = tempDir.resolve("broken");
        Files.createDirectories(badDir);
        Files.writeString(badDir.resolve("V1__invalid.sql"),
                "-- jdbc:mysql://admin:SecretFail99@10.0.0.1:3306/bad?password=PlainFail88\nBAD SQL SYNTAX;",
                StandardCharsets.UTF_8);

        HikariDataSource masterDs = newH2Pool("master_ff");
        HikariDataSource brokenDs = newH2Pool("broken_db");
        Map<String, DataSource> dsMap = new LinkedHashMap<>();
        dsMap.put("master", masterDs);
        dsMap.put("broken_db", brokenDs);
        AtengDynamicRoutingDataSource routingDs = buildRoutingDataSource(dsMap);

        // 1. 启动期默认 staticFailurePolicy=FAIL_FAST，broken_db 失败应立即阻断启动
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> routingDs)
                .withPropertyValues(
                        "spring.flyway.locations=classpath:db/multi/master",
                        "ateng.boot.flyway.datasources.broken_db.locations=filesystem:" + badDir.toAbsolutePath()
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasCauseInstanceOf(FlywayException.class);
                });

        // 2. DISABLED 启动后以 FAIL_SAFE 批量执行，应完成 master 并汇总脱敏后的 broken_db 失败回执
        HikariDataSource masterSafeDs = newH2Pool("master_fs");
        HikariDataSource brokenSafeDs = newH2Pool("broken_fs");
        Map<String, DataSource> safeMap = new LinkedHashMap<>();
        safeMap.put("master", masterSafeDs);
        safeMap.put("broken_db", brokenSafeDs);
        AtengDynamicRoutingDataSource safeRouting = buildRoutingDataSource(safeMap);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> safeRouting)
                .withPropertyValues(
                        "ateng.boot.flyway.mode=DISABLED",
                        "spring.flyway.locations=classpath:db/multi/master",
                        "ateng.boot.flyway.datasources.broken_db.locations=filesystem:" + badDir.toAbsolutePath()
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    BatchMigrationResult batch = template.migrateTargets(BatchExecutionPolicy.FAIL_SAFE);

                    assertThat(batch.getPolicy()).isEqualTo(BatchExecutionPolicy.FAIL_SAFE);
                    assertThat(batch.getTotalTargets()).isEqualTo(2);
                    assertThat(batch.getSuccessCount()).isEqualTo(1);
                    assertThat(batch.getFailedCount()).isEqualTo(1);
                    assertThat(batch.isAllSuccess()).isFalse();
                    assertThat(batch.hasFailures()).isTrue();
                    assertThat(batch.getFailedTargets()).containsExactly("broken_db");
                    assertThat(batch.getTargetDurations()).containsKeys("master", "broken_db");
                    assertThat(batch.getFailures().get(0).getErrorMessage()).doesNotContain("SecretFail99");
                });
    }

    @Test
    @DisplayName("支持自定义 FlywayTargetResolver 与 FlywayLockProvider SPI，持锁失败抛 LOCK_TIMEOUT 且确保释锁")
    void shouldSupportCustomTargetResolverAndLockProviderSpi() {
        HikariDataSource customDs = newH2Pool("custom_spi");
        List<String> lockTrace = new CopyOnWriteArrayList<>();
        AtomicBoolean allowLock = new AtomicBoolean(true);

        FlywayTargetResolver customResolver = () -> List.of(MigrationTarget.builder()
                .targetKey("custom_primary")
                .kind(MigrationTargetKind.PRIMARY)
                .dataSource(customDs)
                .build());

        FlywayLockProvider customLockProvider = new FlywayLockProvider() {
            @Override
            public boolean tryLock(String targetKey, Duration waitTimeout) {
                lockTrace.add("tryLock:" + targetKey);
                return allowLock.get();
            }

            @Override
            public void unlock(String targetKey) {
                lockTrace.add("unlock:" + targetKey);
            }
        };

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> customDs)
                .withBean(FlywayTargetResolver.class, () -> customResolver)
                .withBean(FlywayLockProvider.class, () -> customLockProvider)
                .withPropertyValues("spring.flyway.locations=classpath:db/multi/master")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(lockTrace).containsExactly("tryLock:custom_primary", "unlock:custom_primary");

                    allowLock.set(false);
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    assertThatThrownBy(() -> template.migrate("custom_primary"))
                            .isInstanceOf(FlywayException.class)
                            .satisfies(ex -> assertThat(((FlywayException) ex).getCode())
                                    .isEqualTo(FlywayResultCode.LOCK_TIMEOUT.getCode()));
                });
    }

    @Test
    @DisplayName("未配置 datasources.<dsKey>.locations 时自动回退默认约定目录并支持子集批处理与键去重")
    void shouldSupportDefaultFallbackLocationAndSubsetBatchExecution() {
        HikariDataSource masterDs = newH2Pool("master_fb");
        HikariDataSource autoFallbackDs = newH2Pool("auto_fallback_db");
        Map<String, DataSource> dsMap = new LinkedHashMap<>();
        dsMap.put("master", masterDs);
        dsMap.put("auto_fallback_db", autoFallbackDs);
        AtengDynamicRoutingDataSource routingDs = buildRoutingDataSource(dsMap);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> routingDs)
                .withPropertyValues(
                        "ateng.boot.flyway.mode=DISABLED",
                        "spring.flyway.locations=classpath:db/multi/master",
                        "ateng.boot.flyway.datasources.auto_fallback_db.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);

                    BatchMigrationResult subsetMigrate = template.migrateTargets(List.of("auto_fallback_db", "auto_fallback_db"));
                    assertThat(subsetMigrate.isAllSuccess()).isTrue();
                    assertThat(subsetMigrate.getTotalTargets()).isEqualTo(1);
                    String note = new JdbcTemplate(autoFallbackDs).queryForObject(
                            "SELECT note FROM fallback_audit_item WHERE id = 1", String.class);
                    assertThat(note).isEqualTo("auto-fallback-ok");

                    List<MigrationTargetInfo> infos = template.infoAll(List.of("master", "auto_fallback_db", "master"));
                    assertThat(infos).extracting(MigrationTargetInfo::getTargetKey)
                            .containsExactly("master", "auto_fallback_db");

                    BatchMigrationResult validateSubset = template.validateTargets(List.of("auto_fallback_db"));
                    assertThat(validateSubset.isAllSuccess()).isTrue();
                    assertThat(validateSubset.getTotalTargets()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("校验路径穿越拦截、单数据源独立 URL 目标识别与锁释放异常抑制/脱敏")
    void shouldEnforcePathTraversalSingleDatasourceAndLockSuppressedGuards() {
        assertThatThrownBy(() -> TargetRoutingSupport.sanitizePathSegment("../secret"))
                .isInstanceOf(FlywayException.class);
        assertThatThrownBy(() -> TargetRoutingSupport.sanitizePathSegment("a/b"))
                .isInstanceOf(FlywayException.class);
        assertThatThrownBy(() -> TargetRoutingSupport.sanitizePathSegment(".hidden"))
                .isInstanceOf(FlywayException.class);

        AtengFlywayProperties props = new AtengFlywayProperties();
        AtengFlywayProperties.DataSourceMigrationProperties urlCfg = new AtengFlywayProperties.DataSourceMigrationProperties();
        urlCfg.getCredential().setUsername("sa");
        urlCfg.getCredential().setPassword("");
        urlCfg.getCredential().setUrl("jdbc:h2:mem:standalone_l2;DB_CLOSE_DELAY=-1");
        props.getDatasources().put("standalone_db", urlCfg);
        SingleDataSourceFlywayTargetResolver singleResolver =
                new SingleDataSourceFlywayTargetResolver(props, (DataSource) null);
        List<MigrationTarget> resolved = singleResolver.resolveTargets();
        assertThat(resolved).extracting(MigrationTarget::getTargetKey).containsExactly("standalone_db");
        assertThat(resolved.get(0).getKind()).isEqualTo(MigrationTargetKind.STATIC_DATASOURCE);

        AtengFlywayProperties missingUrlProps = new AtengFlywayProperties();
        missingUrlProps.getDatasources().put("broken_l2", new AtengFlywayProperties.DataSourceMigrationProperties());
        assertThatThrownBy(() -> new SingleDataSourceFlywayTargetResolver(missingUrlProps, (DataSource) null).resolveTargets())
                .isInstanceOf(FlywayException.class)
                .satisfies(ex -> assertThat(((FlywayException) ex).getCode())
                        .isEqualTo(FlywayResultCode.TARGET_NOT_FOUND.getCode()));

        FlywayLockProvider suppressLock = new FlywayLockProvider() {
            @Override
            public boolean tryLock(String targetKey, Duration waitTimeout) {
                return true;
            }

            @Override
            public void unlock(String targetKey) {
                throw new IllegalStateException("redis://:UnlockPass77@127.0.0.1:6379 timeout");
            }
        };
        assertThatThrownBy(() -> suppressLock.executeWithLock("master", Duration.ofSeconds(1), () -> {
            throw new IllegalArgumentException("primary failure");
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("primary failure")
                .satisfies(ex -> assertThat(ex.getSuppressed()).hasSize(1));

        assertThatThrownBy(() -> suppressLock.executeWithLock("master", Duration.ofSeconds(1), () -> "ok"))
                .isInstanceOf(FlywayException.class)
                .satisfies(ex -> {
                    FlywayException fe = (FlywayException) ex;
                    assertThat(fe.getCode()).isEqualTo(FlywayResultCode.LOCK_TIMEOUT.getCode());
                    assertThat(fe.getMessage()).doesNotContain("UnlockPass77");
                });
    }

    @Test
    @DisplayName("应剔除自定义名称只读组与全从库成员分组，且主库禁用无目标时启动不抛 TARGET_NOT_FOUND")
    void shouldExcludeCustomReadOnlyGroupAndAllowAllTargetsDisabledStartup() {
        HikariDataSource masterDs = newH2Pool("custom_ro_master");
        HikariDataSource readPoolDs = newH2Pool("custom_read_pool");
        HikariDataSource memberSlaveDs = newH2Pool("custom_ro_member");
        Map<String, DataSource> dsMap = new LinkedHashMap<>();
        dsMap.put("master", masterDs);
        dsMap.put("read_pool_node", readPoolDs);
        dsMap.put("node_a_slave", memberSlaveDs);

        DatasourceProperties dsProps = new DatasourceProperties();
        dsProps.getReadWriteSplit().setEnabled(true);
        dsProps.getReadWriteSplit().setSlaveDataSource("read_pool");
        DynamicDataSourceProvider provider = () -> dsMap;
        AtengDynamicRoutingDataSource routing = new AtengDynamicRoutingDataSource(
                List.of(provider), dsProps, new DefaultTenantDataSourceProvider(dsProps));
        routing.setPrimary("master");
        routing.afterPropertiesSet();

        GroupDataSource customReadGroup = new GroupDataSource("read_pool", routing.getHealthAwareStrategy());
        customReadGroup.addDatasource("read_pool_node", readPoolDs);
        routing.addGroupDataSource("read_pool", customReadGroup);

        GroupDataSource allSlaveMemberGroup = new GroupDataSource("replica_cluster", routing.getHealthAwareStrategy());
        allSlaveMemberGroup.addDatasource("node_a_slave", memberSlaveDs);
        routing.addGroupDataSource("replica_cluster", allSlaveMemberGroup);

        AtengFlywayProperties flywayProps = new AtengFlywayProperties();
        flywayProps.getDatasources().put("read_pool_node", new AtengFlywayProperties.DataSourceMigrationProperties());
        flywayProps.getDatasources().put("node_a_slave", new AtengFlywayProperties.DataSourceMigrationProperties());
        DynamicDatasourceFlywayTargetResolver resolver = new DynamicDatasourceFlywayTargetResolver(
                flywayProps,
                new org.springframework.boot.autoconfigure.flyway.FlywayProperties(),
                routing,
                dsProps,
                null);
        assertThat(resolver.resolveTargets())
                .extracting(MigrationTarget::getTargetKey)
                .containsExactly("master");

        // 主库显式禁用且无其他纳管库时，启动应平稳返回空摘要而不抛 TARGET_NOT_FOUND
        HikariDataSource disabledMasterDs = newH2Pool("disabled_master");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> disabledMasterDs)
                .withPropertyValues(
                        "spring.flyway.locations=classpath:db/multi/master",
                        "ateng.boot.flyway.datasources.default.enabled=false"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    assertThat(template.getLastMigrationSummary()).isNotNull();
                    assertThat(template.getLastMigrationSummary().getTotalTargets()).isZero();
                });
    }
}

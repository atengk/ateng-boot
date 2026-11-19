package io.github.atengk.boot.flyway.observability;

import com.baomidou.dynamic.datasource.creator.DefaultDataSourceCreator;
import com.baomidou.dynamic.datasource.creator.hikaricp.HikariCpConfig;
import com.baomidou.dynamic.datasource.creator.hikaricp.HikariDataSourceCreator;
import com.baomidou.dynamic.datasource.provider.DynamicDataSourceProvider;
import com.zaxxer.hikari.HikariDataSource;
import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.core.util.EnumUtil;
import io.github.atengk.boot.datasource.guard.DynamicDatasourceGuard;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.datasource.tenant.DefaultTenantDataSourceProvider;
import io.github.atengk.boot.datasource.tenant.TenantDataSourceProvider;
import io.github.atengk.boot.flyway.autoconfigure.AtengFlywayAutoConfiguration;
import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.enums.MigrationTargetKind;
import io.github.atengk.boot.flyway.enums.ObservabilityTargetScope;
import io.github.atengk.boot.flyway.event.FlywayMigrationCompletedEvent;
import io.github.atengk.boot.flyway.event.FlywayMigrationFailedEvent;
import io.github.atengk.boot.flyway.exception.FlywayException;
import io.github.atengk.boot.flyway.listener.FlywayMigrationListener;
import io.github.atengk.boot.flyway.model.MigrationOutcome;
import io.github.atengk.boot.flyway.security.TransientFlywayExecutor;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flyway 领域事件、SPI 监听回调、健康探针与 Micrometer 指标闭环单元测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
class FlywayObservabilityAndEventsTest {

    private final List<HikariDataSource> openedPools = new ArrayList<>();

    @AfterEach
    void cleanupPools() {
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
        ds.setPoolName("obs-" + prefix);
        ds.setJdbcUrl("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUsername("sa");
        ds.setPassword("");
        ds.setDriverClassName("org.h2.Driver");
        openedPools.add(ds);
        return ds;
    }

    private AtengDynamicRoutingDataSource buildRoutingDataSource(Map<String, DataSource> dsMap) {
        DynamicDataSourceProvider provider = () -> dsMap;
        DatasourceProperties dsProps = new DatasourceProperties();
        dsProps.getMultiTenancy().setEnabled(true);
        dsProps.getReadWriteSplit().setEnabled(true);
        AtengDynamicRoutingDataSource routing = new AtengDynamicRoutingDataSource(
                List.of(provider), dsProps, new DefaultTenantDataSourceProvider(dsProps));
        routing.setPrimary("master");
        return routing;
    }

    private ApplicationContextRunner newContextRunner(String dbName) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1;MODE=MySQL",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                );
    }

    @Test
    @DisplayName("迁移成功应广播 CompletedEvent、触发 FlywayMigrationListener、更新健康探针（遵循缓存 TTL）与 Micrometer 指标")
    void shouldPublishCompletedEventInvokeListenerAndExposeCachedHealthAndMetrics() {
        String dbName = "flyway_obs_ok_" + UUID.randomUUID().toString().replace("-", "");
        List<FlywayMigrationCompletedEvent> completedEvents = new CopyOnWriteArrayList<>();
        List<String> listenerTrace = new CopyOnWriteArrayList<>();

        ApplicationListener<FlywayMigrationCompletedEvent> eventCollector = completedEvents::add;
        FlywayMigrationListener customListener = new FlywayMigrationListener() {
            @Override
            public void onBeforeMigrate(String targetKey) {
                listenerTrace.add("before:" + targetKey);
            }

            @Override
            public void onMigrationSucceeded(MigrationOutcome outcome) {
                listenerTrace.add("succeeded:" + outcome.getTargetKey() + ":" + outcome.getMigrationsExecuted());
            }
        };
        FlywayMigrationListener throwingListener = new FlywayMigrationListener() {
            @Override
            public void onBeforeMigrate(String targetKey) {
                throw new IllegalStateException("模拟监听器异常不应阻断主流程");
            }

            @Override
            public void onMigrationSucceeded(MigrationOutcome outcome) {
                throw new IllegalStateException("模拟成功回调异常不应阻断主流程");
            }
        };

        newContextRunner(dbName)
                .withPropertyValues(
                        "spring.flyway.locations=classpath:db/slice1",
                        "ateng.boot.flyway.observability.health-cache-ttl=30s"
                )
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean("completedEventCollector", ApplicationListener.class, () -> eventCollector)
                .withBean("customFlywayListener", FlywayMigrationListener.class, () -> customListener)
                .withBean("throwingFlywayListener", FlywayMigrationListener.class, () -> throwingListener)
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    // 1. 验证领域事件与监听器回调（含异常隔离）
                    assertThat(completedEvents).hasSize(1);
                    assertThat(completedEvents.get(0).getTargetKey()).isEqualTo("default");
                    assertThat(completedEvents.get(0).getOutcome().getMigrationsExecuted()).isEqualTo(1);
                    assertThat(listenerTrace).containsExactly("before:default", "succeeded:default:1");

                    // 2. 验证 FlywayHealthIndicator 状态 UP 及缓存 TTL 防高频击穿
                    FlywayHealthIndicator healthIndicator = context.getBean(FlywayHealthIndicator.class);
                    for (int i = 0; i < 10; i++) {
                        Health h = healthIndicator.health();
                        assertThat(h.getStatus()).isEqualTo(Status.UP);
                        assertThat(h.getDetails()).containsEntry("currentVersion", "1");
                    }
                    assertThat(healthIndicator.getDatabaseProbeCount()).isEqualTo(1L);

                    healthIndicator.invalidateCache();
                    assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.UP);
                    assertThat(healthIndicator.getDatabaseProbeCount()).isEqualTo(2L);

                    // 3. 验证 Micrometer 度量指标与启动补记一致性（绝不重复计数）
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    Counter totalSuccess = registry.find(FlywayMetricsBinder.METRIC_MIGRATIONS_TOTAL)
                            .tag("target", "default")
                            .tag("outcome", "success")
                            .counter();
                    assertThat(totalSuccess).isNotNull();
                    assertThat(totalSuccess.count()).isEqualTo(1.0);

                    Counter executed = registry.find(FlywayMetricsBinder.METRIC_MIGRATIONS_EXECUTED)
                            .tag("target", "default")
                            .counter();
                    assertThat(executed).isNotNull();
                    assertThat(executed.count()).isEqualTo(1.0);

                    Timer durationTimer = registry.find(FlywayMetricsBinder.METRIC_MIGRATION_DURATION)
                            .tag("target", "default")
                            .timer();
                    assertThat(durationTimer).isNotNull();
                    assertThat(durationTimer.count()).isEqualTo(1L);

                    Gauge successGauge = registry.find(FlywayMetricsBinder.METRIC_SUCCESS_TARGETS).gauge();
                    Gauge failedGauge = registry.find(FlywayMetricsBinder.METRIC_FAILED_TARGETS).gauge();
                    Gauge pendingGauge = registry.find(FlywayMetricsBinder.METRIC_MIGRATIONS_PENDING).gauge();
                    assertThat(successGauge).isNotNull();
                    assertThat(successGauge.value()).isEqualTo(1.0);
                    assertThat(failedGauge).isNotNull();
                    assertThat(failedGauge.value()).isZero();
                    assertThat(pendingGauge).isNotNull();

                    FlywayMetricsBinder binder = context.getBean(FlywayMetricsBinder.class);
                    for (int i = 0; i < 5; i++) {
                        assertThat(pendingGauge.value()).isZero();
                    }
                    assertThat(binder.getPendingProbeCount()).isEqualTo(1L);
                });
    }

    @Test
    @DisplayName("迁移失败应广播脱敏 FailedEvent、回调 onMigrationFailed 并使健康探针与失败 Gauge 准确联动")
    void shouldPublishSanitizedFailedEventAndReportDownHealthOnFailure(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("V1__bad.sql"),
                "-- jdbc:mysql://ddl_admin:UltraSecret99@db.prod:3306/app?password=ParamSecret88\nINVALID SQL SYNTAX;",
                StandardCharsets.UTF_8);

        String dbName = "flyway_obs_fail_" + UUID.randomUUID().toString().replace("-", "");
        List<FlywayMigrationFailedEvent> failedEvents = new CopyOnWriteArrayList<>();
        AtomicInteger callbackFailCount = new AtomicInteger(0);

        ApplicationListener<FlywayMigrationFailedEvent> failCollector = failedEvents::add;
        FlywayMigrationListener failListener = new FlywayMigrationListener() {
            @Override
            public void onMigrationFailed(String targetKey, MigrationOutcome outcome, Throwable cause) {
                callbackFailCount.incrementAndGet();
            }
        };

        newContextRunner(dbName)
                .withPropertyValues(
                        "ateng.boot.flyway.mode=DISABLED",
                        "spring.flyway.locations=filesystem:" + tempDir.toAbsolutePath()
                )
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean("failedEventCollector", ApplicationListener.class, () -> failCollector)
                .withBean("failListener", FlywayMigrationListener.class, () -> failListener)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayHealthIndicator healthIndicator = context.getBean(FlywayHealthIndicator.class);
                    assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.UP);
                    assertThat(healthIndicator.health().getDetails()).containsEntry("mode", FlywayExecutionMode.DISABLED.name());

                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    assertThatThrownBy(template::migrate).isInstanceOf(FlywayException.class);

                    assertThat(callbackFailCount.get()).isEqualTo(1);
                    assertThat(failedEvents).hasSize(1);
                    FlywayMigrationFailedEvent event = failedEvents.get(0);
                    assertThat(event.getTargetKey()).isEqualTo("default");
                    assertThat(event.getErrorMessage()).doesNotContain("UltraSecret99");
                    assertThat(event.getErrorMessage()).doesNotContain("ParamSecret88");

                    healthIndicator.invalidateCache();
                    Health downHealth = healthIndicator.health();
                    assertThat(downHealth.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(downHealth.getDetails()).containsEntry("failedCount", 1);

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    Counter failCounter = registry.find(FlywayMetricsBinder.METRIC_MIGRATIONS_TOTAL)
                            .tag("target", "default")
                            .tag("outcome", "failure")
                            .counter();
                    assertThat(failCounter).isNotNull();
                    assertThat(failCounter.count()).isEqualTo(1.0);
                    assertThat(registry.find(FlywayMetricsBinder.METRIC_FAILED_TARGETS).gauge().value()).isEqualTo(1.0);
                });
    }

    @Test
    @DisplayName("当 observability 开关关闭或 healthCacheTtl=0s 时应遵循开关配置")
    void shouldRespectObservabilityTogglesAndZeroCacheTtl() {
        String offDb = "flyway_obs_off_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(offDb)
                .withPropertyValues(
                        "spring.flyway.locations=classpath:db/slice1",
                        "ateng.boot.flyway.observability.health-enabled=false",
                        "ateng.boot.flyway.observability.metrics-enabled=false"
                )
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(FlywayHealthIndicator.class);
                    assertThat(context).doesNotHaveBean(FlywayMetricsBinder.class);
                });

        String zeroTtlDb = "flyway_obs_zero_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(zeroTtlDb)
                .withPropertyValues(
                        "spring.flyway.locations=classpath:db/slice1",
                        "ateng.boot.flyway.observability.health-cache-ttl=0s"
                )
                .run(context -> {
                    FlywayHealthIndicator indicator = context.getBean(FlywayHealthIndicator.class);
                    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
                    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
                    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
                    assertThat(indicator.getDatabaseProbeCount()).isEqualTo(3L);
                });
    }

    @Test
    @DisplayName("隔离 DDL 凭据模式下可观测组件应正常工作且不泄漏瞬态连接，DISABLED/VALIDATE_ONLY 待执行指标与前置异常事件应准确")
    void shouldWorkWithIsolatedCredentialObservabilityAndPreValidationFailure() {
        byte[] sm4Key = CryptoUtil.generateSm4Key();
        byte[] sm4Iv = CryptoUtil.generateIv(CryptoUtil.SM4_IV_SIZE);
        String encPwd = "enc:sm4:" + CryptoUtil.encodeBase64(sm4Key) + ":" + CryptoUtil.encodeBase64(sm4Iv)
                + ":" + CryptoUtil.encryptSm4Cbc("", sm4Key, sm4Iv);

        String disabledDb = "flyway_obs_iso_dis_" + UUID.randomUUID().toString().replace("-", "");
        List<FlywayMigrationFailedEvent> valFailedEvents = new CopyOnWriteArrayList<>();
        newContextRunner(disabledDb)
                .withPropertyValues(
                        "ateng.boot.flyway.mode=DISABLED",
                        "spring.flyway.locations=classpath:db/slice1",
                        "ateng.boot.flyway.credential.username=sa",
                        "ateng.boot.flyway.credential.password=" + encPwd
                )
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    Gauge pendingGauge = registry.find(FlywayMetricsBinder.METRIC_MIGRATIONS_PENDING).gauge();
                    assertThat(pendingGauge).isNotNull();
                    assertThat(pendingGauge.value()).isEqualTo(1.0);

                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    template.migrate();
                    assertThat(pendingGauge.value()).isZero();

                    FlywayHealthIndicator indicator = context.getBean(FlywayHealthIndicator.class);
                    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
                    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
                    assertThat(indicator.getDatabaseProbeCount()).isEqualTo(1L);

                    TransientFlywayExecutor executor = context.getBean(TransientFlywayExecutor.class);
                    assertThat(executor.getActiveTransientPoolCount()).isZero();
                });

        // VALIDATE_ONLY 下前置 FlywayException（如密文解密失败）同样应发布脱敏失败事件
        String badCipherDb = "flyway_obs_iso_bad_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(badCipherDb)
                .withPropertyValues(
                        "ateng.boot.flyway.mode=VALIDATE_ONLY",
                        "spring.flyway.locations=classpath:db/slice1",
                        "ateng.boot.flyway.credential.username=sa",
                        "ateng.boot.flyway.credential.password=enc:sm4:BAD_PAYLOAD_TOKEN"
                )
                .withBean("valFailCollector", ApplicationListener.class,
                        () -> (ApplicationListener<FlywayMigrationFailedEvent>) valFailedEvents::add)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasCauseInstanceOf(FlywayException.class);
                    assertThat(valFailedEvents).hasSize(1);
                    assertThat(valFailedEvents.get(0).getErrorMessage()).doesNotContain("BAD_PAYLOAD_TOKEN");
                });
    }

    @Test
    @DisplayName("MIGRATE 启动完成后再次调用 validate 失败仍应广播 FailedEvent 并回调 onMigrationFailed")
    void shouldEmitFailedEventAndCallbackWhenValidateFailsAfterMigrateStartup(@TempDir Path tempDir) throws Exception {
        Path v1 = tempDir.resolve("V1__validate_event.sql");
        Files.writeString(v1, "CREATE TABLE obs_val_check (id BIGINT PRIMARY KEY);", StandardCharsets.UTF_8);

        String dbName = "flyway_obs_val_after_mig_" + UUID.randomUUID().toString().replace("-", "");
        List<FlywayMigrationFailedEvent> failedEvents = new CopyOnWriteArrayList<>();
        AtomicInteger callbackFailCount = new AtomicInteger(0);

        newContextRunner(dbName)
                .withPropertyValues("spring.flyway.locations=filesystem:" + tempDir.toAbsolutePath())
                .withBean("valFailEventCollector", ApplicationListener.class,
                        () -> (ApplicationListener<FlywayMigrationFailedEvent>) failedEvents::add)
                .withBean("valFailListener", FlywayMigrationListener.class, () -> new FlywayMigrationListener() {
                    @Override
                    public void onMigrationFailed(String targetKey, MigrationOutcome outcome, Throwable cause) {
                        callbackFailCount.incrementAndGet();
                    }
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    assertThat(template.getLastMigrationSummary()).isNotNull();

                    // 篡改已执行脚本触发校验失败
                    Files.writeString(v1, "CREATE TABLE obs_val_check (id BIGINT PRIMARY KEY, note VARCHAR(32));",
                            StandardCharsets.UTF_8);

                    assertThatThrownBy(template::validate)
                            .isInstanceOf(FlywayException.class)
                            .satisfies(ex -> assertThat(((FlywayException) ex).getCode())
                                    .isEqualTo(FlywayResultCode.VALIDATION_FAILED.getCode()));

                    assertThat(callbackFailCount.get()).isEqualTo(1);
                    assertThat(failedEvents).hasSize(1);
                    assertThat(failedEvents.get(0).getMode()).isEqualTo(FlywayExecutionMode.VALIDATE_ONLY);
                });
    }

    @Test
    @DisplayName("ObservabilityTargetScope(PRIMARY/PRIMARY_AND_STATIC/ALL) 与单目标故障隔离应精准控制健康探针及 pending 指标范围")
    @SuppressWarnings("unchecked")
    void shouldRespectObservabilityTargetScopeAndIsolatePerTargetProbeFailure(@TempDir Path tempDir) throws Exception {
        assertThat(ObservabilityTargetScope.PRIMARY).isInstanceOf(IBaseEnum.class);
        assertThat(EnumUtil.fromCode(ObservabilityTargetScope.class, "PRIMARY_AND_STATIC"))
                .contains(ObservabilityTargetScope.PRIMARY_AND_STATIC);

        Path masterDir = tempDir.resolve("master-scope");
        Path staticDir = tempDir.resolve("log-scope");
        Path tenantDir = tempDir.resolve("tenant-scope");
        Files.createDirectories(masterDir);
        Files.createDirectories(staticDir);
        Files.createDirectories(tenantDir);
        Files.writeString(masterDir.resolve("V1__master_init.sql"),
                "CREATE TABLE m_scope (id BIGINT PRIMARY KEY);", StandardCharsets.UTF_8);
        Files.writeString(staticDir.resolve("V1__log_init.sql"),
                "CREATE TABLE l_scope (id BIGINT PRIMARY KEY);", StandardCharsets.UTF_8);
        Files.writeString(tenantDir.resolve("V1__tenant_init.sql"),
                "CREATE TABLE t_scope (id BIGINT PRIMARY KEY);", StandardCharsets.UTF_8);

        HikariDataSource mPrimary = newH2Pool("m_pri");
        HikariDataSource lPrimary = newH2Pool("l_pri");
        HikariDataSource tPrimary = newH2Pool("t_pri");
        Map<String, DataSource> dsMap1 = new LinkedHashMap<>();
        dsMap1.put("master", mPrimary);
        dsMap1.put("log_db", lPrimary);
        dsMap1.put("tenant_1001", tPrimary);
        AtengDynamicRoutingDataSource routing1 = buildRoutingDataSource(dsMap1);

        // 1. 先让 master 完成迁移，保持 log_db 与 tenant_1001 未迁移（各有 1 个 pending 脚本）
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> routing1)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(
                        "ateng.boot.flyway.mode=DISABLED",
                        "ateng.boot.flyway.datasources.master.locations=filesystem:" + masterDir.toAbsolutePath(),
                        "ateng.boot.flyway.datasources.log_db.locations=filesystem:" + staticDir.toAbsolutePath(),
                        "ateng.boot.flyway.tenant.enabled=true",
                        "ateng.boot.flyway.tenant.migrate-on-startup=false",
                        "ateng.boot.flyway.tenant.locations=filesystem:" + tenantDir.toAbsolutePath(),
                        "ateng.boot.flyway.observability.target-scope=PRIMARY",
                        "ateng.boot.flyway.observability.fail-on-pending=false"
                )
                .run(context -> {
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    template.migrate("master");

                    FlywayHealthIndicator indicator = context.getBean(FlywayHealthIndicator.class);
                    Health primaryHealth = indicator.health();
                    assertThat(primaryHealth.getStatus()).isEqualTo(Status.UP);
                    assertThat(primaryHealth.getDetails()).containsEntry("scope", "PRIMARY");
                    assertThat(primaryHealth.getDetails()).containsEntry("pendingCount", 0);
                    Map<String, Object> targets = (Map<String, Object>) primaryHealth.getDetails().get("targets");
                    assertThat(targets).containsOnlyKeys("master");

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find(FlywayMetricsBinder.METRIC_MIGRATIONS_PENDING).gauge().value()).isZero();
                });

        // 2. PRIMARY_AND_STATIC (默认)：汇总 master(0) + log_db(1) = 1 个 pending，排除 tenant_1001；单库 log_db 故障不掩盖 master
        HikariDataSource mStatic = newH2Pool("m_stat");
        HikariDataSource lStatic = newH2Pool("l_stat");
        HikariDataSource tStatic = newH2Pool("t_stat");
        Map<String, DataSource> dsMap2 = new LinkedHashMap<>();
        dsMap2.put("master", mStatic);
        dsMap2.put("log_db", lStatic);
        dsMap2.put("tenant_1001", tStatic);
        AtengDynamicRoutingDataSource routing2 = buildRoutingDataSource(dsMap2);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> routing2)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(
                        "ateng.boot.flyway.mode=DISABLED",
                        "ateng.boot.flyway.datasources.master.locations=filesystem:" + masterDir.toAbsolutePath(),
                        "ateng.boot.flyway.datasources.log_db.locations=filesystem:" + staticDir.toAbsolutePath(),
                        "ateng.boot.flyway.tenant.enabled=true",
                        "ateng.boot.flyway.tenant.migrate-on-startup=false",
                        "ateng.boot.flyway.tenant.locations=filesystem:" + tenantDir.toAbsolutePath()
                )
                .run(context -> {
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    template.migrate("master");

                    FlywayHealthIndicator indicator = context.getBean(FlywayHealthIndicator.class);
                    Health staticHealth = indicator.health();
                    assertThat(staticHealth.getStatus()).isEqualTo(Status.UP);
                    assertThat(staticHealth.getDetails()).containsEntry("scope", "PRIMARY_AND_STATIC");
                    assertThat(staticHealth.getDetails()).containsEntry("pendingCount", 1);
                    Map<String, Object> targets = (Map<String, Object>) staticHealth.getDetails().get("targets");
                    assertThat(targets).containsOnlyKeys("master", "log_db");

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find(FlywayMetricsBinder.METRIC_MIGRATIONS_PENDING).gauge().value()).isEqualTo(1.0);

                    // 模拟 log_db 查询故障：单目标异常隔离上报，master 仍保持 UP 状态可见
                    lStatic.close();
                    indicator.invalidateCache();
                    Health isolatedHealth = indicator.health();
                    assertThat(isolatedHealth.getStatus()).isEqualTo(Status.DOWN);
                    Map<String, Object> isolatedTargets = (Map<String, Object>) isolatedHealth.getDetails().get("targets");
                    assertThat((Map<String, Object>) isolatedTargets.get("master")).containsEntry("status", "UP");
                    assertThat((Map<String, Object>) isolatedTargets.get("log_db")).containsEntry("status", "ERROR");
                });

        // 3. ALL 作用域：汇总 master(0) + log_db(1) + tenant_1001(1) + tenant_1002(1) = 3
        HikariDataSource mAll = newH2Pool("m_all");
        HikariDataSource lAll = newH2Pool("l_all");
        HikariDataSource t1All = newH2Pool("t1_all");
        HikariDataSource t2All = newH2Pool("t2_all");
        Map<String, DataSource> dsMap3 = new LinkedHashMap<>();
        dsMap3.put("master", mAll);
        dsMap3.put("log_db", lAll);
        dsMap3.put("tenant_1001", t1All);
        dsMap3.put("tenant_1002", t2All);
        AtengDynamicRoutingDataSource routing3 = buildRoutingDataSource(dsMap3);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> routing3)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(
                        "ateng.boot.flyway.mode=DISABLED",
                        "ateng.boot.flyway.datasources.master.locations=filesystem:" + masterDir.toAbsolutePath(),
                        "ateng.boot.flyway.datasources.log_db.locations=filesystem:" + staticDir.toAbsolutePath(),
                        "ateng.boot.flyway.tenant.enabled=true",
                        "ateng.boot.flyway.tenant.migrate-on-startup=false",
                        "ateng.boot.flyway.tenant.locations=filesystem:" + tenantDir.toAbsolutePath(),
                        "ateng.boot.flyway.observability.target-scope=ALL"
                )
                .run(context -> {
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    template.migrate("master");

                    FlywayHealthIndicator indicator = context.getBean(FlywayHealthIndicator.class);
                    Health allHealth = indicator.health();
                    assertThat(allHealth.getDetails()).containsEntry("scope", "ALL");
                    assertThat(allHealth.getDetails()).containsEntry("pendingCount", 3);
                    Map<String, Object> targets = (Map<String, Object>) allHealth.getDetails().get("targets");
                    assertThat(targets).containsOnlyKeys("master", "log_db", "tenant_1001", "tenant_1002");

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find(FlywayMetricsBinder.METRIC_MIGRATIONS_PENDING).gauge().value()).isEqualTo(3.0);
                });
    }

    @Test
    @DisplayName("fail-on-pending=true 含 DISABLED 模式有待执行脚本应返回 DOWN；baseline/repair/clean 后 pending 指标无需等 TTL 即时刷新")
    void shouldFailOnPendingInDisabledAndMigrateAndRefreshPendingCacheViaSummaryVersion(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("V1__pending_gate.sql"),
                "CREATE TABLE gate_tbl (id BIGINT PRIMARY KEY);", StandardCharsets.UTF_8);

        // 1. DISABLED + fail-on-pending=true -> DOWN；baseline 后 pending 归 0 且探针立即恢复 UP
        String disGateDb = "flyway_obs_gate_dis_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(disGateDb)
                .withPropertyValues(
                        "ateng.boot.flyway.mode=DISABLED",
                        "spring.flyway.clean-disabled=false",
                        "ateng.boot.flyway.guard.forbid-clean-always=false",
                        "ateng.boot.flyway.guard.forbid-clean-in-protected-profiles=false",
                        "spring.flyway.locations=filesystem:" + tempDir.toAbsolutePath(),
                        "ateng.boot.flyway.observability.health-cache-ttl=60s",
                        "ateng.boot.flyway.observability.fail-on-pending=true"
                )
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayHealthIndicator indicator = context.getBean(FlywayHealthIndicator.class);
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    FlywayMetricsBinder binder = context.getBean(FlywayMetricsBinder.class);
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    Gauge pendingGauge = registry.find(FlywayMetricsBinder.METRIC_MIGRATIONS_PENDING).gauge();

                    // 未基线前存在 1 个 pending 脚本，DISABLED 模式下 fail-on-pending=true 必须降级为 DOWN
                    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
                    assertThat(pendingGauge.value()).isEqualTo(1.0);
                    assertThat(pendingGauge.value()).isEqualTo(1.0);
                    assertThat(binder.getPendingProbeCount()).isEqualTo(1L);

                    // 模拟存量表并执行 baseline -> 无需等待 60s TTL，summaryVersion 递增立即刷新 pending=0 且健康恢复 UP
                    new JdbcTemplate(context.getBean(javax.sql.DataSource.class))
                            .execute("CREATE TABLE gate_tbl (id BIGINT PRIMARY KEY)");
                    template.baseline("default", "1", "gate-base");
                    assertThat(pendingGauge.value()).isZero();
                    assertThat(binder.getPendingProbeCount()).isEqualTo(2L);
                    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

                    // 执行 repair -> summaryVersion 递增同样触发 pendingCache 刷新
                    template.repair("default");
                    assertThat(pendingGauge.value()).isZero();
                    assertThat(binder.getPendingProbeCount()).isEqualTo(3L);

                    // 执行 clean -> pending 立即回升为 1 且健康因 fail-on-pending=true 转回 DOWN
                    template.clean("default");
                    assertThat(pendingGauge.value()).isEqualTo(1.0);
                    assertThat(binder.getPendingProbeCount()).isEqualTo(4L);
                    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
                });

        // 2. MIGRATE 启动跑完 V1 后动态新增待执行 V2 脚本：fail-on-pending=true 转 DOWN，默认 false 保持 UP
        Path dynamicMigDir = tempDir.resolve("dynamic-mig-gate");
        Files.createDirectories(dynamicMigDir);
        Files.writeString(dynamicMigDir.resolve("V1__v1_gate.sql"),
                "CREATE TABLE mig_gate_tbl (id BIGINT PRIMARY KEY);", StandardCharsets.UTF_8);

        String migGateDb = "flyway_obs_gate_mig_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(migGateDb)
                .withPropertyValues(
                        "spring.flyway.locations=filesystem:" + dynamicMigDir.toAbsolutePath(),
                        "ateng.boot.flyway.observability.fail-on-pending=true"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayHealthIndicator indicator = context.getBean(FlywayHealthIndicator.class);
                    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

                    Files.writeString(dynamicMigDir.resolve("V2__v2_gate.sql"),
                            "ALTER TABLE mig_gate_tbl ADD COLUMN note VARCHAR(32);", StandardCharsets.UTF_8);
                    indicator.invalidateCache();
                    Health health = indicator.health();
                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails()).containsEntry("pendingCount", 1);
                });

        // 默认 fail-on-pending=false 时存在 pending 脚本仍保持 UP 向后兼容
        Path dynamicCompatDir = tempDir.resolve("dynamic-compat-gate");
        Files.createDirectories(dynamicCompatDir);
        Files.writeString(dynamicCompatDir.resolve("V1__v1_compat.sql"),
                "CREATE TABLE compat_gate_tbl (id BIGINT PRIMARY KEY);", StandardCharsets.UTF_8);
        String migCompatDb = "flyway_obs_gate_compat_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(migCompatDb)
                .withPropertyValues("spring.flyway.locations=filesystem:" + dynamicCompatDir.toAbsolutePath())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayHealthIndicator indicator = context.getBean(FlywayHealthIndicator.class);
                    Files.writeString(dynamicCompatDir.resolve("V2__v2_compat.sql"),
                            "ALTER TABLE compat_gate_tbl ADD COLUMN note VARCHAR(32);", StandardCharsets.UTF_8);
                    indicator.invalidateCache();
                    Health health = indicator.health();
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).containsEntry("pendingCount", 1);
                });
    }

    @Test
    @DisplayName("aggregate-tenant-metrics=true 应将物理租户指标 target 归一化为 tenant 并附带 kind 标签，关闭时保留原始 tenant_* 键")
    void shouldNormalizeTenantMetricLabelsByDefaultAndAllowRawKeysWhenDisabled(@TempDir Path tempDir) throws Exception {
        Path mDir = tempDir.resolve("m-card");
        Path sDir = tempDir.resolve("s-card");
        Path tDir = tempDir.resolve("t-card");
        Files.createDirectories(mDir);
        Files.createDirectories(sDir);
        Files.createDirectories(tDir);
        Files.writeString(mDir.resolve("V1__m.sql"), "CREATE TABLE m_c (id BIGINT PRIMARY KEY);", StandardCharsets.UTF_8);
        Files.writeString(sDir.resolve("V1__s.sql"), "CREATE TABLE s_c (id BIGINT PRIMARY KEY);", StandardCharsets.UTF_8);
        Files.writeString(tDir.resolve("V1__t.sql"), "CREATE TABLE t_c (id BIGINT PRIMARY KEY);", StandardCharsets.UTF_8);

        // 1. 默认 aggregate-tenant-metrics=true：tenant_1001/1002 归一为 target=tenant, kind=TENANT；master/log_db 保留原名并附 kind
        HikariDataSource m1 = newH2Pool("m_agg");
        HikariDataSource l1 = newH2Pool("l_agg");
        HikariDataSource t1 = newH2Pool("t1_agg");
        HikariDataSource t2 = newH2Pool("t2_agg");
        Map<String, DataSource> dsMap1 = new LinkedHashMap<>();
        dsMap1.put("master", m1);
        dsMap1.put("log_db", l1);
        dsMap1.put("tenant_1001", t1);
        dsMap1.put("tenant_1002", t2);
        AtengDynamicRoutingDataSource routing1 = buildRoutingDataSource(dsMap1);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> routing1)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(
                        "ateng.boot.flyway.datasources.master.locations=filesystem:" + mDir.toAbsolutePath(),
                        "ateng.boot.flyway.datasources.log_db.locations=filesystem:" + sDir.toAbsolutePath(),
                        "ateng.boot.flyway.tenant.enabled=true",
                        "ateng.boot.flyway.tenant.locations=filesystem:" + tDir.toAbsolutePath()
                )
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    Counter tenantTotal = registry.find(FlywayMetricsBinder.METRIC_MIGRATIONS_TOTAL)
                            .tag("target", "tenant")
                            .tag("kind", MigrationTargetKind.TENANT.getCode())
                            .tag("outcome", "success")
                            .counter();
                    assertThat(tenantTotal).isNotNull();
                    assertThat(tenantTotal.count()).isEqualTo(2.0);
                    assertThat(registry.find(FlywayMetricsBinder.METRIC_MIGRATIONS_TOTAL)
                            .tag("target", "tenant_1001").counter()).isNull();

                    Counter tenantExecuted = registry.find(FlywayMetricsBinder.METRIC_MIGRATIONS_EXECUTED)
                            .tag("target", "tenant")
                            .tag("kind", MigrationTargetKind.TENANT.getCode())
                            .counter();
                    assertThat(tenantExecuted).isNotNull();
                    assertThat(tenantExecuted.count()).isEqualTo(2.0);

                    Timer tenantTimer = registry.find(FlywayMetricsBinder.METRIC_MIGRATION_DURATION)
                            .tag("target", "tenant")
                            .tag("kind", MigrationTargetKind.TENANT.getCode())
                            .timer();
                    assertThat(tenantTimer).isNotNull();
                    assertThat(tenantTimer.count()).isEqualTo(2L);

                    assertThat(registry.find(FlywayMetricsBinder.METRIC_MIGRATIONS_TOTAL)
                            .tag("target", "master")
                            .tag("kind", MigrationTargetKind.PRIMARY.getCode())
                            .counter().count()).isEqualTo(1.0);
                    assertThat(registry.find(FlywayMetricsBinder.METRIC_MIGRATIONS_TOTAL)
                            .tag("target", "log_db")
                            .tag("kind", MigrationTargetKind.STATIC_DATASOURCE.getCode())
                            .counter().count()).isEqualTo(1.0);
                });

        // 2. 关闭 aggregate-tenant-metrics=false：保留原始 tenant_1001 / tenant_1002 键名并附 kind=TENANT
        HikariDataSource m2 = newH2Pool("m_raw");
        HikariDataSource t1Raw = newH2Pool("t1_raw");
        HikariDataSource t2Raw = newH2Pool("t2_raw");
        Map<String, DataSource> dsMap2 = new LinkedHashMap<>();
        dsMap2.put("master", m2);
        dsMap2.put("tenant_1001", t1Raw);
        dsMap2.put("tenant_1002", t2Raw);
        AtengDynamicRoutingDataSource routing2 = buildRoutingDataSource(dsMap2);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> routing2)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(
                        "ateng.boot.flyway.datasources.master.locations=filesystem:" + mDir.toAbsolutePath(),
                        "ateng.boot.flyway.tenant.enabled=true",
                        "ateng.boot.flyway.tenant.locations=filesystem:" + tDir.toAbsolutePath(),
                        "ateng.boot.flyway.observability.aggregate-tenant-metrics=false"
                )
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find(FlywayMetricsBinder.METRIC_MIGRATIONS_TOTAL)
                            .tag("target", "tenant_1001")
                            .tag("kind", MigrationTargetKind.TENANT.getCode())
                            .counter().count()).isEqualTo(1.0);
                    assertThat(registry.find(FlywayMetricsBinder.METRIC_MIGRATIONS_TOTAL)
                            .tag("target", "tenant_1002")
                            .tag("kind", MigrationTargetKind.TENANT.getCode())
                            .counter().count()).isEqualTo(1.0);
                });
    }

    @Test
    @DisplayName("onDataSourceRemoved 摘除动态租户库后即使未进入摘要也应通过 summaryVersion 即时刷新 pending 指标与健康探针")
    @SuppressWarnings("unchecked")
    void shouldRefreshPendingMetricAndHealthImmediatelyWhenDynamicTenantRemoved(@TempDir Path tempDir) throws Exception {
        Path mDir = tempDir.resolve("m-rm");
        Path tDir = tempDir.resolve("t-rm");
        Files.createDirectories(mDir);
        Files.createDirectories(tDir);
        Files.writeString(mDir.resolve("V1__m.sql"), "CREATE TABLE m_rm (id BIGINT PRIMARY KEY);", StandardCharsets.UTF_8);
        Files.writeString(tDir.resolve("V1__t.sql"), "CREATE TABLE t_rm (id BIGINT PRIMARY KEY);", StandardCharsets.UTF_8);

        HikariDataSource masterDs = newH2Pool("m_rm_obs");
        Map<String, DataSource> dsMap = new LinkedHashMap<>();
        dsMap.put("master", masterDs);
        AtengDynamicRoutingDataSource routingDs = buildRoutingDataSource(dsMap);

        DatasourceProperties dsProps = new DatasourceProperties();
        dsProps.getMultiTenancy().setEnabled(true);
        dsProps.getReadWriteSplit().setEnabled(true);
        TenantDataSourceProvider tenantProvider = new DefaultTenantDataSourceProvider(dsProps);
        DefaultDataSourceCreator creator = new DefaultDataSourceCreator();
        creator.setCreators(List.of(new HikariDataSourceCreator(new HikariCpConfig())));
        DynamicDatasourceTemplate dsTemplate = new DynamicDatasourceTemplate(
                routingDs, dsProps, tenantProvider, new DynamicDatasourceGuard(dsProps), creator);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withBean(DataSource.class, () -> routingDs)
                .withBean(DatasourceProperties.class, () -> dsProps)
                .withBean(TenantDataSourceProvider.class, () -> tenantProvider)
                .withBean(DynamicDatasourceTemplate.class, () -> dsTemplate)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(
                        "ateng.boot.flyway.datasources.master.locations=filesystem:" + mDir.toAbsolutePath(),
                        "ateng.boot.flyway.tenant.enabled=true",
                        "ateng.boot.flyway.tenant.migrate-on-dynamic-add=false",
                        "ateng.boot.flyway.tenant.locations=filesystem:" + tDir.toAbsolutePath(),
                        "ateng.boot.flyway.observability.target-scope=ALL",
                        "ateng.boot.flyway.observability.health-cache-ttl=60s"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DynamicDatasourceTemplate dynamicTemplate = context.getBean(DynamicDatasourceTemplate.class);
                    FlywayTemplate flywayTemplate = context.getBean(FlywayTemplate.class);
                    FlywayHealthIndicator healthIndicator = context.getBean(FlywayHealthIndicator.class);
                    FlywayMetricsBinder binder = context.getBean(FlywayMetricsBinder.class);
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    Gauge pendingGauge = registry.find(FlywayMetricsBinder.METRIC_MIGRATIONS_PENDING).gauge();

                    // 动态挂载 tenant_1009（migrate-on-dynamic-add=false，故其未写入 MigrationSummary，且含 1 条 pending 脚本）
                    HikariDataSource tenant1009Ds = newH2Pool("tenant_1009_obs");
                    dynamicTemplate.addDataSource("tenant_1009", tenant1009Ds, false);
                    flywayTemplate.removeTargetFromSummary("tenant_1009", true);

                    assertThat(pendingGauge.value()).isEqualTo(1.0);
                    assertThat(((Map<String, Object>) healthIndicator.health().getDetails().get("targets")))
                            .containsKeys("master", "tenant_1009");
                    long probesBeforeRemove = binder.getPendingProbeCount();
                    long healthProbesBeforeRemove = healthIndicator.getDatabaseProbeCount();
                    long summaryVersionBefore = flywayTemplate.getSummaryVersion();

                    // 摘除 tenant_1009：即使目标不在 MigrationSummary 中，onDataSourceRemoved 仍递增 summaryVersion 并即时失效双重缓存
                    dynamicTemplate.removeDataSource("tenant_1009", true);

                    assertThat(flywayTemplate.getSummaryVersion()).isGreaterThan(summaryVersionBefore);
                    assertThat(pendingGauge.value()).isZero();
                    assertThat(binder.getPendingProbeCount()).isGreaterThan(probesBeforeRemove);

                    Health afterRemove = healthIndicator.health();
                    assertThat(afterRemove.getStatus()).isEqualTo(Status.UP);
                    assertThat(((Map<String, Object>) afterRemove.getDetails().get("targets")))
                            .containsOnlyKeys("master");
                    assertThat(healthIndicator.getDatabaseProbeCount()).isGreaterThan(healthProbesBeforeRemove);
                });
    }
}

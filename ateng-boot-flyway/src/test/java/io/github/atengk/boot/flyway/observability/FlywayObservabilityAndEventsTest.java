package io.github.atengk.boot.flyway.observability;

import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.flyway.autoconfigure.AtengFlywayAutoConfiguration;
import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}

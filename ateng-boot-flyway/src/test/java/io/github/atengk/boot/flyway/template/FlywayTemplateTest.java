package io.github.atengk.boot.flyway.template;

import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.util.AsyncUtil;
import io.github.atengk.boot.core.util.EnumUtil;
import io.github.atengk.boot.flyway.autoconfigure.AtengFlywayAutoConfiguration;
import io.github.atengk.boot.flyway.config.FlywayConfigurationSupport;
import io.github.atengk.boot.flyway.enums.BatchExecutionPolicy;
import io.github.atengk.boot.flyway.enums.DynamicMountFailurePolicy;
import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.enums.MigrationTargetKind;
import io.github.atengk.boot.flyway.exception.FlywayException;
import io.github.atengk.boot.flyway.model.BatchOperationResult;
import io.github.atengk.boot.flyway.model.MigrationBaselineResult;
import io.github.atengk.boot.flyway.model.MigrationCleanResult;
import io.github.atengk.boot.flyway.model.MigrationOutcome;
import io.github.atengk.boot.flyway.model.MigrationRepairResult;
import io.github.atengk.boot.flyway.model.MigrationScriptInfo;
import io.github.atengk.boot.flyway.model.MigrationSummary;
import io.github.atengk.boot.flyway.model.MigrationTargetInfo;
import io.github.atengk.boot.flyway.model.MigrationValidationResult;
import io.github.atengk.boot.flyway.observability.FlywayHealthIndicator;
import io.github.atengk.boot.flyway.spi.DefaultFlywayLockProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FlywayTemplate 对称核心门面单元测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
class FlywayTemplateTest {

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
    @DisplayName("pending/info/migrate/validate 生命周期应保持完整对称且绝不返回 null 集合")
    void shouldSupportPendingInfoMigrateAndValidateLifecycle() {
        String dbName = "flyway_s5_life_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(dbName)
                .withPropertyValues(
                        "ateng.boot.flyway.mode=DISABLED",
                        "spring.flyway.locations=classpath:db/slice1"
                )
                .run(context -> {
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);

                    List<MigrationScriptInfo> beforePending = template.pending();
                    assertThat(beforePending).hasSize(1);
                    assertThat(beforePending.get(0).getVersion()).isEqualTo("1");

                    MigrationTargetInfo beforeInfo = template.info();
                    assertThat(beforeInfo.getTargetKey()).isEqualTo("default");
                    assertThat(beforeInfo.getCurrentVersion()).isNull();
                    assertThat(beforeInfo.getAppliedCount()).isZero();
                    assertThat(beforeInfo.getPendingCount()).isEqualTo(1);
                    assertThat(beforeInfo.getFailedCount()).isZero();
                    assertThat(beforeInfo.getScripts()).hasSize(1);

                    MigrationOutcome outcome = template.migrate();
                    assertThat(outcome.isSuccess()).isTrue();
                    assertThat(outcome.getMigrationsExecuted()).isEqualTo(1);
                    assertThat(outcome.getTargetSchemaVersion()).isEqualTo("1");

                    List<MigrationScriptInfo> afterPending = template.pending();
                    assertThat(afterPending).isNotNull().isEmpty();

                    MigrationTargetInfo afterInfo = template.info();
                    assertThat(afterInfo.getCurrentVersion()).isEqualTo("1");
                    assertThat(afterInfo.getAppliedCount()).isEqualTo(1);
                    assertThat(afterInfo.getPendingCount()).isZero();
                    assertThat(afterInfo.getFailedCount()).isZero();
                    assertThat(afterInfo.getScripts()).hasSize(1);
                    MigrationScriptInfo script = afterInfo.getScripts().get(0);
                    assertThat(script.getState()).isEqualTo("SUCCESS");
                    assertThat(script.getChecksum()).isNotNull();

                    MigrationValidationResult validationResult = template.validate();
                    assertThat(validationResult.isValid()).isTrue();
                    assertThat(validationResult.getValidateCount()).isGreaterThanOrEqualTo(1);
                    assertThat(validationResult.getWarnings()).isNotNull();
                });
    }

    @Test
    @DisplayName("存量非空库执行 baseline 后应跳过基线前脚本并仅执行后续增量脚本")
    void shouldBaselineLegacySchemaAndMigrateIncrementalScript(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("V1__legacy_init.sql"),
                "CREATE TABLE legacy_account (id BIGINT PRIMARY KEY, name VARCHAR(64));",
                StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("V2__add_account_status.sql"),
                "ALTER TABLE legacy_account ADD COLUMN status VARCHAR(32) DEFAULT 'ACTIVE';",
                StandardCharsets.UTF_8);

        String dbName = "flyway_s5_base_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(dbName)
                .withPropertyValues(
                        "ateng.boot.flyway.mode=DISABLED",
                        "spring.flyway.locations=filesystem:" + tempDir.toAbsolutePath()
                )
                .run(context -> {
                    DataSource ds = context.getBean(DataSource.class);
                    JdbcTemplate jdbc = new JdbcTemplate(ds);
                    // 模拟存量非空数据库：表已存在但无 flyway_schema_history
                    jdbc.execute("CREATE TABLE legacy_account (id BIGINT PRIMARY KEY, name VARCHAR(64))");
                    jdbc.update("INSERT INTO legacy_account (id, name) VALUES (1, 'legacy-row')");

                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    MigrationBaselineResult baselineResult = template.baseline("default", "1", "legacy-baseline");
                    assertThat(baselineResult.getTargetKey()).isEqualTo("default");
                    assertThat(baselineResult.isSuccessfullyBaselined()).isTrue();
                    assertThat(baselineResult.getBaselineVersion()).isEqualTo("1");

                    MigrationOutcome migrateOutcome = template.migrate();
                    assertThat(migrateOutcome.isSuccess()).isTrue();
                    assertThat(migrateOutcome.getMigrationsExecuted()).isEqualTo(1);
                    assertThat(migrateOutcome.getTargetSchemaVersion()).isEqualTo("2");

                    String status = jdbc.queryForObject(
                            "SELECT status FROM legacy_account WHERE id = 1",
                            String.class
                    );
                    assertThat(status).isEqualTo("ACTIVE");
                });
    }

    @Test
    @DisplayName("脚本校验和漂移后 validate 应失败阻断，经 repair 对齐后再次 validate 应恢复通过")
    void shouldFailValidationOnChecksumDriftAndRecoverAfterRepair(@TempDir Path tempDir) throws Exception {
        Path v1 = tempDir.resolve("V1__drift_table.sql");
        Files.writeString(v1,
                "CREATE TABLE drift_audit (id BIGINT PRIMARY KEY, note VARCHAR(64));",
                StandardCharsets.UTF_8);

        String dbName = "flyway_s5_repair_" + UUID.randomUUID().toString().replace("-", "");
        String location = "filesystem:" + tempDir.toAbsolutePath();

        newContextRunner(dbName)
                .withPropertyValues("spring.flyway.locations=" + location)
                .run(context -> assertThat(context).hasNotFailed());

        // 篡改已执行脚本内容触发 Checksum 漂移
        Files.writeString(v1,
                "CREATE TABLE drift_audit (id BIGINT PRIMARY KEY, note VARCHAR(128));",
                StandardCharsets.UTF_8);

        newContextRunner(dbName)
                .withPropertyValues(
                        "ateng.boot.flyway.mode=DISABLED",
                        "spring.flyway.locations=" + location
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);

                    assertThatThrownBy(template::validate)
                            .isInstanceOf(FlywayException.class)
                            .satisfies(ex -> assertThat(((FlywayException) ex).getCode())
                                    .isEqualTo(FlywayResultCode.VALIDATION_FAILED.getCode()));

                    MigrationRepairResult repairResult = template.repair();
                    assertThat(repairResult.getTargetKey()).isEqualTo("default");
                    assertThat(repairResult.getRepairActions()).isNotNull();
                    assertThat(repairResult.getWarnings()).isNotNull();

                    MigrationValidationResult afterRepair = template.validate();
                    assertThat(afterRepair.isValid()).isTrue();
                });
    }

    @Test
    @DisplayName("非法 SQL 迁移失败时应转译为 FlywayException 并完整保留原始 cause 链")
    void shouldPreserveCauseWhenSqlMigrationFails(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("V1__broken_sql.sql"),
                "THIS IS NOT VALID SQL SYNTAX !!!;",
                StandardCharsets.UTF_8);

        String dbName = "flyway_s5_err_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(dbName)
                .withPropertyValues(
                        "ateng.boot.flyway.mode=DISABLED",
                        "spring.flyway.locations=filesystem:" + tempDir.toAbsolutePath()
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);

                    assertThatThrownBy(template::migrate)
                            .isInstanceOf(FlywayException.class)
                            .hasCauseInstanceOf(org.flywaydb.core.api.FlywayException.class)
                            .satisfies(ex -> assertThat(((FlywayException) ex).getCode())
                                    .isEqualTo(FlywayResultCode.MIGRATION_FAILED.getCode()));
                });
    }

    @Test
    @DisplayName("空 targetKey 应快速失败抛出 IllegalArgumentException，未知 targetKey 应抛出 TARGET_NOT_FOUND")
    void shouldFailFastOnInvalidOrUnknownTargetKey() {
        String dbName = "flyway_s5_key_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(dbName)
                .withPropertyValues(
                        "ateng.boot.flyway.mode=DISABLED",
                        "spring.flyway.locations=classpath:db/slice1"
                )
                .run(context -> {
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);

                    assertThatThrownBy(() -> template.migrate(null)).isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> template.validate("  ")).isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> template.pending("")).isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> template.info(" ")).isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> template.baseline(" ")).isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> template.repair(null)).isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> template.clean("")).isInstanceOf(IllegalArgumentException.class);

                    assertThatThrownBy(() -> template.migrate("unknown"))
                            .isInstanceOf(FlywayException.class)
                            .satisfies(ex -> assertThat(((FlywayException) ex).getCode())
                                    .isEqualTo(FlywayResultCode.TARGET_NOT_FOUND.getCode()));
                    assertThatThrownBy(() -> template.info("unknown"))
                            .isInstanceOf(FlywayException.class)
                            .satisfies(ex -> assertThat(((FlywayException) ex).getCode())
                                    .isEqualTo(FlywayResultCode.TARGET_NOT_FOUND.getCode()));
                });
    }

    @Test
    @DisplayName("异常与摘要文本中的 JDBC 凭据和密钥参数应统一脱敏掩码")
    void shouldSanitizeJdbcCredentialsAndSecretsInMessages() {
        String raw = "Failed to connect jdbc:mysql://admin:SuperSecret123@db.internal:3306/app?user=ddl_admin&username=root_adm&useSSL=false&password=PlainPwd999&token=Tok888";
        String safe = FlywayConfigurationSupport.sanitizeSensitiveText(raw);

        assertThat(safe).contains("jdbc:mysql://admin:******@db.internal:3306/app");
        assertThat(safe).contains("user=******");
        assertThat(safe).contains("username=******");
        assertThat(safe).contains("password=******");
        assertThat(safe).contains("token=******");
        assertThat(safe).doesNotContain("SuperSecret123");
        assertThat(safe).doesNotContain("ddl_admin");
        assertThat(safe).doesNotContain("root_adm");
        assertThat(safe).doesNotContain("PlainPwd999");
        assertThat(safe).doesNotContain("Tok888");

        FlywayException ex = new FlywayException(FlywayResultCode.MIGRATION_FAILED, raw);
        assertThat(ex.getMessage()).doesNotContain("SuperSecret123").doesNotContain("ddl_admin").doesNotContain("PlainPwd999");
    }

    @Test
    @DisplayName("DefaultFlywayLockProvider 应对相同 targetKey 互斥超时抛 LOCK_TIMEOUT，对不同 targetKey 并行放行并在释锁后清理锁条目")
    void shouldEnforceMutexTimeoutPerTargetKeyInDefaultLockProvider() throws Exception {
        DefaultFlywayLockProvider provider = new DefaultFlywayLockProvider();
        CountDownLatch holderEntered = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);

        CompletableFuture<String> holder = CompletableFuture.supplyAsync(() ->
                provider.executeWithLock("master", Duration.ofSeconds(2), () -> {
                    // 验证同线程可重入
                    String nested = provider.executeWithLock("master", Duration.ofMillis(200), () -> "reentrant-ok");
                    assertThat(nested).isEqualTo("reentrant-ok");
                    holderEntered.countDown();
                    try {
                        assertThat(releaseHolder.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "held-ok";
                }), AsyncUtil.getExecutor());

        try {
            assertThat(holderEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(provider.getTrackedLockCount()).isEqualTo(1);

            // 不同 targetKey 应立即并行获锁成功并在释锁后自动回收
            String otherResult = provider.executeWithLock("log_db", Duration.ofMillis(200), () -> "other-ok");
            assertThat(otherResult).isEqualTo("other-ok");
            assertThat(provider.getTrackedLockCount()).isEqualTo(1);

            // 相同 targetKey 在持锁期间应超时抛出 LOCK_TIMEOUT (62008)
            assertThatThrownBy(() -> provider.executeWithLock("master", Duration.ofMillis(80), () -> "should-timeout"))
                    .isInstanceOf(FlywayException.class)
                    .satisfies(err -> assertThat(((FlywayException) err).getCode())
                            .isEqualTo(FlywayResultCode.LOCK_TIMEOUT.getCode()));
            assertThat(provider.getTrackedLockCount()).isEqualTo(1);

            releaseHolder.countDown();
            assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("held-ok");
            assertThat(provider.getTrackedLockCount()).isZero();
        } finally {
            releaseHolder.countDown();
        }
    }

    @Test
    @DisplayName("全部治理枚举应实现 IBaseEnum<String> 并支持 EnumUtil 双向解析与 OptionItem 转换")
    void shouldImplementIBaseEnumContractAcrossAllGovernanceEnums() {
        assertThat(FlywayExecutionMode.VALIDATE_ONLY).isInstanceOf(IBaseEnum.class);
        assertThat(BatchExecutionPolicy.FAIL_SAFE).isInstanceOf(IBaseEnum.class);
        assertThat(DynamicMountFailurePolicy.ROLLBACK_DATASOURCE).isInstanceOf(IBaseEnum.class);
        assertThat(MigrationTargetKind.TENANT).isInstanceOf(IBaseEnum.class);

        assertThat(EnumUtil.fromCode(FlywayExecutionMode.class, "VALIDATE_ONLY"))
                .contains(FlywayExecutionMode.VALIDATE_ONLY);
        assertThat(EnumUtil.getByCodeOrThrow(BatchExecutionPolicy.class, "FAIL_SAFE"))
                .isEqualTo(BatchExecutionPolicy.FAIL_SAFE);
        assertThat(EnumUtil.fromCode(DynamicMountFailurePolicy.class, "ROLLBACK_DATASOURCE"))
                .contains(DynamicMountFailurePolicy.ROLLBACK_DATASOURCE);
        assertThat(EnumUtil.fromCode(MigrationTargetKind.class, "TENANT"))
                .contains(MigrationTargetKind.TENANT);

        assertThat(FlywayExecutionMode.MIGRATE.toOption().getCode()).isEqualTo("MIGRATE");
        assertThat(FlywayExecutionMode.MIGRATE.toOption().getLabel()).isNotBlank();
    }

    @Test
    @DisplayName("单库与显式集合对称批处理门面及 BatchOperationResult 不可变性、Fail-Fast 空参拦截与 FAIL_SAFE 容错校验")
    void shouldSupportGenericBatchOperationsImmutabilityFailFastAndFailSafe() {
        String dbName = "flyway_s5_batch_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(dbName)
                .withPropertyValues(
                        "ateng.boot.flyway.mode=DISABLED",
                        "spring.flyway.locations=classpath:db/slice1"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);

                    // 1. 合法对称批量调用与不可变契约校验
                    BatchOperationResult<List<MigrationScriptInfo>> pendingBatch = template.pendingTargets();
                    assertThat(pendingBatch.isAllSuccess()).isTrue();
                    assertThat(pendingBatch.hasFailures()).isFalse();
                    assertThat(pendingBatch.getTotalTargets()).isEqualTo(1);
                    assertThat(pendingBatch.getSuccessCount()).isEqualTo(1);
                    assertThat(pendingBatch.getFailedTargets()).isEmpty();
                    assertThat(pendingBatch.getResults().get("default")).hasSize(1);
                    assertThat(pendingBatch.getTargetDurations()).containsKey("default");
                    assertThatThrownBy(() -> pendingBatch.getResults().put("x", Collections.emptyList()))
                            .isInstanceOf(UnsupportedOperationException.class);
                    assertThatThrownBy(() -> pendingBatch.getTargetDurations().put("x", Duration.ZERO))
                            .isInstanceOf(UnsupportedOperationException.class);

                    BatchOperationResult<MigrationTargetInfo> infoBatch = template.infoAll(BatchExecutionPolicy.FAIL_SAFE);
                    assertThat(infoBatch.isAllSuccess()).isTrue();
                    assertThat(infoBatch.getResults().get("default").getPendingCount()).isEqualTo(1);

                    // 原有 infoAll() 向后兼容保留
                    List<MigrationTargetInfo> legacyInfoAll = template.infoAll();
                    assertThat(legacyInfoAll).hasSize(1);
                    assertThat(legacyInfoAll.get(0).getTargetKey()).isEqualTo("default");

                    // 2. 空 targetKeys 或含空白/null 元素必须前置 Fail-Fast 抛 IllegalArgumentException
                    assertThatThrownBy(() -> template.pendingTargets(Collections.emptyList()))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> template.repairTargets(Arrays.asList("default", " ")))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> template.baselineTargets(Collections.singletonList((String) null)))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> template.infoTargets(Collections.emptyList(), BatchExecutionPolicy.FAIL_SAFE))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> template.infoAll(Arrays.asList("default", ""), BatchExecutionPolicy.FAIL_SAFE))
                            .isInstanceOf(IllegalArgumentException.class);

                    // 3. 混合未知目标：FAIL_FAST 立即抛出 TARGET_NOT_FOUND (62004)，FAIL_SAFE 收集局部成功与脱敏失败
                    List<String> mixedKeys = List.of("default", "unknown_target");
                    assertThatThrownBy(() -> template.infoTargets(mixedKeys, BatchExecutionPolicy.FAIL_FAST))
                            .isInstanceOf(FlywayException.class)
                            .satisfies(ex -> assertThat(((FlywayException) ex).getCode())
                                    .isEqualTo(FlywayResultCode.TARGET_NOT_FOUND.getCode()));

                    BatchOperationResult<MigrationTargetInfo> partialInfo =
                            template.infoTargets(mixedKeys, BatchExecutionPolicy.FAIL_SAFE);
                    assertThat(partialInfo.isAllSuccess()).isFalse();
                    assertThat(partialInfo.hasFailures()).isTrue();
                    assertThat(partialInfo.getTotalTargets()).isEqualTo(2);
                    assertThat(partialInfo.getSuccessCount()).isEqualTo(1);
                    assertThat(partialInfo.getFailedCount()).isEqualTo(1);
                    assertThat(partialInfo.getResults()).containsOnlyKeys("default");
                    assertThat(partialInfo.getFailedTargets()).containsExactly("unknown_target");
                    assertThat(partialInfo.getFailures().get(0).getErrorCode())
                            .isEqualTo(FlywayResultCode.TARGET_NOT_FOUND.getCode());
                    assertThat(partialInfo.getTargetDurations()).containsKeys("default", "unknown_target");
                });
    }

    @Test
    @DisplayName("baseline/repair/clean 均应递增 summaryVersion，且 clean 后摘要清退不再虚报为已迁移成功")
    void shouldSyncSummaryAndIncrementVersionAcrossBaselineRepairAndClean(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("V1__legacy.sql"),
                "CREATE TABLE legacy_tbl (id BIGINT PRIMARY KEY);",
                StandardCharsets.UTF_8);

        String dbName = "flyway_s5_clean_sync_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(dbName)
                .withPropertyValues(
                        "ateng.boot.flyway.mode=DISABLED",
                        "spring.flyway.clean-disabled=false",
                        "ateng.boot.flyway.guard.forbid-clean-always=false",
                        "ateng.boot.flyway.guard.forbid-clean-in-protected-profiles=false",
                        "spring.flyway.locations=filesystem:" + tempDir.toAbsolutePath()
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    FlywayHealthIndicator healthIndicator = context.getBean(FlywayHealthIndicator.class);
                    DataSource ds = context.getBean(DataSource.class);
                    new JdbcTemplate(ds).execute("CREATE TABLE legacy_tbl (id BIGINT PRIMARY KEY)");

                    // 1. baseline 后 summaryVersion 递增且摘要记录当前基线版本
                    long v0 = template.getSummaryVersion();
                    MigrationBaselineResult baselineRes = template.baseline("default", "1", "init-baseline");
                    assertThat(baselineRes.isSuccessfullyBaselined()).isTrue();
                    long v1 = template.getSummaryVersion();
                    assertThat(v1).isGreaterThan(v0);
                    MigrationSummary afterBaseline = template.getLastMigrationSummary();
                    assertThat(afterBaseline.getSuccessCount()).isEqualTo(1);
                    assertThat(afterBaseline.getOutcomes().get(0).getTargetSchemaVersion()).isEqualTo("1");

                    // 2. repair 后 summaryVersion 递增
                    MigrationRepairResult repairRes = template.repair("default");
                    assertThat(repairRes.getTargetKey()).isEqualTo("default");
                    long v2 = template.getSummaryVersion();
                    assertThat(v2).isGreaterThan(v1);

                    // 3. 先建立健康缓存 UP，再执行 clean，应递增 summaryVersion、清除已迁移虚报并立即刷新健康探针
                    assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.UP);
                    long probesBeforeClean = healthIndicator.getDatabaseProbeCount();

                    MigrationCleanResult cleanRes = template.clean("default");
                    assertThat(cleanRes.getTargetKey()).isEqualTo("default");
                    long v3 = template.getSummaryVersion();
                    assertThat(v3).isGreaterThan(v2);

                    MigrationSummary afterClean = template.getLastMigrationSummary();
                    assertThat(afterClean.getTotalTargets()).isZero();
                    assertThat(afterClean.getSuccessCount()).isZero();
                    assertThat(afterClean.getOutcomes()).isEmpty();

                    // clean 后 healthIndicator 应因 summaryVersion 变更立即触发底层探查而非命中陈旧缓存
                    assertThat(healthIndicator.health().getDetails().get("pendingCount")).isEqualTo(1);
                    assertThat(healthIndicator.getDatabaseProbeCount()).isGreaterThan(probesBeforeClean);
                });
    }
}

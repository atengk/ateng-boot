package io.github.atengk.boot.flyway.template;

import io.github.atengk.boot.flyway.autoconfigure.AtengFlywayAutoConfiguration;
import io.github.atengk.boot.flyway.config.FlywayConfigurationSupport;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.exception.FlywayException;
import io.github.atengk.boot.flyway.model.MigrationBaselineResult;
import io.github.atengk.boot.flyway.model.MigrationOutcome;
import io.github.atengk.boot.flyway.model.MigrationRepairResult;
import io.github.atengk.boot.flyway.model.MigrationScriptInfo;
import io.github.atengk.boot.flyway.model.MigrationTargetInfo;
import io.github.atengk.boot.flyway.model.MigrationValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

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
        String raw = "Failed to connect jdbc:mysql://admin:SuperSecret123@db.internal:3306/app?useSSL=false&password=PlainPwd999&token=Tok888";
        String safe = FlywayConfigurationSupport.sanitizeSensitiveText(raw);

        assertThat(safe).contains("jdbc:mysql://admin:******@db.internal:3306/app");
        assertThat(safe).contains("password=******");
        assertThat(safe).contains("token=******");
        assertThat(safe).doesNotContain("SuperSecret123");
        assertThat(safe).doesNotContain("PlainPwd999");
        assertThat(safe).doesNotContain("Tok888");
    }
}

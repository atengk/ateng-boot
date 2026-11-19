package io.github.atengk.boot.flyway.guard;

import io.github.atengk.boot.flyway.autoconfigure.AtengFlywayAutoConfiguration;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.exception.FlywayException;
import io.github.atengk.boot.flyway.model.MigrationCleanResult;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;

import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生产防误删硬守卫单元测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
class ProductionSafetyGuardTest {

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
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.flyway.locations=classpath:db/slice1"
                );
    }

    @Test
    @DisplayName("受保护环境（含大小写变体及自定义 Profile）即使开启 clean-disabled=false 或 Customizer 覆盖也必须阻断 clean")
    void shouldBlockCleanInProtectedProfilesEvenWhenCleanEnabled() {
        for (String profile : new String[]{"prod", "PRD", "production", "staging"}) {
            String dbName = "flyway_s3_prod_" + UUID.randomUUID().toString().replace("-", "");
            ApplicationContextRunner runner = newContextRunner(dbName)
                    .withPropertyValues(
                            "spring.profiles.active=" + profile,
                            "spring.flyway.clean-disabled=false"
                    )
                    .withBean("unsafeCustomizer", FlywayConfigurationCustomizer.class,
                            () -> configuration -> configuration.cleanDisabled(false));
            if ("staging".equals(profile)) {
                runner = runner.withPropertyValues("ateng.boot.flyway.guard.protected-profiles=prod,staging");
            }
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                ProductionSafetyGuard guard = context.getBean(ProductionSafetyGuard.class);
                assertThat(guard.isProtectedEnvironment()).isTrue();

                FlywayTemplate template = context.getBean(FlywayTemplate.class);
                assertThatThrownBy(template::clean)
                        .isInstanceOf(FlywayException.class)
                        .satisfies(ex -> assertThat(((FlywayException) ex).getCode())
                                .isEqualTo(FlywayResultCode.CLEAN_FORBIDDEN.getCode()));
            });
        }
    }

    @Test
    @DisplayName("受保护环境下开启 spring.flyway.clean-on-validation-error=true 启动阶段应 Fail-Fast 阻断")
    void shouldFailFastWhenCleanOnValidationErrorEnabledInProd() {
        String dbName = "flyway_s3_cove_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(dbName)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "spring.flyway.clean-on-validation-error=true"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(FlywayException.class);
                    assertThat(extractRootFlywayCode(context.getStartupFailure()))
                            .isEqualTo(FlywayResultCode.CLEAN_FORBIDDEN.getCode());
                });
    }

    @Test
    @DisplayName("开启 forbid-clean-always=true 时在 dev 环境下也应禁止 clean")
    void shouldBlockCleanInDevWhenForbidCleanAlwaysEnabled() {
        String dbName = "flyway_s3_always_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(dbName)
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "spring.flyway.clean-disabled=false",
                        "ateng.boot.flyway.guard.forbid-clean-always=true"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    assertThatThrownBy(template::clean)
                            .isInstanceOf(FlywayException.class)
                            .satisfies(ex -> assertThat(((FlywayException) ex).getCode())
                                    .isEqualTo(FlywayResultCode.CLEAN_FORBIDDEN.getCode()));
                });
    }

    @Test
    @DisplayName("dev 环境默认 clean-disabled=true 禁止 clean，显式配置 clean-disabled=false 时允许清理并清理表结构")
    void shouldRespectCleanDisabledSettingInNonProtectedProfile() {
        String defaultDb = "flyway_s3_dev_def_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(defaultDb)
                .withPropertyValues("spring.profiles.active=dev")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    assertThatThrownBy(template::clean)
                            .isInstanceOf(FlywayException.class)
                            .satisfies(ex -> assertThat(((FlywayException) ex).getCode())
                                    .isEqualTo(FlywayResultCode.CLEAN_FORBIDDEN.getCode()));
                });

        String cleanAllowedDb = "flyway_s3_dev_ok_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(cleanAllowedDb)
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "spring.flyway.clean-disabled=false"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    MigrationCleanResult result = template.clean();
                    assertThat(result.getTargetKey()).isEqualTo("default");
                    assertThat(result.getSchemasCleaned()).isNotEmpty();

                    DataSource ds = context.getBean(DataSource.class);
                    try (Connection conn = ds.getConnection();
                         var rs = conn.getMetaData().getTables(null, null, "SAMPLE_USER", null)) {
                        assertThat(rs.next()).isFalse();
                    }
                });
    }

    @Test
    @DisplayName("多 Profile 混合配置中命中受保护 Profile 时仍必须物理阻断 clean")
    void shouldBlockCleanWhenMultipleProfilesContainProtectedOne() {
        String dbName = "flyway_s6_multi_prof_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(dbName)
                .withPropertyValues(
                        "spring.profiles.active=mysql,PROD",
                        "spring.flyway.clean-disabled=false"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ProductionSafetyGuard.class).isProtectedEnvironment()).isTrue();
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    assertThatThrownBy(template::clean)
                            .isInstanceOf(FlywayException.class)
                            .satisfies(ex -> assertThat(((FlywayException) ex).getCode())
                                    .isEqualTo(FlywayResultCode.CLEAN_FORBIDDEN.getCode()));
                });
    }

    @Test
    @DisplayName("guard.enabled=false 或单独放行 forbid-clean-on-validation-error=false 时应按细粒度策略放行")
    void shouldRespectGranularGuardToggles() {
        String guardOffDb = "flyway_s6_guard_off_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(guardOffDb)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "spring.flyway.clean-disabled=false",
                        "ateng.boot.flyway.guard.enabled=false"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ProductionSafetyGuard.class).isProtectedEnvironment()).isFalse();
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    assertThat(template.clean().getSchemasCleaned()).isNotEmpty();
                });

        String validationCleanDb = "flyway_s6_val_clean_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(validationCleanDb)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "spring.flyway.clean-on-validation-error=true",
                        "ateng.boot.flyway.guard.forbid-clean-on-validation-error=false"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("受保护环境下待执行 SQL 含 DROP TABLE 时应 Fail-Fast 阻断（62010）且不执行脚本")
    void shouldBlockPendingDropTableInProtectedProfile(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("V1__init.sql"),
                "CREATE TABLE safe_table (id BIGINT PRIMARY KEY);",
                StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("V2__danger_drop.sql"),
                "DROP TABLE safe_table;",
                StandardCharsets.UTF_8);

        String dbName = "flyway_ddl_drop_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(dbName)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "spring.flyway.locations=filesystem:" + tempDir.toAbsolutePath()
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(FlywayException.class);
                    assertThat(extractRootFlywayCode(context.getStartupFailure()))
                            .isEqualTo(FlywayResultCode.DESTRUCTIVE_DDL_FORBIDDEN.getCode());
                });
    }

    @Test
    @DisplayName("应剥离注释与字符串字面量防误报、识破注释分割绕过、跳过已应用脚本并支持 DROP DATABASE/SCHEMA/TRUNCATE 拦截")
    void shouldHandleCommentsLiteralsAppliedHistoryAndAllDestructiveStatements(@TempDir Path tempDir) throws Exception {
        Path safeDir = tempDir.resolve("safe");
        Files.createDirectories(safeDir);
        Files.writeString(safeDir.resolve("V1__safe_with_comments_and_literals.sql"),
                """
                -- 历史说明：禁止 DROP TABLE 和 TRUNCATE TABLE
                /* 块注释内的 DROP DATABASE 和 DROP SCHEMA */
                CREATE TABLE audit_notice (id BIGINT PRIMARY KEY, note VARCHAR(200));
                INSERT INTO audit_notice (id, note)
                VALUES (1, 'DROP TABLE audit_notice; TRUNCATE audit_notice; -- @ateng-allow-destructive-ddl');
                """,
                StandardCharsets.UTF_8);

        String safeDb = "flyway_ddl_safe_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(safeDb)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "spring.flyway.locations=filesystem:" + safeDir.toAbsolutePath()
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Integer cnt = new JdbcTemplate(context.getBean(DataSource.class))
                            .queryForObject("SELECT COUNT(*) FROM audit_notice", Integer.class);
                    assertThat(cnt).isEqualTo(1);
                });

        // 已在 dev 历史执行过的含 DROP TABLE 脚本，后续在 prod 增量发布 V2 时不应被回溯阻断
        Path legacyDir = tempDir.resolve("legacy");
        Files.createDirectories(legacyDir);
        Files.writeString(legacyDir.resolve("V1__legacy_cleanup.sql"),
                "CREATE TABLE tmp_legacy (id BIGINT PRIMARY KEY); DROP TABLE tmp_legacy;",
                StandardCharsets.UTF_8);
        String legacyDb = "flyway_ddl_legacy_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(legacyDb)
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "spring.flyway.locations=filesystem:" + legacyDir.toAbsolutePath()
                )
                .run(context -> assertThat(context).hasNotFailed());

        Files.writeString(legacyDir.resolve("V2__add_prod_table.sql"),
                "CREATE TABLE prod_feature (id BIGINT PRIMARY KEY);",
                StandardCharsets.UTF_8);
        newContextRunner(legacyDb)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "spring.flyway.locations=filesystem:" + legacyDir.toAbsolutePath()
                )
                .run(context -> assertThat(context).hasNotFailed());

        // 拦截注释分割绕过、TRUNCATE、DROP SCHEMA、DROP DATABASE、反斜杠路径字面量绕过以及字符串伪造豁免
        String[] destructiveSqls = {
                "CREATE TABLE t_obf (id BIGINT); DROP /* bypass */ TABLE t_obf;",
                "CREATE TABLE t_trunc (id BIGINT); TRUNCATE TABLE t_trunc;",
                "DROP SCHEMA IF EXISTS legacy_schema;",
                "DROP DATABASE IF EXISTS legacy_db;",
                "INSERT INTO t_path VALUES ('C:\\\\'); DROP TABLE t_path;",
                "INSERT INTO t_fake VALUES ('-- @ateng-allow-destructive-ddl'); DROP TABLE t_fake;"
        };
        for (int i = 0; i < destructiveSqls.length; i++) {
            Path dir = tempDir.resolve("bad_" + i);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("V1__bad.sql"), destructiveSqls[i], StandardCharsets.UTF_8);
            String badDb = "flyway_ddl_bad_" + i + "_" + UUID.randomUUID().toString().replace("-", "");
            newContextRunner(badDb)
                    .withPropertyValues(
                            "spring.profiles.active=prod",
                            "spring.flyway.locations=filesystem:" + dir.toAbsolutePath()
                    )
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(FlywayException.class);
                        assertThat(extractRootFlywayCode(context.getStartupFailure()))
                                .isEqualTo(FlywayResultCode.DESTRUCTIVE_DDL_FORBIDDEN.getCode());
                    });
        }
    }

    @Test
    @DisplayName("应支持 @ateng-allow-destructive-ddl 豁免开关控制及 forbid-destructive-ddl-always 全环境拦截")
    void shouldRespectExemptionAnnotationAndAlwaysForbidToggle(@TempDir Path tempDir) throws Exception {
        Path exemptDir = tempDir.resolve("exempt");
        Files.createDirectories(exemptDir);
        Files.writeString(exemptDir.resolve("V1__exempt_drop.sql"),
                """
                -- @ateng-allow-destructive-ddl
                CREATE TABLE temp_stage (id BIGINT PRIMARY KEY);
                DROP TABLE temp_stage;
                """,
                StandardCharsets.UTF_8);

        // 1. 默认允许显式豁免注释在 prod 放行
        String allowedDb = "flyway_ddl_ex_ok_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(allowedDb)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "spring.flyway.locations=filesystem:" + exemptDir.toAbsolutePath()
                )
                .run(context -> assertThat(context).hasNotFailed());

        // 2. 关闭 allow-destructive-ddl-annotation=false 后强制拦截
        String blockedDb = "flyway_ddl_ex_no_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(blockedDb)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "ateng.boot.flyway.guard.allow-destructive-ddl-annotation=false",
                        "spring.flyway.locations=filesystem:" + exemptDir.toAbsolutePath()
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(extractRootFlywayCode(context.getStartupFailure()))
                            .isEqualTo(FlywayResultCode.DESTRUCTIVE_DDL_FORBIDDEN.getCode());
                });

        // 3. dev 环境默认放行，开启 forbid-destructive-ddl-always=true 后同样阻断
        Path devDropDir = tempDir.resolve("dev_drop");
        Files.createDirectories(devDropDir);
        Files.writeString(devDropDir.resolve("V1__dev_drop.sql"),
                "CREATE TABLE dev_tmp (id INT); DROP TABLE dev_tmp;",
                StandardCharsets.UTF_8);

        String devAllowedDb = "flyway_ddl_dev_ok_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(devAllowedDb)
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "spring.flyway.locations=filesystem:" + devDropDir.toAbsolutePath()
                )
                .run(context -> assertThat(context).hasNotFailed());

        String devAlwaysDb = "flyway_ddl_dev_blk_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(devAlwaysDb)
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "ateng.boot.flyway.guard.forbid-destructive-ddl-always=true",
                        "spring.flyway.locations=filesystem:" + devDropDir.toAbsolutePath()
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(extractRootFlywayCode(context.getStartupFailure()))
                            .isEqualTo(FlywayResultCode.DESTRUCTIVE_DDL_FORBIDDEN.getCode());
                });
    }

    @Test
    @DisplayName("受保护环境下 Customizer 后置违规开启 cleanOnValidationError(true) 应 Fail-Fast 抛出 CLEAN_FORBIDDEN")
    void shouldFailFastWhenCustomizerEnablesCleanOnValidationErrorInProtectedProfile() {
        String dbName = "flyway_cust_cove_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(dbName)
                .withPropertyValues("spring.profiles.active=prod")
                .withBean("unsafeCleanOnValidationCustomizer", FlywayConfigurationCustomizer.class,
                        () -> configuration -> configuration.cleanOnValidationError(true))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(FlywayException.class);
                    assertThat(extractRootFlywayCode(context.getStartupFailure()))
                            .isEqualTo(FlywayResultCode.CLEAN_FORBIDDEN.getCode());
                });
    }

    private Integer extractRootFlywayCode(Throwable startupFailure) {
        Throwable root = startupFailure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(FlywayException.class);
        return ((FlywayException) root).getCode();
    }
}

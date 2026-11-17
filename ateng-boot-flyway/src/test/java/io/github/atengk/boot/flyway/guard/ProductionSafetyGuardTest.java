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
import java.sql.Connection;
import java.util.UUID;

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
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(FlywayException.class);
                    Throwable root = context.getStartupFailure();
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertThat(((FlywayException) root).getCode())
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
}

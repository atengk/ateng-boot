package io.github.atengk.boot.flyway.autoconfigure;

import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.exception.FlywayException;
import io.github.atengk.boot.flyway.model.MigrationOutcome;
import io.github.atengk.boot.flyway.model.MigrationSummary;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.JavaMigration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flyway 自动装配与启动接管集成测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
class AtengFlywayAutoConfigurationTest {

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
    @DisplayName("默认配置应自动接管启动迁移、建表并记录执行摘要")
    void shouldMigrateSingleDataSourceOnStartup() {
        String dbName = "flyway_s1_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(dbName)
                .withPropertyValues("spring.flyway.locations=classpath:db/slice1")
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengFlywayProperties.class);
                    assertThat(context).hasSingleBean(FlywayTemplate.class);
                    assertThat(context).hasSingleBean(Flyway.class);
                    assertThat(context).hasSingleBean(FlywayMigrationInitializer.class);
                    assertThat(context.getBean(FlywayMigrationInitializer.class))
                            .isInstanceOf(AtengFlywayMigrationInitializer.class);

                    AtengFlywayProperties properties = context.getBean(AtengFlywayProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getMode()).isEqualTo(FlywayExecutionMode.MIGRATE);

                    JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
                    Integer count = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM sample_user WHERE username = 'ateng'",
                            Integer.class
                    );
                    assertThat(count).isEqualTo(1);

                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    MigrationSummary summary = template.getLastMigrationSummary();
                    assertThat(summary).isNotNull();
                    assertThat(summary.getTotalTargets()).isEqualTo(1);
                    assertThat(summary.getSuccessCount()).isEqualTo(1);
                    assertThat(summary.getFailedCount()).isZero();
                });
    }

    @Test
    @DisplayName("当 ateng.boot.flyway.enabled=false 时应退让给 Spring Boot 原生 FlywayAutoConfiguration")
    void shouldBackOffWhenDisabled() {
        String dbName = "flyway_s1_off_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(dbName)
                .withPropertyValues(
                        "ateng.boot.flyway.enabled=false",
                        "spring.flyway.locations=classpath:db/slice1"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengFlywayAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(FlywayTemplate.class);
                    assertThat(context).hasSingleBean(Flyway.class);
                    assertThat(context).hasSingleBean(FlywayMigrationInitializer.class);
                    assertThat(context.getBean(FlywayMigrationInitializer.class))
                            .isNotInstanceOf(AtengFlywayMigrationInitializer.class);

                    JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
                    Integer count = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM sample_user WHERE username = 'ateng'",
                            Integer.class
                    );
                    assertThat(count).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("VALIDATE_ONLY 模式下未迁移库启动应 Fail-Fast 阻断且不建表，已迁移库应只读校验通过")
    void shouldValidateOnlyWithoutExecutingDdl() throws Exception {
        String dbName = "flyway_s2_val_" + UUID.randomUUID().toString().replace("-", "");
        // 1. 未迁移库在 VALIDATE_ONLY 下应启动失败且抛出 VALIDATION_FAILED
        newContextRunner(dbName)
                .withPropertyValues(
                        "ateng.boot.flyway.mode=VALIDATE_ONLY",
                        "spring.flyway.locations=classpath:db/slice1"
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
                            .isEqualTo(FlywayResultCode.VALIDATION_FAILED.getCode());
                });

        // 2. 先使用 V1 迁移完成，再使用 VALIDATE_ONLY 启动应通过且不应用新增的 V2 脚本
        String preMigratedDb = "flyway_s2_val_ok_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(preMigratedDb)
                .withPropertyValues("spring.flyway.locations=classpath:db/slice1")
                .run(context -> assertThat(context).hasNotFailed());

        newContextRunner(preMigratedDb)
                .withPropertyValues(
                        "ateng.boot.flyway.mode=VALIDATE_ONLY",
                        "spring.flyway.locations=classpath:db/slice1"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    MigrationSummary summary = template.getLastMigrationSummary();
                    assertThat(summary).isNotNull();
                    assertThat(summary.getMode()).isEqualTo(FlywayExecutionMode.VALIDATE_ONLY);
                    assertThat(summary.getSuccessCount()).isEqualTo(1);
                });

        // 3. 若出现待执行新脚本 V2，VALIDATE_ONLY 不应自动执行建表且应校验失败
        newContextRunner(preMigratedDb)
                .withPropertyValues(
                        "ateng.boot.flyway.mode=VALIDATE_ONLY",
                        "spring.flyway.locations=classpath:db/slice1,classpath:db/slice2"
                )
                .run(context -> assertThat(context).hasFailed());

        try (Connection conn = DriverManager.getConnection(
                "jdbc:h2:mem:" + preMigratedDb + ";DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
             var rs = conn.getMetaData().getTables(null, null, "SAMPLE_ORDER", null)) {
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    @DisplayName("DISABLED 模式下应跳过启动迁移并允许随后手动调用 FlywayTemplate.migrate")
    void shouldSkipStartupMigrationWhenModeDisabled() {
        String dbName = "flyway_s2_dis_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(dbName)
                .withPropertyValues(
                        "ateng.boot.flyway.mode=DISABLED",
                        "spring.flyway.locations=classpath:db/slice1"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    MigrationSummary startupSummary = template.getLastMigrationSummary();
                    assertThat(startupSummary).isNotNull();
                    assertThat(startupSummary.getMode()).isEqualTo(FlywayExecutionMode.DISABLED);
                    assertThat(startupSummary.getSkippedCount()).isEqualTo(1);
                    assertThat(startupSummary.getSuccessCount()).isZero();

                    DataSource ds = context.getBean(DataSource.class);
                    try (Connection conn = ds.getConnection();
                         var rs = conn.getMetaData().getTables(null, null, "SAMPLE_USER", null)) {
                        assertThat(rs.next()).isFalse();
                    }

                    // 显式手工迁移仍可执行
                    MigrationOutcome outcome = template.migrate();
                    assertThat(outcome.isSuccess()).isTrue();
                    assertThat(outcome.getMigrationsExecuted()).isEqualTo(1);

                    JdbcTemplate jdbcTemplate = new JdbcTemplate(ds);
                    Integer count = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM sample_user",
                            Integer.class
                    );
                    assertThat(count).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("应自动装配容器内 FlywayConfigurationCustomizer、JavaMigration、Callback 并替换 {vendor} 方言占位符")
    void shouldSupportCustomizersJavaMigrationsCallbacksAndVendorPlaceholder() {
        String dbName = "flyway_s4_ext_" + UUID.randomUUID().toString().replace("-", "");
        AtomicInteger afterMigrateCalls = new AtomicInteger(0);

        class V2__SeedRoleMigration extends BaseJavaMigration {
            @Override
            public void migrate(org.flywaydb.core.api.migration.Context context) throws Exception {
                try (PreparedStatement ps = context.getConnection()
                        .prepareStatement("INSERT INTO vendor_audit (role_name) VALUES (?)")) {
                    ps.setString(1, "java_migrated");
                    ps.executeUpdate();
                }
            }
        }
        JavaMigration javaMigration = new V2__SeedRoleMigration();

        Callback callback = new Callback() {
            @Override
            public boolean supports(Event event, Context context) {
                return Event.AFTER_MIGRATE == event;
            }

            @Override
            public boolean canHandleInTransaction(Event event, Context context) {
                return true;
            }

            @Override
            public void handle(Event event, Context context) {
                afterMigrateCalls.incrementAndGet();
            }

            @Override
            public String getCallbackName() {
                return "testAfterMigrateCallback";
            }
        };

        newContextRunner(dbName)
                .withPropertyValues("spring.flyway.locations=classpath:db/vendor/{vendor}")
                .withBean("roleCustomizer", FlywayConfigurationCustomizer.class,
                        () -> configuration -> configuration.placeholders(Map.of("customRole", "architect")))
                .withBean("v2SeedMigration", JavaMigration.class, () -> javaMigration)
                .withBean("afterMigrateCallback", Callback.class, () -> callback)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(afterMigrateCalls.get()).isEqualTo(1);

                    JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
                    Integer sqlRow = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM vendor_audit WHERE role_name = 'architect'",
                            Integer.class
                    );
                    Integer javaRow = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM vendor_audit WHERE role_name = 'java_migrated'",
                            Integer.class
                    );
                    assertThat(sqlRow).isEqualTo(1);
                    assertThat(javaRow).isEqualTo(1);

                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    MigrationSummary summary = template.getLastMigrationSummary();
                    assertThat(summary.getOutcomes()).hasSize(1);
                    assertThat(summary.getOutcomes().get(0).getMigrationsExecuted()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("当 spring.flyway.enabled=false 时应联动退让且不创建 Flyway 与不执行迁移")
    void shouldDisableAllFlywayWhenSpringFlywayDisabled() {
        String dbName = "flyway_s6_spring_off_" + UUID.randomUUID().toString().replace("-", "");
        newContextRunner(dbName)
                .withPropertyValues(
                        "spring.flyway.enabled=false",
                        "spring.flyway.locations=classpath:db/slice1"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AtengFlywayAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(FlywayTemplate.class);
                    assertThat(context).doesNotHaveBean(Flyway.class);
                    assertThat(context).doesNotHaveBean(FlywayMigrationInitializer.class);

                    DataSource ds = context.getBean(DataSource.class);
                    try (Connection conn = ds.getConnection();
                         var rs = conn.getMetaData().getTables(null, null, "SAMPLE_USER", null)) {
                        assertThat(rs.next()).isFalse();
                    }
                });
    }

    @Test
    @DisplayName("仅配置 spring.flyway.url 而无容器 DataSource Bean 时仍应受管装配并强制生产守卫与解析 {vendor}")
    void shouldTakeOverAndEnforceSafetyGuardWhenOnlyFlywayUrlConfigured() {
        String dbName = "flyway_s6_url_only_" + UUID.randomUUID().toString().replace("-", "");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "spring.flyway.url=jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1;MODE=MySQL",
                        "spring.flyway.user=sa",
                        "spring.flyway.password=",
                        "spring.flyway.clean-disabled=false",
                        "spring.flyway.locations=classpath:db/slice1"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DataSource.class);
                    assertThat(context).hasSingleBean(Flyway.class);
                    assertThat(context).hasSingleBean(FlywayTemplate.class);
                    assertThat(context.getBean(FlywayMigrationInitializer.class))
                            .isInstanceOf(AtengFlywayMigrationInitializer.class);

                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    assertThat(template.info().getAppliedCount()).isEqualTo(1);
                    assertThatThrownBy(template::clean)
                            .isInstanceOf(FlywayException.class)
                            .satisfies(ex -> assertThat(((FlywayException) ex).getCode())
                                    .isEqualTo(FlywayResultCode.CLEAN_FORBIDDEN.getCode()));
                });
    }

    @Test
    @DisplayName("存在自定义 FlywayMigrationStrategy Bean 时应执行策略并准确统计迁移脚本数量")
    void shouldCountExecutedMigrationsWhenCustomMigrationStrategyProvided() {
        String dbName = "flyway_s6_strategy_" + UUID.randomUUID().toString().replace("-", "");
        AtomicInteger invoked = new AtomicInteger(0);
        newContextRunner(dbName)
                .withPropertyValues("spring.flyway.locations=classpath:db/slice1")
                .withBean("customMigrationStrategy", FlywayMigrationStrategy.class, () -> flyway -> {
                    invoked.incrementAndGet();
                    flyway.migrate();
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(invoked.get()).isEqualTo(1);
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    MigrationSummary summary = template.getLastMigrationSummary();
                    assertThat(summary.getSuccessCount()).isEqualTo(1);
                    assertThat(summary.getOutcomes().get(0).getMigrationsExecuted()).isEqualTo(1);
                });
    }
}

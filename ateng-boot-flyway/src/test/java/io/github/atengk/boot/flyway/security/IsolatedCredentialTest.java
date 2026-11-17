package io.github.atengk.boot.flyway.security;

import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.core.util.HexUtil;
import io.github.atengk.boot.flyway.autoconfigure.AtengFlywayAutoConfiguration;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.exception.FlywayException;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.flyway.FlywayEndpointAutoConfiguration;
import org.springframework.boot.actuate.flyway.FlywayEndpoint;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 隔离式加密 DDL 凭据与瞬态连接零泄漏单元测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
class IsolatedCredentialTest {

    @Test
    @DisplayName("FlywayPasswordDecryptor 应支持 SM4/AES 密文瞬态解密并在错误密文时抛出 CREDENTIAL_DECRYPT_FAILED 且不泄露明密文")
    void shouldDecryptSm4AndAesAndFailFastWithoutLeakingSecrets() {
        byte[] sm4Key = CryptoUtil.generateSm4Key();
        byte[] sm4Iv = CryptoUtil.generateIv(CryptoUtil.SM4_IV_SIZE);
        String sm4KeyB64 = CryptoUtil.encodeBase64(sm4Key);
        String sm4IvB64 = CryptoUtil.encodeBase64(sm4Iv);

        String sm4Cbc = "enc:sm4:" + sm4KeyB64 + ":" + sm4IvB64 + ":" + CryptoUtil.encryptSm4Cbc("DdlSecret!2026", sm4Key, sm4Iv);
        String sm4Ecb = "enc:sm4:" + sm4KeyB64 + ":" + CryptoUtil.encryptSm4Ecb("DdlEcbSecret!99", sm4Key);

        byte[] aesKey = CryptoUtil.generateKey(128);
        byte[] aesIv = CryptoUtil.generateIv();
        String aesCbc = "enc:aes:" + CryptoUtil.encodeBase64(aesKey) + ":" + CryptoUtil.encodeBase64(aesIv) + ":"
                + CryptoUtil.encryptCbc("AesDdlSecret#88", aesKey, aesIv);

        AtengFlywayProperties props = new AtengFlywayProperties();
        props.getCredential().setSecretKey(HexUtil.encodeHex(sm4Key));
        props.getCredential().setIv(HexUtil.encodeHex(sm4Iv));
        String sm4Global = "enc:sm4:" + CryptoUtil.encryptSm4Cbc("GlobalSm4Pwd", sm4Key, sm4Iv);

        FlywayPasswordDecryptor decryptor = new FlywayPasswordDecryptor(props.getCredential());
        assertThat(decryptor.decrypt(sm4Cbc)).isEqualTo("DdlSecret!2026");
        assertThat(decryptor.decrypt(sm4Ecb)).isEqualTo("DdlEcbSecret!99");
        assertThat(decryptor.decrypt(aesCbc)).isEqualTo("AesDdlSecret#88");
        assertThat(decryptor.decrypt(sm4Global)).isEqualTo("GlobalSm4Pwd");
        assertThat(decryptor.decrypt("plain-pwd")).isEqualTo("plain-pwd");

        String brokenCipher = "enc:sm4:" + sm4KeyB64 + ":" + sm4IvB64 + ":NOT_VALID_BASE64_CIPHER";
        assertThatThrownBy(() -> decryptor.decrypt(brokenCipher))
                .isInstanceOf(FlywayException.class)
                .satisfies(ex -> {
                    FlywayException fe = (FlywayException) ex;
                    assertThat(fe.getCode()).isEqualTo(FlywayResultCode.CREDENTIAL_DECRYPT_FAILED.getCode());
                    assertThat(fe.getCause()).isNotNull();
                    assertThat(fe.getMessage()).doesNotContain("NOT_VALID_BASE64_CIPHER");
                    assertThat(fe.getMessage()).doesNotContain(sm4KeyB64);
                });
    }

    @Test
    @DisplayName("配置 enc:sm4 加密独立 DDL 凭据后应成功迁移建表、及时释放瞬态连接且不向容器注册额外 DataSource 或闭池 Flyway Bean")
    void shouldMigrateWithIsolatedEncryptedCredentialWithoutPollutingSpringContainer() {
        byte[] sm4Key = CryptoUtil.generateSm4Key();
        byte[] sm4Iv = CryptoUtil.generateIv(CryptoUtil.SM4_IV_SIZE);
        String encryptedEmptyPwd = "enc:sm4:" + CryptoUtil.encodeBase64(sm4Key) + ":" + CryptoUtil.encodeBase64(sm4Iv)
                + ":" + CryptoUtil.encryptSm4Cbc("ddl_pass_123", sm4Key, sm4Iv);

        String dbName = "flyway_iso_sm4_" + UUID.randomUUID().toString().replace("-", "");
        String ddlJdbcUrl = "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1;MODE=MySQL";

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class,
                        FlywayEndpointAutoConfiguration.class
                ))
                .withPropertyValues(
                        "spring.datasource.url=" + ddlJdbcUrl,
                        "spring.datasource.username=ddl_admin",
                        "spring.datasource.password=ddl_pass_123",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.flyway.locations=classpath:db/slice1",
                        "ateng.boot.flyway.credential.url=" + ddlJdbcUrl,
                        "ateng.boot.flyway.credential.username=ddl_admin",
                        "ateng.boot.flyway.credential.password=" + encryptedEmptyPwd,
                        "ateng.boot.flyway.credential.connect-timeout=5s"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // 1. 严禁新增额外 DataSource Bean，严禁暴露持有已关闭连接池的原生 Flyway Bean
                    assertThat(context).hasSingleBean(DataSource.class);
                    assertThat(context).doesNotHaveBean(Flyway.class);
                    assertThat(context).doesNotHaveBean(FlywayEndpoint.class);

                    // 2. 验证通过瞬态连接完成了建表迁移并已关闭瞬态连接池
                    JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                    Integer count = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM sample_user WHERE username = 'ateng'",
                            Integer.class
                    );
                    assertThat(count).isEqualTo(1);

                    TransientFlywayExecutor executor = context.getBean(TransientFlywayExecutor.class);
                    assertThat(executor.getActiveTransientPoolCount()).isZero();
                    assertThat(executor.getClosedTransientPoolCount()).isGreaterThanOrEqualTo(1);

                    // 3. 验证隔离凭据模式下编程式探查仍可工作且每次用完立即关池
                    FlywayTemplate template = context.getBean(FlywayTemplate.class);
                    assertThat(template.info().getAppliedCount()).isEqualTo(1);
                    assertThat(template.validate().isValid()).isTrue();
                    assertThat(executor.getActiveTransientPoolCount()).isZero();
                    assertThat(executor.getClosedTransientPoolCount()).isGreaterThanOrEqualTo(3);
                });
    }

    @Test
    @DisplayName("隔离凭据省略 url 时应自动继承主数据源 URL 且在禁用开关时退回正常装配")
    void shouldInheritRuntimeJdbcUrlWhenCredentialUrlOmittedAndRespectToggle() {
        String dbName = "flyway_iso_inh_" + UUID.randomUUID().toString().replace("-", "");
        new ApplicationContextRunner()
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
                        "spring.flyway.locations=classpath:db/slice1",
                        "ateng.boot.flyway.credential.username=sa",
                        "ateng.boot.flyway.credential.password="
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DataSource.class);
                    assertThat(context).doesNotHaveBean(Flyway.class);
                    TransientFlywayExecutor executor = context.getBean(TransientFlywayExecutor.class);
                    assertThat(executor.getActiveTransientPoolCount()).isZero();
                    assertThat(executor.getClosedTransientPoolCount()).isEqualTo(1L);
                });

        String disabledDb = "flyway_iso_dis_" + UUID.randomUUID().toString().replace("-", "");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:" + disabledDb + ";DB_CLOSE_DELAY=-1;MODE=MySQL",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.flyway.locations=classpath:db/slice1",
                        "ateng.boot.flyway.credential.enabled=false",
                        "ateng.boot.flyway.credential.username=ignored_admin"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(Flyway.class);
                    assertThat(context).doesNotHaveBean(TransientFlywayExecutor.class);
                });
    }

    @Test
    @DisplayName("无 HikariCP 回退 SimpleDriverDataSource 时应将 connectTimeout 透传至连接属性")
    void shouldApplyConnectTimeoutOnFallbackSimpleDriverDataSource() {
        AtengFlywayProperties props = new AtengFlywayProperties();
        props.getCredential().setConnectTimeout(Duration.ofSeconds(7));
        TransientFlywayExecutor executor = new TransientFlywayExecutor(
                props,
                new org.springframework.boot.autoconfigure.flyway.FlywayProperties(),
                new FlywayPasswordDecryptor(props.getCredential()),
                new org.springframework.core.io.DefaultResourceLoader(),
                null,
                null,
                null,
                new io.github.atengk.boot.flyway.guard.ProductionSafetyGuard(
                        props, new org.springframework.mock.env.MockEnvironment()),
                null
        );
        DataSource fallbackDs = executor.buildFallbackDriverDataSource(
                props.getCredential(),
                "jdbc:h2:mem:fallback_timeout_test;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        assertThat(fallbackDs).isInstanceOf(SimpleDriverDataSource.class);
        SimpleDriverDataSource simpleDs = (SimpleDriverDataSource) fallbackDs;
        assertThat(simpleDs.getConnectionProperties()).isNotNull();
        assertThat(simpleDs.getConnectionProperties().getProperty("connectTimeout")).isEqualTo("7000");
    }
}

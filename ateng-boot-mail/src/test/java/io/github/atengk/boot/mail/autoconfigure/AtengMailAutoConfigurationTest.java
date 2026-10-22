package io.github.atengk.boot.mail.autoconfigure;

import io.github.atengk.boot.mail.registry.MailAccountRegistry;
import io.github.atengk.boot.mail.template.MailTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 邮件自动装配条件与平滑探测测试用例
 *
 * @author Ateng
 * @since 2026-10-22
 */
class AtengMailAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengMailAutoConfiguration.class));

    @Test
    @DisplayName("缺外部配置时静默跳过自动装配并不引发崩溃")
    void testAutoConfigurationSkippedWhenNoProperties() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(MailTemplate.class);
            assertThat(context).doesNotHaveBean(MailAccountRegistry.class);
        });
    }

    @Test
    @DisplayName("显式配置顶级单账号时成功装配组件")
    void testAutoConfigurationWithTopLevelAccount() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.mail.host=smtp.example.com",
                        "ateng.boot.mail.username=user@example.com",
                        "ateng.boot.mail.password=secret"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(MailTemplate.class);
                    assertThat(context).hasSingleBean(MailAccountRegistry.class);
                    MailAccountRegistry registry = context.getBean(MailAccountRegistry.class);
                    assertThat(registry.size()).isEqualTo(1);
                    assertThat(registry.getDefault().getHost()).isEqualTo("smtp.example.com");
                });
    }

    @Test
    @DisplayName("显式配置多命名账号字典时成功装配各账号")
    void testAutoConfigurationWithMultipleAccounts() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.mail.accounts.ops.host=smtp.ops.com",
                        "ateng.boot.mail.accounts.ops.username=ops@ops.com",
                        "ateng.boot.mail.accounts.ops.password=pwd1",
                        "ateng.boot.mail.accounts.marketing.host=smtp.mkt.com",
                        "ateng.boot.mail.accounts.marketing.username=mkt@mkt.com",
                        "ateng.boot.mail.accounts.marketing.password=pwd2"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(MailTemplate.class);
                    assertThat(context).hasSingleBean(MailAccountRegistry.class);
                    MailAccountRegistry registry = context.getBean(MailAccountRegistry.class);
                    assertThat(registry.size()).isEqualTo(2);
                    assertThat(registry.contains("ops")).isTrue();
                    assertThat(registry.contains("marketing")).isTrue();
                });
    }

    @Test
    @DisplayName("仅配置 Spring 官方原生配置时平滑纳管保底")
    void testAutoConfigurationWithSpringMailFallback() {
        contextRunner
                .withPropertyValues(
                        "spring.mail.host=smtp.springfallback.com",
                        "spring.mail.username=spring@test.com",
                        "spring.mail.password=springpwd",
                        "spring.mail.port=587"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(MailTemplate.class);
                    MailAccountRegistry registry = context.getBean(MailAccountRegistry.class);
                    assertThat(registry.size()).isEqualTo(1);
                    assertThat(registry.getDefault().getHost()).isEqualTo("smtp.springfallback.com");
                    assertThat(registry.getDefault().getPort()).isEqualTo(587);
                });
    }

    @Test
    @DisplayName("全局开关 enabled 为 false 时禁用装配")
    void testAutoConfigurationDisabled() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.mail.enabled=false",
                        "ateng.boot.mail.host=smtp.example.com"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MailTemplate.class);
                    assertThat(context).doesNotHaveBean(MailAccountRegistry.class);
                });
    }
}

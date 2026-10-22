package io.github.atengk.boot.ssh.autoconfigure;

import io.github.atengk.boot.ssh.properties.SshProperties;
import org.apache.sshd.client.SshClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSH 自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SSH 自动装配单元测试")
class AtengSshAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengSshAutoConfiguration.class));

    @Test
    @DisplayName("测试默认配置下成功装配 SshClient 与 SshProperties")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SshProperties.class);
            assertThat(context).hasSingleBean(SshClient.class);
            SshClient client = context.getBean(SshClient.class);
            assertThat(client.isStarted()).isTrue();
        });
    }

    @Test
    @DisplayName("测试当 enabled=false 时不触发自动装配")
    void testDisabledAutoConfiguration() {
        contextRunner.withPropertyValues("ateng.boot.ssh.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SshProperties.class);
                    assertThat(context).doesNotHaveBean(SshClient.class);
                });
    }

    @Test
    @DisplayName("测试用户自定义 SshClient Bean 时能够平滑接管")
    void testCustomBeanOverride() {
        SshClient customClient = SshClient.setUpDefaultClient();
        contextRunner.withBean("sshClient", SshClient.class, () -> customClient)
                .run(context -> {
                    assertThat(context).hasSingleBean(SshClient.class);
                    assertThat(context.getBean(SshClient.class)).isSameAs(customClient);
                });
    }
}

package io.github.atengk.boot.web.ready;

import io.github.atengk.boot.core.diagnostic.SlowBeanDiagnosticPostProcessor;
import io.github.atengk.boot.web.properties.WebProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用就绪总览卡片渲染测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("应用就绪总览卡片渲染测试")
class ApplicationReadyCardListenerTest {

    @AfterEach
    void tearDown() {
        SlowBeanDiagnosticPostProcessor.clearSlowBeans();
    }

    @Test
    @DisplayName("buildCard: 格式化双线 ASCII 艺术边框卡片并输出完整应用与 JVM 信息")
    void shouldRenderReadyCardCorrectly() {
        WebProperties properties = new WebProperties();
        ApplicationReadyCardListener listener = new ApplicationReadyCardListener(properties);

        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.application.name", "order-center");
        env.setActiveProfiles("dev", "local");
        env.setProperty("server.port", "8888");

        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(env);
        context.refresh();

        String card = listener.buildCard(context, env, Duration.ofMillis(3456));

        assertThat(card).contains("╔════════════════════════════════════════════════════════════════════════════╗");
        assertThat(card).contains(":: Ateng Boot ::");
        assertThat(card).contains("Application   : order-center");
        assertThat(card).contains("Active Profile: dev, local");
        assertThat(card).contains("Startup Time  : 3.46 s");
        assertThat(card).contains("Local URL     : http://localhost:8888");
        assertThat(card).contains("Network URL   : http://");
        assertThat(card).contains("Process PID   :");
        assertThat(card).contains("OS / Arch     :");
        assertThat(card).contains("Java Runtime  :");
        assertThat(card).contains("Heap Memory   :");
        assertThat(card).contains("╚════════════════════════════════════════════════════════════════════════════╝");
    }

    @Test
    @DisplayName("buildCard: 正确处理 ContextPath 与 SSL 配置")
    void shouldHandleContextPathAndSsl() {
        WebProperties properties = new WebProperties();
        ApplicationReadyCardListener listener = new ApplicationReadyCardListener(properties);

        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.application.name", "auth-center");
        env.setProperty("server.port", "8443");
        env.setProperty("server.servlet.context-path", "/api/v1");
        env.setProperty("server.ssl.enabled", "true");

        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(env);
        context.refresh();

        String card = listener.buildCard(context, env, Duration.ofSeconds(2));

        assertThat(card).contains("Local URL     : https://localhost:8443/api/v1");
        assertThat(card).contains("Network URL   : https://");
    }

    @Test
    @DisplayName("buildCard: 存在慢 Bean 诊断记录时输出慢 Bean 告警摘要")
    void shouldIncludeSlowBeansDiagnosticWhenPresent() {
        WebProperties properties = new WebProperties();
        ApplicationReadyCardListener listener = new ApplicationReadyCardListener(properties);

        SlowBeanDiagnosticPostProcessor postProcessor = new SlowBeanDiagnosticPostProcessor(Duration.ofMillis(100));
        postProcessor.postProcessBeforeInitialization(new Object(), "slowDataSourceBean");
        try {
            Thread.sleep(120);
        } catch (InterruptedException ignored) {
        }
        postProcessor.postProcessAfterInitialization(new Object(), "slowDataSourceBean");

        MockEnvironment env = new MockEnvironment();
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(env);
        context.refresh();

        String card = listener.buildCard(context, env, Duration.ofSeconds(1));
        assertThat(card).contains("Slow Beans    : 1 detected");
        assertThat(card).contains("slowDataSourceBean");
    }

    @Test
    @DisplayName("resolveDocPath: 正确解析 Knife4j 与自定义文档路径")
    void shouldResolveDocPathCorrectly() {
        MockEnvironment env = new MockEnvironment();
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();

        // 默认无文档依赖或未开启
        assertThat(ApplicationReadyCardListener.resolveDocPath(env, context)).isNull();

        // 模拟 Knife4j 开启属性
        env.setProperty("knife4j.enable", "true");
        assertThat(ApplicationReadyCardListener.resolveDocPath(env, context)).isEqualTo("/doc.html");
    }
}

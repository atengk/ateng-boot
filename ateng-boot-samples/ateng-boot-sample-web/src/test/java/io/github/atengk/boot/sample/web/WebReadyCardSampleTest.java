package io.github.atengk.boot.sample.web;

import io.github.atengk.boot.web.ready.ApplicationReadyCardListener;
import io.github.atengk.boot.web.ready.ProductionSanityChecker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web 场景工程启动就绪总览卡片与零 XML 运行全真集成测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Web 场景工程就绪卡片与零 XML 端到端实测")
class WebReadyCardSampleTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationReadyCardListener readyCardListener;

    @Autowired
    private ProductionSanityChecker sanityChecker;

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("全真验证：启动就绪卡片规整输出、端口动态绑定与 SpringDoc 在线文档自动发现")
    void shouldRenderReadyCardWithSpringDocInSampleWeb() {
        assertThat(readyCardListener).isNotNull();
        assertThat(sanityChecker).isNotNull();

        String card = readyCardListener.buildCard(applicationContext, environment, Duration.ofMillis(2150));

        // 验证边框与标头
        assertThat(card).contains("╔════════════════════════════════════════════════════════════════════════════╗");
        assertThat(card).contains(":: Ateng Boot ::");
        assertThat(card).contains("Application   : ateng-boot-sample-web");

        // 验证动态端口与在线接口文档直达地址
        assertThat(card).contains("Local URL     : http://localhost:" + port);
        assertThat(card).contains("Network URL   : http://");
        assertThat(card).contains("Doc URL       : http://");
        assertThat(card).contains("/swagger-ui/index.html");

        // 验证 JVM 资源快照
        assertThat(card).contains("Process PID   :");
        assertThat(card).contains("Heap Memory   :");
        assertThat(card).contains("╚════════════════════════════════════════════════════════════════════════════╝");
    }

    @Test
    @DisplayName("全真验证：零 XML 模式下控制台日志格式已注入 MDC traceId 标记")
    void shouldVerifyZeroXmlLoggingInWebSample() {
        String pattern = environment.getProperty("logging.pattern.console");
        assertThat(pattern).isNotNull();
        assertThat(pattern).contains("traceId");
    }
}

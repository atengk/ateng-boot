package io.github.atengk.boot.sample.http;

import io.github.atengk.boot.sample.http.provider.MockServerProviderController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sample 双模切换开关隔离验证测试
 * 验证激活 local Profile 时离线回环仿真桩 MockServerProviderController 不会被加载，避免与真实直连服务端口及路由冲突
 *
 * @author Ateng
 * @since 2026-11-14
 */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MockServerLocalProfileTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("sample.user-service.url", () -> "http://user-service.example.com");
        registry.add("sample.order-service.url", () -> "http://order-service.example.com");
        registry.add("sample.wechat-pay.url", () -> "https://pay.example.com");
        registry.add("sample.flaky-service.url", () -> "http://flaky-service.example.com");
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("local Profile 激活时应自动屏蔽 MockServerProviderController 离线回环桩")
    void shouldExcludeMockServerProviderWhenLocalProfileActive() {
        assertThat(applicationContext.getBeansOfType(MockServerProviderController.class)).isEmpty();
    }
}

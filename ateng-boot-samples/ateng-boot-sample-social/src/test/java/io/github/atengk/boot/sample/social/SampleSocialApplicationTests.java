package io.github.atengk.boot.sample.social;

import org.dromara.anduin.http.HttpClientAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 社交登录自验应用上下文加载与基础装配测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest
class SampleSocialApplicationTests {

    @Autowired(required = false)
    private HttpClientAdapter httpClientAdapter;

    @Test
    @DisplayName("上下文平滑加载与 HttpClientAdapter 自动装配断言")
    void contextLoads() {
        assertNotNull(httpClientAdapter, "HttpClientAdapter should be auto-configured by AtengSocialAutoConfiguration");
    }
}

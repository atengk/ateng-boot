package io.github.atengk.boot.sample.ssh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * SSH 样例服务上下文加载测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@SpringBootTest
@DisplayName("SSH 样例服务上下文加载测试")
class SampleSshApplicationTest {

    @Test
    @DisplayName("测试 Spring Boot 容器能够正常加载并注入 SSH 模块依赖")
    void contextLoads() {
        // 容器加载成功即代表装配正常
    }
}

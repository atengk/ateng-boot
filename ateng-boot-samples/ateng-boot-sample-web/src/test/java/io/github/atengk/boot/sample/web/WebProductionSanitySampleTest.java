package io.github.atengk.boot.sample.web;

import io.github.atengk.boot.web.ready.ProductionSanityChecker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web 场景工程生产环境 Profile 安全防呆端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@SpringBootTest(properties = "spring.profiles.active=prod")
@DisplayName("Web 场景工程 prod Profile 安全防呆端到端实测")
class WebProductionSanitySampleTest {

    @Autowired
    private ProductionSanityChecker sanityChecker;

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("全真验证：在 prod Profile 下 ProductionSanityChecker 成功探测到在线文档并触发安全警示")
    void shouldTriggerSanityWarningsInProdProfile() {
        assertThat(sanityChecker).isNotNull();
        assertThat(sanityChecker.isProductionProfile(environment)).isTrue();

        List<String> warnings = sanityChecker.checkSanity(environment, applicationContext);
        assertThat(warnings).isNotEmpty();
        assertThat(warnings).anyMatch(w -> w.contains("[SECURITY-WARN]") && w.contains("Swagger/OpenAPI"));
    }
}

package io.github.atengk.boot.web.ready;

import io.github.atengk.boot.web.properties.WebProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产环境安全防呆自检测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("生产环境安全防呆自检测试")
class ProductionSanityCheckerTest {

    @Test
    @DisplayName("非生产 Profile 下不触发任何安全警示")
    void shouldNotWarnInNonProdProfile() {
        WebProperties properties = new WebProperties();
        ProductionSanityChecker checker = new ProductionSanityChecker(properties);

        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev", "local");

        List<String> warnings = checker.checkSanity(env, null);
        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("生产 Profile 下当 PayloadLog 开启时输出警告")
    void shouldWarnWhenPayloadLogEnabledInProd() {
        WebProperties properties = new WebProperties();
        properties.getPayloadLog().setEnabled(true);
        ProductionSanityChecker checker = new ProductionSanityChecker(properties);

        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        List<String> warnings = checker.checkSanity(env, null);
        assertThat(warnings).anyMatch(w -> w.contains("[SECURITY-WARN]") && w.contains("HTTP Payload"));
    }

    @Test
    @DisplayName("生产 Profile 下当 H2 控制台开启时输出警告")
    void shouldWarnWhenH2ConsoleEnabledInProd() {
        WebProperties properties = new WebProperties();
        ProductionSanityChecker checker = new ProductionSanityChecker(properties);

        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");
        env.setProperty("spring.h2.console.enabled", "true");

        List<String> warnings = checker.checkSanity(env, null);
        assertThat(warnings).anyMatch(w -> w.contains("[SECURITY-WARN]") && w.contains("H2"));
    }

    @Test
    @DisplayName("生产 Profile 下当 Knife4j 开启时输出警告")
    void shouldWarnWhenKnife4jEnabledInProd() {
        WebProperties properties = new WebProperties();
        ProductionSanityChecker checker = new ProductionSanityChecker(properties);

        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod-beijing");
        env.setProperty("knife4j.enable", "true");

        List<String> warnings = checker.checkSanity(env, null);
        assertThat(warnings).anyMatch(w -> w.contains("[SECURITY-WARN]") && w.contains("Knife4j"));
    }

    @Test
    @DisplayName("生产 Profile 下当 SpringDoc 存在且启用时输出警告")
    void shouldWarnWhenSpringDocEnabledInProd() {
        WebProperties properties = new WebProperties();
        ProductionSanityChecker checker = new ProductionSanityChecker(properties);

        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("springdoc.api-docs.enabled", "true");

        List<String> warnings = checker.checkSanity(env, null);
        assertThat(warnings).anyMatch(w -> w.contains("[SECURITY-WARN]") && w.contains("Swagger/OpenAPI"));
    }

    @Test
    @DisplayName("isProductionProfile: 正确判定多种生产 Profile 别名")
    void shouldCorrectlyIdentifyProdProfiles() {
        WebProperties properties = new WebProperties();
        ProductionSanityChecker checker = new ProductionSanityChecker(properties);

        MockEnvironment env1 = new MockEnvironment();
        env1.setActiveProfiles("prod");
        assertThat(checker.isProductionProfile(env1)).isTrue();

        MockEnvironment env2 = new MockEnvironment();
        env2.setActiveProfiles("production");
        assertThat(checker.isProductionProfile(env2)).isTrue();

        MockEnvironment env3 = new MockEnvironment();
        env3.setActiveProfiles("prod_k8s");
        assertThat(checker.isProductionProfile(env3)).isTrue();

        MockEnvironment env4 = new MockEnvironment();
        env4.setActiveProfiles("test", "uat");
        assertThat(checker.isProductionProfile(env4)).isFalse();

        MockEnvironment env5 = new MockEnvironment();
        assertThat(checker.isProductionProfile(env5)).isFalse();
    }
}

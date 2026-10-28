package io.github.atengk.boot.satoken.model;

import cn.dev33.satoken.annotation.SaMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PathPermissionRule 动态路径权限规则模型单元测试
 * 覆盖：
 * 1. 工厂方法与防御性参数清洗
 * 2. HTTP Method 匹配规则（单方法、逗号多方法、* 或 ALL 宽松通配、大小写中立）
 * 3. AntPath 模式匹配规则（精确路径、单星号通配符、双星号跨级通配符）
 * 4. SaMode 组合判定模式
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("PathPermissionRule 动态路径权限规则模型测试")
class PathPermissionRuleTest {

    private final PathMatcher pathMatcher = new AntPathMatcher();

    @Test
    @DisplayName("1. 工厂方法：便捷构造与权限码去重清洗")
    void testFactoryMethods() {
        PathPermissionRule r1 = PathPermissionRule.of("/api/v1/users/**", "user:query");
        assertThat(r1.getPathPattern()).isEqualTo("/api/v1/users/**");
        assertThat(r1.getHttpMethod()).isNull();
        assertThat(r1.getPermissions()).containsExactly("user:query");
        assertThat(r1.getMode()).isEqualTo(SaMode.OR);

        PathPermissionRule r2 = PathPermissionRule.of("/api/v1/users/**", "GET", "user:query");
        assertThat(r2.getHttpMethod()).isEqualTo("GET");

        PathPermissionRule r3 = PathPermissionRule.of("/api/v1/users/**", "POST", "user:create", "user:add", " ");
        assertThat(r3.getPermissions()).containsExactlyInAnyOrder("user:create", "user:add");
        assertThat(r3.getMode()).isEqualTo(SaMode.OR);

        PathPermissionRule r4 = PathPermissionRule.of("/api/v1/users/**", "DELETE", SaMode.AND, "user:delete", "user:admin");
        assertThat(r4.getMode()).isEqualTo(SaMode.AND);
        assertThat(r4.getPermissions()).containsExactlyInAnyOrder("user:delete", "user:admin");

        PathPermissionRule r5 = PathPermissionRule.of("/api/v1/orders/**", "PUT", List.of("order:update", "order:edit"));
        assertThat(r5.getPermissions()).containsExactlyInAnyOrder("order:update", "order:edit");
    }

    @Test
    @DisplayName("2. HTTP Method 匹配测试：单方法、大小写中立、逗号分隔多方法与通配符")
    void testHttpMethodMatching() {
        // 1. 单方法精确匹配（大小写中立）
        PathPermissionRule ruleGet = PathPermissionRule.of("/api/orders", "GET", "order:query");
        assertThat(ruleGet.matches(pathMatcher, "/api/orders", "GET")).isTrue();
        assertThat(ruleGet.matches(pathMatcher, "/api/orders", "get")).isTrue();
        assertThat(ruleGet.matches(pathMatcher, "/api/orders", "POST")).isFalse();
        assertThat(ruleGet.matches(pathMatcher, "/api/orders", null)).isFalse();

        // 2. 逗号分隔多方法
        PathPermissionRule ruleMulti = PathPermissionRule.of("/api/orders", "GET,POST", "order:op");
        assertThat(ruleMulti.matches(pathMatcher, "/api/orders", "GET")).isTrue();
        assertThat(ruleMulti.matches(pathMatcher, "/api/orders", "POST")).isTrue();
        assertThat(ruleMulti.matches(pathMatcher, "/api/orders", "DELETE")).isFalse();

        // 3. 宽松通配符：null, *, ALL
        PathPermissionRule ruleAll1 = PathPermissionRule.of("/api/orders", null, "order:op");
        assertThat(ruleAll1.matches(pathMatcher, "/api/orders", "GET")).isTrue();
        assertThat(ruleAll1.matches(pathMatcher, "/api/orders", "DELETE")).isTrue();

        PathPermissionRule ruleAll2 = PathPermissionRule.of("/api/orders", "*", "order:op");
        assertThat(ruleAll2.matches(pathMatcher, "/api/orders", "POST")).isTrue();

        PathPermissionRule ruleAll3 = PathPermissionRule.of("/api/orders", "ALL", "order:op");
        assertThat(ruleAll3.matches(pathMatcher, "/api/orders", "PUT")).isTrue();
    }

    @Test
    @DisplayName("3. AntPath 路径通配匹配测试")
    void testAntPathMatching() {
        PathPermissionRule rule = PathPermissionRule.of("/api/users/**", "GET", "user:query");

        assertThat(rule.matches(pathMatcher, "/api/users/list", "GET")).isTrue();
        assertThat(rule.matches(pathMatcher, "/api/users/1001/roles", "GET")).isTrue();
        assertThat(rule.matches(pathMatcher, "/api/orders/list", "GET")).isFalse();

        // 防御性测试：null 路径与 null matcher
        assertThat(rule.matches(null, "/api/users/list", "GET")).isFalse();
        assertThat(rule.matches(pathMatcher, null, "GET")).isFalse();
    }

    @Test
    @DisplayName("4. 属性链式与 Setter/Getter 完备性")
    void testBeanCompleteness() {
        PathPermissionRule rule = new PathPermissionRule()
                .setPathPattern("/api/test")
                .setHttpMethod("POST")
                .setPermissions(Set.of("test:perm"))
                .setMode(SaMode.AND);

        assertThat(rule.getPathPattern()).isEqualTo("/api/test");
        assertThat(rule.getHttpMethod()).isEqualTo("POST");
        assertThat(rule.getPermissions()).contains("test:perm");
        assertThat(rule.getMode()).isEqualTo(SaMode.AND);
        assertThat(rule.toString()).isNotBlank();
    }
}

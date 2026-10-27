package io.github.atengk.boot.web.version.condition;

import io.github.atengk.boot.web.version.properties.ApiVersionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API 版本匹配条件单测
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("ApiVersionCondition 核心匹配与版本比较单测")
class ApiVersionConditionTest {

    @Test
    @DisplayName("1. URL 路径前缀精准匹配：/v1/user 成功匹配 V1 条件，拒绝 V2 条件")
    void shouldMatchUrlPathPrefix() {
        ApiVersionProperties properties = new ApiVersionProperties();
        ApiVersionCondition conditionV1 = new ApiVersionCondition("1", properties);
        ApiVersionCondition conditionV2 = new ApiVersionCondition("2", properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/api/user");

        assertThat(conditionV1.getMatchingCondition(request)).isNotNull();
        assertThat(conditionV2.getMatchingCondition(request)).isNull();
    }

    @Test
    @DisplayName("2. 请求头版本精准匹配：Header X-API-Version: 2 匹配 V2 条件，拒绝 V1 条件")
    void shouldMatchHeaderVersion() {
        ApiVersionProperties properties = new ApiVersionProperties();
        ApiVersionCondition conditionV1 = new ApiVersionCondition("1", properties);
        ApiVersionCondition conditionV2 = new ApiVersionCondition("2", properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/user");
        request.addHeader("X-API-Version", "2");

        assertThat(conditionV1.getMatchingCondition(request)).isNull();
        assertThat(conditionV2.getMatchingCondition(request)).isNotNull();
    }

    @Test
    @DisplayName("3. 缺省版本回退：未提供 URL 前缀与 Header 时自动回退至默认版本 1")
    void shouldFallbackToDefaultVersion() {
        ApiVersionProperties properties = new ApiVersionProperties();
        properties.setDefaultVersion("1");

        ApiVersionCondition conditionV1 = new ApiVersionCondition("1", properties);
        ApiVersionCondition conditionV2 = new ApiVersionCondition("2", properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/user");

        assertThat(conditionV1.getMatchingCondition(request)).isNotNull();
        assertThat(conditionV2.getMatchingCondition(request)).isNull();
    }

    @Test
    @DisplayName("4. 数值化兼容比对：1 与 1.0、v2 与 2 兼容识别匹配")
    void shouldSupportSemanticVersionEquivalence() {
        ApiVersionProperties properties = new ApiVersionProperties();
        ApiVersionCondition condition10 = new ApiVersionCondition("1.0", properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/api/user");
        assertThat(condition10.getMatchingCondition(request)).isNotNull();

        ApiVersionCondition condition2 = new ApiVersionCondition("2", properties);
        MockHttpServletRequest reqHeader = new MockHttpServletRequest("GET", "/api/user");
        reqHeader.addHeader("X-API-Version", "v2");
        assertThat(condition2.getMatchingCondition(reqHeader)).isNotNull();
    }

    @Test
    @DisplayName("5. 条件优先级比对：版本号更高的优先胜出 (v2 优先于 v1)")
    void shouldCompareVersionsProperly() {
        ApiVersionProperties properties = new ApiVersionProperties();
        ApiVersionCondition conditionV1 = new ApiVersionCondition("1", properties);
        ApiVersionCondition conditionV2 = new ApiVersionCondition("2", properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/user");

        // compareTo 返回负数表示 this 优于 other
        assertThat(conditionV2.compareTo(conditionV1, request)).isLessThan(0);
        assertThat(conditionV1.compareTo(conditionV2, request)).isGreaterThan(0);
        assertThat(conditionV1.compareTo(new ApiVersionCondition("1.0", properties), request)).isEqualTo(0);
    }

    @Test
    @DisplayName("6. 方法级别覆盖类级别：combine 优先保留方法级别 Condition")
    void shouldOverrideClassConditionWithMethodCondition() {
        ApiVersionProperties properties = new ApiVersionProperties();
        ApiVersionCondition classCondition = new ApiVersionCondition("1", properties);
        ApiVersionCondition methodCondition = new ApiVersionCondition("2", properties);

        ApiVersionCondition combined = classCondition.combine(methodCondition);
        assertThat(combined.getVersion()).isEqualTo("2");

        ApiVersionCondition fallback = classCondition.combine(null);
        assertThat(fallback.getVersion()).isEqualTo("1");
    }

    @Test
    @DisplayName("7. 路径中段防御：/api/items/v2/details 不应误识别为 V2 版本，应回退至默认版本 1")
    void shouldNotMatchVersionInMiddleOfUri() {
        ApiVersionProperties properties = new ApiVersionProperties();
        properties.setDefaultVersion("1");

        ApiVersionCondition conditionV1 = new ApiVersionCondition("1", properties);
        ApiVersionCondition conditionV2 = new ApiVersionCondition("2", properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/items/v2/details");

        assertThat(conditionV1.getMatchingCondition(request)).isNotNull();
        assertThat(conditionV2.getMatchingCondition(request)).isNull();
    }

    @Test
    @DisplayName("8. SemVer 语义化版本隔离：2.10 与 2.1 区分隔离，避免浮点数误判")
    void shouldDistinguishSubVersions() {
        assertThat(ApiVersionCondition.isVersionMatch("2.10", "2.1")).isFalse();
        assertThat(ApiVersionCondition.isVersionMatch("1", "1.0")).isTrue();
        assertThat(ApiVersionCondition.isVersionMatch("1.0", "1")).isTrue();
        assertThat(ApiVersionCondition.isVersionMatch("1.0.1", "1.0.1")).isTrue();
        assertThat(ApiVersionCondition.isVersionMatch("1.0.1", "1.0.2")).isFalse();
    }
}

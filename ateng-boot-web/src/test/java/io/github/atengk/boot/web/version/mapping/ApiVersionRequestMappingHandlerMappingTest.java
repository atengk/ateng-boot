package io.github.atengk.boot.web.version.mapping;

import io.github.atengk.boot.web.version.annotation.ApiVersion;
import io.github.atengk.boot.web.version.condition.ApiVersionCondition;
import io.github.atengk.boot.web.version.properties.ApiVersionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ApiVersionRequestMappingHandlerMapping 路径前缀与映射规则单测
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("ApiVersionRequestMappingHandlerMapping 路由拓展单测")
class ApiVersionRequestMappingHandlerMappingTest {

    @Test
    @DisplayName("类级别 @ApiVersion 自动追加 /v{version} 路径前缀并挂载 CustomCondition")
    void shouldAppendPathPrefixForClassLevelVersion() throws Exception {
        ApiVersionProperties properties = new ApiVersionProperties();
        ApiVersionRequestMappingHandlerMapping mapping = new ApiVersionRequestMappingHandlerMapping(properties);

        Method method = SampleV1Controller.class.getMethod("getUser");
        RequestMappingInfo info = mapping.getMappingForMethod(method, SampleV1Controller.class);

        assertThat(info).isNotNull();
        Set<String> patterns = info.getPatternValues();

        // 同时支持原始路径与带有版本前缀的路径
        assertThat(patterns).containsExactlyInAnyOrder("/api/sample/user", "/v1/api/sample/user");

        // 验证 customCondition 生效且版本为 1
        assertThat(info.getCustomCondition()).isInstanceOf(ApiVersionCondition.class);
        ApiVersionCondition condition = (ApiVersionCondition) info.getCustomCondition();
        assertThat(condition.getVersion()).isEqualTo("1");
    }

    @Test
    @DisplayName("方法级 @ApiVersion 覆盖类级别版本并生成对应前缀")
    void shouldOverrideVersionOnMethodLevel() throws Exception {
        ApiVersionProperties properties = new ApiVersionProperties();
        ApiVersionRequestMappingHandlerMapping mapping = new ApiVersionRequestMappingHandlerMapping(properties);

        Method method = SampleV1Controller.class.getMethod("getCustomUser");
        RequestMappingInfo info = mapping.getMappingForMethod(method, SampleV1Controller.class);

        assertThat(info).isNotNull();
        Set<String> patterns = info.getPatternValues();

        // 方法级注解 @ApiVersion("2") 覆盖类级
        assertThat(patterns).containsExactlyInAnyOrder("/api/sample/custom", "/v2/api/sample/custom");

        assertThat(info.getCustomCondition()).isInstanceOf(ApiVersionCondition.class);
        ApiVersionCondition condition = (ApiVersionCondition) info.getCustomCondition();
        assertThat(condition.getVersion()).isEqualTo("2");
    }

    @Test
    @DisplayName("当 pathPrefixEnabled 为 false 时仅注册原始路径，依托 Header 与缺省版本分流")
    void shouldNotAppendPrefixWhenDisabled() throws Exception {
        ApiVersionProperties properties = new ApiVersionProperties();
        properties.setPathPrefixEnabled(false);
        ApiVersionRequestMappingHandlerMapping mapping = new ApiVersionRequestMappingHandlerMapping(properties);

        Method method = SampleV1Controller.class.getMethod("getUser");
        RequestMappingInfo info = mapping.getMappingForMethod(method, SampleV1Controller.class);

        assertThat(info).isNotNull();
        Set<String> patterns = info.getPatternValues();
        assertThat(patterns).containsExactly("/api/sample/user");
    }

    @RestController
    @RequestMapping("/api/sample")
    @ApiVersion("1")
    static class SampleV1Controller {

        @GetMapping("/user")
        public String getUser() {
            return "user_v1";
        }

        @GetMapping("/custom")
        @ApiVersion("2")
        public String getCustomUser() {
            return "user_v2";
        }
    }

    @Test
    @DisplayName("通过 number 属性声明整型版本（如 @ApiVersion(number = 3)）支持")
    void shouldSupportNumberVersionAttribute() throws Exception {
        ApiVersionProperties properties = new ApiVersionProperties();
        ApiVersionRequestMappingHandlerMapping mapping = new ApiVersionRequestMappingHandlerMapping(properties);

        Method method = SampleNumberController.class.getMethod("getNumberUser");
        RequestMappingInfo info = mapping.getMappingForMethod(method, SampleNumberController.class);

        assertThat(info).isNotNull();
        Set<String> patterns = info.getPatternValues();
        assertThat(patterns).containsExactlyInAnyOrder("/api/sample/number", "/v3/api/sample/number");

        ApiVersionCondition condition = (ApiVersionCondition) info.getCustomCondition();
        assertThat(condition.getVersion()).isEqualTo("3");
    }

    @RestController
    @RequestMapping("/api/sample")
    @ApiVersion(number = 3)
    static class SampleNumberController {
        @GetMapping("/number")
        public String getNumberUser() {
            return "user_v3";
        }
    }
}

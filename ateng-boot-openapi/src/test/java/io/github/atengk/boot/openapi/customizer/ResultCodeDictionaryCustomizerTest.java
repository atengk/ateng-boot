package io.github.atengk.boot.openapi.customizer;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 系统通用状态码全景字典注入定制器测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("状态码全景字典定制器测试")
class ResultCodeDictionaryCustomizerTest {

    @Test
    @DisplayName("测试默认配置下成功注入 Markdown 状态码表格")
    void testDefaultDictionaryInjection() {
        OpenApiProperties properties = new OpenApiProperties();
        ResultCodeDictionaryCustomizer customizer = new ResultCodeDictionaryCustomizer(properties);

        OpenAPI openAPI = new OpenAPI();
        openAPI.setInfo(new Info().description("Original System Description"));

        customizer.customize(openAPI);

        String description = openAPI.getInfo().getDescription();
        assertThat(description).isNotNull();
        assertThat(description).startsWith("Original System Description\n\n");
        assertThat(description).contains(ResultCodeDictionaryCustomizer.DICTIONARY_TITLE);

        // 验证各状态码均已注入
        for (CommonResultCode code : CommonResultCode.values()) {
            assertThat(description).contains("`" + code.getCode() + "`");
            assertThat(description).contains(code.getMsg());
        }
    }

    @Test
    @DisplayName("测试在无原有 description 时直接设置状态码表格")
    void testEmptyDescription() {
        OpenApiProperties properties = new OpenApiProperties();
        ResultCodeDictionaryCustomizer customizer = new ResultCodeDictionaryCustomizer(properties);

        OpenAPI openAPI = new OpenAPI();
        customizer.customize(openAPI);

        assertThat(openAPI.getInfo()).isNotNull();
        assertThat(openAPI.getInfo().getDescription()).startsWith(ResultCodeDictionaryCustomizer.DICTIONARY_TITLE);
    }

    @Test
    @DisplayName("测试重复调用具备幂等性，不重复追加表格")
    void testIdempotency() {
        OpenApiProperties properties = new OpenApiProperties();
        ResultCodeDictionaryCustomizer customizer = new ResultCodeDictionaryCustomizer(properties);

        OpenAPI openAPI = new OpenAPI();
        customizer.customize(openAPI);
        String firstDesc = openAPI.getInfo().getDescription();

        customizer.customize(openAPI);
        String secondDesc = openAPI.getInfo().getDescription();

        assertThat(secondDesc).isEqualTo(firstDesc);
    }

    @Test
    @DisplayName("测试当 showResultCodes=false 时不追加字典")
    void testDisabledDictionary() {
        OpenApiProperties properties = new OpenApiProperties();
        properties.setShowResultCodes(false);

        ResultCodeDictionaryCustomizer customizer = new ResultCodeDictionaryCustomizer(properties);
        OpenAPI openAPI = new OpenAPI();
        openAPI.setInfo(new Info().description("Base Description"));

        customizer.customize(openAPI);

        assertThat(openAPI.getInfo().getDescription()).isEqualTo("Base Description");
    }

    @Test
    @DisplayName("测试空安全防御保护 (Null Safety)")
    void testNullSafety() {
        OpenApiProperties properties = new OpenApiProperties();
        ResultCodeDictionaryCustomizer customizer = new ResultCodeDictionaryCustomizer(properties);

        customizer.customize(null);
    }
}

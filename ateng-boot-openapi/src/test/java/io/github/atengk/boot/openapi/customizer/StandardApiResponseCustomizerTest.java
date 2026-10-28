package io.github.atengk.boot.openapi.customizer;

import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通用标准 HTTP 响应说明定制器测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("通用标准 HTTP 响应定制器测试")
class StandardApiResponseCustomizerTest {

    @Test
    @DisplayName("测试默认配置下为 Operation 补全 200, 400, 401, 500 响应说明")
    void testDefaultResponsesInjection() {
        OpenApiProperties properties = new OpenApiProperties();
        StandardApiResponseCustomizer customizer = new StandardApiResponseCustomizer(properties);

        Operation operation = new Operation();
        customizer.customize(operation, null);

        ApiResponses responses = operation.getResponses();
        assertThat(responses).isNotNull();
        assertThat(responses).containsKeys("200", "400", "401", "500");

        assertThat(responses.get("200").getDescription()).contains("HTTP 200 OK");
        assertThat(responses.get("400").getDescription()).contains("HTTP 400 Bad Request");
        assertThat(responses.get("401").getDescription()).contains("HTTP 401 Unauthorized");
        assertThat(responses.get("500").getDescription()).contains("HTTP 500 Internal Server Error");
    }

    @Test
    @DisplayName("测试已有自定义响应说明时保留，不强制覆盖")
    void testPreserveExistingResponses() {
        OpenApiProperties properties = new OpenApiProperties();
        StandardApiResponseCustomizer customizer = new StandardApiResponseCustomizer(properties);

        Operation operation = new Operation();
        ApiResponses existingResponses = new ApiResponses();
        existingResponses.addApiResponse("200", new ApiResponse().description("Custom Success Message"));
        operation.setResponses(existingResponses);

        customizer.customize(operation, null);

        ApiResponses responses = operation.getResponses();
        assertThat(responses.get("200").getDescription()).isEqualTo("Custom Success Message");
        assertThat(responses.get("400").getDescription()).contains("HTTP 400 Bad Request");
    }

    @Test
    @DisplayName("测试当 standardResponses=false 时不追加任何响应说明")
    void testDisabledResponses() {
        OpenApiProperties properties = new OpenApiProperties();
        properties.setStandardResponses(false);

        StandardApiResponseCustomizer customizer = new StandardApiResponseCustomizer(properties);
        Operation operation = new Operation();

        customizer.customize(operation, null);

        assertThat(operation.getResponses()).isNull();
    }

    @Test
    @DisplayName("测试空安全防御保护 (Null Safety)")
    void testNullSafety() {
        OpenApiProperties properties = new OpenApiProperties();
        StandardApiResponseCustomizer customizer = new StandardApiResponseCustomizer(properties);

        assertThat(customizer.customize(null, null)).isNull();
    }
}

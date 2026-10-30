package io.github.atengk.boot.openapi.export;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.webmvc.api.MultipleOpenApiWebMvcResource;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OpenAPI 契约离线导出门面 (OpenApiExportHelper) 单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("OpenAPI 离线契约导出门面 (OpenApiExportHelper) 单元测试")
class OpenApiExportHelperTest {

    @AfterEach
    void tearDown() {
        OpenApiExportHelper.setInstance(null);
    }

    @Test
    @DisplayName("测试独立脱机模式：直接将 OpenAPI 实体导出为 JSON 与 YAML")
    void testStandaloneExport() throws IOException {
        OpenAPI openAPI = new OpenAPI().info(
                new Info().title("Offline Test API").version("2.5.0").description("Offline description")
        );

        // 1. 导出 JSON
        String json = OpenApiExportHelper.exportJson(openAPI);
        assertThat(json).contains("\"title\" : \"Offline Test API\"");
        assertThat(json).contains("\"version\" : \"2.5.0\"");

        // 2. 导出 YAML
        String yaml = OpenApiExportHelper.exportYaml(openAPI);
        assertThat(yaml).contains("title: Offline Test API");
        assertThat(yaml).contains("version: 2.5.0");

        // 3. 导出字节数组
        byte[] jsonBytes = OpenApiExportHelper.exportBytes(openAPI, OpenApiFormat.JSON);
        assertThat(new String(jsonBytes, StandardCharsets.UTF_8)).isEqualTo(json);

        // 4. 流式导出
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OpenApiExportHelper.export(openAPI, OpenApiFormat.YAML, out);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(yaml);
    }

    @Test
    @DisplayName("测试未初始化 Spring 容器时调用静态 group 门面抛出 IllegalStateException")
    void testStaticWithoutInitializationThrowsException() {
        assertThatThrownBy(OpenApiExportHelper::getInstance)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has not been initialized");

        assertThatThrownBy(OpenApiExportHelper::exportJson)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("测试空入参前置卫语句防御校验")
    void testDefensiveNullChecks() {
        assertThatThrownBy(() -> OpenApiExportHelper.exportJson((OpenAPI) null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> OpenApiExportHelper.exportYaml((OpenAPI) null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> OpenApiExportHelper.exportBytes((OpenAPI) null, OpenApiFormat.JSON))
                .isInstanceOf(IllegalArgumentException.class);

        OpenAPI openAPI = new OpenAPI();
        assertThatThrownBy(() -> OpenApiExportHelper.exportBytes(openAPI, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> OpenApiExportHelper.export(openAPI, OpenApiFormat.JSON, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("测试受管多分组模式：联动 MultipleOpenApiWebMvcResource 导出指定分组")
    void testManagedMultipleGroupExport() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<MultipleOpenApiWebMvcResource> multipleResourceProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenApiWebMvcResource> singleResourceProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenAPI> openApiProvider = mock(ObjectProvider.class);

        MultipleOpenApiWebMvcResource multipleResource = mock(MultipleOpenApiWebMvcResource.class);
        when(multipleResourceProvider.getIfAvailable()).thenReturn(multipleResource);

        String mockAdminJson = "{\"info\":{\"title\":\"Admin Spec\"}}";
        when(multipleResource.openapiJson(any(), any(), eq("admin"), any()))
                .thenReturn(mockAdminJson.getBytes(StandardCharsets.UTF_8));

        String mockAdminYaml = "info:\n  title: Admin Spec";
        when(multipleResource.openapiYaml(any(), any(), eq("admin"), any()))
                .thenReturn(mockAdminYaml.getBytes(StandardCharsets.UTF_8));

        GroupedOpenApi adminGroup = GroupedOpenApi.builder().group("admin").pathsToMatch("/api/admin/**").build();
        OpenApiExportHelper helper = new OpenApiExportHelper(
                multipleResourceProvider, singleResourceProvider, openApiProvider, List.of(adminGroup)
        );

        // 验证受管实例导出
        assertThat(helper.exportJsonString("admin")).isEqualTo(mockAdminJson);
        assertThat(helper.exportYamlString("admin")).isEqualTo(mockAdminYaml);

        // 验证静态门面代理
        assertThat(OpenApiExportHelper.exportJson("admin")).isEqualTo(mockAdminJson);
        assertThat(OpenApiExportHelper.exportYaml("admin")).isEqualTo(mockAdminYaml);

        // 验证流式写入
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OpenApiExportHelper.export("admin", OpenApiFormat.JSON, out);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(mockAdminJson);
    }

    @Test
    @DisplayName("测试受管单分组模式：联动 OpenApiWebMvcResource 导出默认规范")
    void testManagedSingleGroupExport() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<MultipleOpenApiWebMvcResource> multipleResourceProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenApiWebMvcResource> singleResourceProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenAPI> openApiProvider = mock(ObjectProvider.class);

        OpenApiWebMvcResource singleResource = mock(OpenApiWebMvcResource.class);
        when(singleResourceProvider.getIfAvailable()).thenReturn(singleResource);

        String mockDefaultJson = "{\"info\":{\"title\":\"Default Spec\"}}";
        when(singleResource.openapiJson(any(), any(), any()))
                .thenReturn(mockDefaultJson.getBytes(StandardCharsets.UTF_8));

        OpenApiExportHelper helper = new OpenApiExportHelper(
                multipleResourceProvider, singleResourceProvider, openApiProvider, List.of()
        );

        assertThat(helper.exportJsonString(null)).isEqualTo(mockDefaultJson);
        assertThat(OpenApiExportHelper.exportJson()).isEqualTo(mockDefaultJson);
    }
}

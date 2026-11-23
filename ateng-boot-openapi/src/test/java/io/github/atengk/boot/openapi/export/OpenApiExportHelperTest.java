package io.github.atengk.boot.openapi.export;

import io.github.atengk.boot.core.util.SpringUtil;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.webmvc.api.MultipleOpenApiWebMvcResource;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.GenericApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
        SpringUtil.clear();
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
        assertThat(OpenApiExportHelper.exportBytes("admin", OpenApiFormat.JSON)).isEqualTo(mockAdminJson.getBytes(StandardCharsets.UTF_8));
        assertThat(OpenApiExportHelper.exportBytes("admin", OpenApiFormat.YAML)).isEqualTo(mockAdminYaml.getBytes(StandardCharsets.UTF_8));
        assertThat(OpenApiExportHelper.exportBytes("admin")).isEqualTo(mockAdminJson.getBytes(StandardCharsets.UTF_8));

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

    @Test
    @DisplayName("测试 DisposableBean destroy 仅对称清理当前静态实例而不误清后续新实例")
    void testDisposableBeanDestroyResetsOnlyMatchingInstance() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<MultipleOpenApiWebMvcResource> multipleProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenApiWebMvcResource> singleProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenAPI> openApiProvider = mock(ObjectProvider.class);

        OpenApiExportHelper first = new OpenApiExportHelper(multipleProvider, singleProvider, openApiProvider, List.of());
        assertThat(OpenApiExportHelper.getInstance()).isSameAs(first);

        OpenApiExportHelper second = new OpenApiExportHelper(multipleProvider, singleProvider, openApiProvider, List.of());
        assertThat(OpenApiExportHelper.getInstance()).isSameAs(second);

        // 销毁旧实例不应误清活跃的新实例
        first.destroy();
        assertThat(OpenApiExportHelper.getInstance()).isSameAs(second);

        // 销毁当前实例应复位静态引用
        second.destroy();
        assertThatThrownBy(OpenApiExportHelper::getInstance)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("测试静态引用为空时 getInstance 自动通过 SpringUtil 容器兜底回退")
    void testGetInstanceFallsBackToSpringUtilWhenStaticInstanceNull() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MultipleOpenApiWebMvcResource> multipleProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenApiWebMvcResource> singleProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenAPI> openApiProvider = mock(ObjectProvider.class);

        OpenApiExportHelper helper = new OpenApiExportHelper(multipleProvider, singleProvider, openApiProvider, List.of());
        OpenApiExportHelper.setInstance(null);

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(OpenApiExportHelper.class, () -> helper);
            context.refresh();
            SpringUtil.setContext(context);

            assertThat(OpenApiExportHelper.getInstance()).isSameAs(helper);
        }
    }

    @Test
    @DisplayName("测试 exportToFile 安全落盘 JSON/YAML、自动建目录及阻断路径穿越与系统垃圾文件")
    void testExportToFileWritesSafelyAndBlocksUnsafePaths(@TempDir Path tempDir) throws Exception {
        OpenAPI openAPI = new OpenAPI().info(
                new Info().title("File Export API").version("1.0.0")
        );

        // 1. 独立脱机模式导出 YAML 并自动创建父目录
        Path nestedYaml = tempDir.resolve("nested/dir/spec.yaml");
        Path writtenYaml = OpenApiExportHelper.exportToFile(openAPI, OpenApiFormat.YAML, nestedYaml);
        assertThat(writtenYaml).isEqualTo(nestedYaml.toAbsolutePath().normalize());
        assertThat(Files.readString(writtenYaml, StandardCharsets.UTF_8)).contains("title: File Export API");

        // 2. 受管多分组模式导出默认与指定分组文件
        @SuppressWarnings("unchecked")
        ObjectProvider<MultipleOpenApiWebMvcResource> multipleProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenApiWebMvcResource> singleProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenAPI> openApiProvider = mock(ObjectProvider.class);
        MultipleOpenApiWebMvcResource multipleResource = mock(MultipleOpenApiWebMvcResource.class);
        when(multipleProvider.getIfAvailable()).thenReturn(multipleResource);

        String adminJson = "{\"info\":{\"title\":\"Admin File Spec\"}}";
        when(multipleResource.openapiJson(any(), any(), eq("admin"), any()))
                .thenReturn(adminJson.getBytes(StandardCharsets.UTF_8));
        GroupedOpenApi adminGroup = GroupedOpenApi.builder().group("admin").pathsToMatch("/admin/**").build();
        new OpenApiExportHelper(multipleProvider, singleProvider, openApiProvider, List.of(adminGroup));

        Path defaultFile = tempDir.resolve("managed/default.json");
        OpenApiExportHelper.exportToFile(defaultFile);
        assertThat(Files.readString(defaultFile, StandardCharsets.UTF_8)).isEqualTo(adminJson);

        Path adminFile = tempDir.resolve("managed/sub/admin.json");
        OpenApiExportHelper.exportToFile("admin", OpenApiFormat.JSON, adminFile);
        assertThat(Files.readString(adminFile, StandardCharsets.UTF_8)).isEqualTo(adminJson);

        // 3. 拦截非法空参、路径穿越、目录及系统隐性垃圾文件
        assertThatThrownBy(() -> OpenApiExportHelper.exportToFile(openAPI, OpenApiFormat.JSON, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenApiExportHelper.exportToFile(openAPI, OpenApiFormat.JSON, Paths.get("../escape.json")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenApiExportHelper.exportToFile(openAPI, OpenApiFormat.JSON, tempDir.resolve("../escape.json")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenApiExportHelper.exportToFile(openAPI, OpenApiFormat.JSON, tempDir.resolve(".DS_Store")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenApiExportHelper.exportToFile(openAPI, OpenApiFormat.JSON, tempDir.resolve("desktop.ini")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenApiExportHelper.exportToFile(openAPI, OpenApiFormat.JSON, tempDir.resolve("__MACOSX/spec.json")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenApiExportHelper.exportToFile(openAPI, OpenApiFormat.JSON, tempDir))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

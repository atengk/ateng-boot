package io.github.atengk.boot.sample.openapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI 样例工程端到端全场景契约集成测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = "ateng.boot.web.shutdown.drain-timeout=0s")
@DisplayName("OpenAPI 样例工程端到端契约测试")
class OpenApiSampleEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("测试 /docs 与 /doc 友好路由 302 重定向至 Swagger UI")
    void testDocsRedirect() throws Exception {
        mockMvc.perform(get("/docs"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/swagger-ui/index.html"));

        mockMvc.perform(get("/doc"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/swagger-ui/index.html"));
    }

    @Test
    @DisplayName("测试全局 OpenAPI 契约 JSON 包含元数据、状态码字典、Sa-Token/Bearer 锁头与全局参数")
    void testOpenApiJsonContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // 1. Info 元数据与状态码字典（通用 + 业务 SPI）
                .andExpect(jsonPath("$.info.title").value("ateng-boot OpenAPI 生产级契约沙箱"))
                .andExpect(jsonPath("$.info.version").value("1.0.0"))
                .andExpect(jsonPath("$.info.description", containsString("通用业务响应状态码字典")))
                .andExpect(jsonPath("$.info.description", containsString("样例工程自定义业务状态码字典")))
                .andExpect(jsonPath("$.info.description", containsString("`8001`")))
                .andExpect(jsonPath("$.info.description", containsString("`8002`")))
                // 2. Servers 列表
                .andExpect(jsonPath("$.servers[0].url").value("/"))
                .andExpect(jsonPath("$.servers[1].url").value("https://api.example.com"))
                // 3. Components SecuritySchemes 锁头 (Sa-Token + BearerAuth)
                .andExpect(jsonPath("$.components.securitySchemes.satoken.type").value("apiKey"))
                .andExpect(jsonPath("$.components.securitySchemes.satoken.in").value("header"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                // 4. 全局 SecurityRequirement
                .andExpect(jsonPath("$.security[0].satoken").isArray())
                .andExpect(jsonPath("$.security[1].bearerAuth").isArray())
                // 5. 全局请求头自动化注入 (X-Trace-Id, X-Tenant-Id 与自定义 X-App-Version)
                .andExpect(jsonPath("$.paths['/api/admin/users'].get.parameters[*].name", hasItem("X-Trace-Id")))
                .andExpect(jsonPath("$.paths['/api/admin/users'].get.parameters[*].name", hasItem("X-Tenant-Id")))
                .andExpect(jsonPath("$.paths['/api/admin/users'].get.parameters[*].name", hasItem("X-App-Version")))
                // 6. 通用标准响应补全
                .andExpect(jsonPath("$.paths['/api/admin/users'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/users'].get.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/users'].get.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/users'].get.responses['500']").exists());
    }

    @Test
    @DisplayName("测试模型转换保真：IBaseEnum 强类型枚举、ValidList 原生数组与 @FieldSensitive 脱敏警示")
    void testModelConverterFeatures() throws Exception {
        mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // 1. IBaseEnum 强类型契约 (UserStatusEnum: type=integer, enum=[1, 0], Markdown 选项字典)
                .andExpect(jsonPath("$.components.schemas.AdminUserVO.properties.status.type").value("integer"))
                .andExpect(jsonPath("$.components.schemas.AdminUserVO.properties.status.enum[0]").value(1))
                .andExpect(jsonPath("$.components.schemas.AdminUserVO.properties.status.enum[1]").value(0))
                .andExpect(jsonPath("$.components.schemas.AdminUserVO.properties.status.description", containsString("枚举选项")))
                .andExpect(jsonPath("$.components.schemas.AdminUserVO.properties.status.description", containsString("`1`: 启用 (ENABLE)")))
                .andExpect(jsonPath("$.components.schemas.AdminUserVO.properties.status.example").value(1))
                // 2. ValidList 批量数组包装转换 (paths['/api/admin/users/batch'].post.requestBody 为 array)
                .andExpect(jsonPath("$.paths['/api/admin/users/batch'].post.requestBody.content['application/json'].schema.type").value("array"))
                .andExpect(jsonPath("$.paths['/api/admin/users/batch'].post.requestBody.content['application/json'].schema.items").exists())
                // 3. @FieldSensitive 敏感字段脱敏安全警示标签
                .andExpect(jsonPath("$.components.schemas.AdminUserVO.properties.phone.description", containsString("[脱敏字段: 手机号码掩码]")))
                .andExpect(jsonPath("$.components.schemas.AdminUserVO.properties.phone.example").value("138****5678"));
    }

    @Test
    @DisplayName("测试声明式 @IgnoreGlobalHeaders 精细化与全量豁免全局请求头")
    void testIgnoreGlobalHeadersFeature() throws Exception {
        mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // 1. 正常业务端点包含全部全局请求头
                .andExpect(jsonPath("$.paths['/api/admin/users'].get.parameters[*].name", hasItem("X-Tenant-Id")))
                .andExpect(jsonPath("$.paths['/api/admin/users'].get.parameters[*].name", hasItem("X-Trace-Id")))
                .andExpect(jsonPath("$.paths['/api/admin/users'].get.parameters[*].name", hasItem("X-App-Version")))
                // 2. 登录接口声明 @IgnoreGlobalHeaders({"X-Tenant-Id"}) -> 仅排除 X-Tenant-Id，保留 X-Trace-Id 与 X-App-Version
                .andExpect(jsonPath("$.paths['/api/admin/auth/login'].post.parameters[*].name", not(hasItem("X-Tenant-Id"))))
                .andExpect(jsonPath("$.paths['/api/admin/auth/login'].post.parameters[*].name", hasItem("X-Trace-Id")))
                .andExpect(jsonPath("$.paths['/api/admin/auth/login'].post.parameters[*].name", hasItem("X-App-Version")))
                // 3. 探针接口声明无参 @IgnoreGlobalHeaders -> 全量排除所有全局请求头
                .andExpect(jsonPath("$.paths['/api/admin/auth/ping'].get.parameters").doesNotExist());
    }

    @Test
    @DisplayName("测试多业务分组路由契约划分：Admin 分组仅包含后台用户与认证接口")
    void testAdminGroupContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs/admin").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/admin/users']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/users/{id}']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/users/batch']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/auth/login']").exists())
                .andExpect(jsonPath("$.paths['/api/app/articles']").doesNotExist());
    }

    @Test
    @DisplayName("测试多业务分组路由契约划分：App 分组仅包含移动端文章接口")
    void testAppGroupContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs/app").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/app/articles']").exists())
                .andExpect(jsonPath("$.paths['/api/app/articles/{id}']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/users']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/admin/auth/login']").doesNotExist());
    }

    @Test
    @DisplayName("测试离线契约导出 REST 端点一键下载 JSON / YAML 附件")
    void testOfflineExportEndpoints() throws Exception {
        // 1. 默认分组 JSON 下载
        mockMvc.perform(get("/docs/export/json"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"openapi-default.json\""))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.info.title").value("ateng-boot OpenAPI 生产级契约沙箱"));

        // 2. 默认分组 YAML 下载
        mockMvc.perform(get("/docs/export/yaml"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"openapi-default.yaml\""))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("application/yaml")))
                .andExpect(content().string(containsString("title: ateng-boot OpenAPI 生产级契约沙箱")));

        // 3. Admin 分组指定下载
        mockMvc.perform(get("/docs/export/admin.json"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"openapi-admin.json\""))
                .andExpect(jsonPath("$.paths['/api/admin/users']").exists())
                .andExpect(jsonPath("$.paths['/api/app/articles']").doesNotExist());

        // 4. App 分组指定下载
        mockMvc.perform(get("/docs/export/app.json"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"openapi-app.json\""))
                .andExpect(jsonPath("$.paths['/api/app/articles']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/users']").doesNotExist());
    }
}

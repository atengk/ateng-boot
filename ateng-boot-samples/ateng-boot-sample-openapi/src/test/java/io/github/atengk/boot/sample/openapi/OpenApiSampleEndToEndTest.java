package io.github.atengk.boot.sample.openapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI 样例工程端到端契约集成测试
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
    @DisplayName("测试全局 OpenAPI 契约 JSON 包含元数据、状态码字典、Sa-Token 锁头与全局参数")
    void testOpenApiJsonContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // 1. Info 元数据
                .andExpect(jsonPath("$.info.title").value("ateng-boot OpenAPI 生产级契约沙箱"))
                .andExpect(jsonPath("$.info.version").value("1.0.0"))
                .andExpect(jsonPath("$.info.description", containsString("通用业务响应状态码字典")))
                // 2. Servers 列表
                .andExpect(jsonPath("$.servers[0].url").value("/"))
                // 3. Components SecuritySchemes 锁头
                .andExpect(jsonPath("$.components.securitySchemes.satoken.type").value("apiKey"))
                .andExpect(jsonPath("$.components.securitySchemes.satoken.in").value("header"))
                .andExpect(jsonPath("$.components.securitySchemes.satoken.name").value("satoken"))
                // 4. 全局 SecurityRequirement
                .andExpect(jsonPath("$.security[0].satoken").isArray())
                // 5. 全局请求头自动化注入 (X-Trace-Id 与 X-Tenant-Id)
                .andExpect(jsonPath("$.paths['/api/admin/users'].get.parameters[*].name", hasItem("X-Trace-Id")))
                .andExpect(jsonPath("$.paths['/api/admin/users'].get.parameters[*].name", hasItem("X-Tenant-Id")))
                // 6. 通用标准响应补全
                .andExpect(jsonPath("$.paths['/api/admin/users'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/users'].get.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/users'].get.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/users'].get.responses['500']").exists());
    }

    @Test
    @DisplayName("测试多业务分组路由契约划分：Admin 分组仅包含后台用户接口")
    void testAdminGroupContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs/admin").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/admin/users']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/users/{id}']").exists())
                .andExpect(jsonPath("$.paths['/api/app/articles']").doesNotExist());
    }

    @Test
    @DisplayName("测试多业务分组路由契约划分：App 分组仅包含移动端文章接口")
    void testAppGroupContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs/app").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/app/articles']").exists())
                .andExpect(jsonPath("$.paths['/api/app/articles/{id}']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/users']").doesNotExist());
    }
}

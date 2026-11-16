package io.github.atengk.boot.sample.openapi;

import io.github.atengk.boot.openapi.export.OpenApiExportHelper;
import io.github.atengk.boot.openapi.export.OpenApiFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    private static final String BASE_ADMIN_USERS = "/sample/openapi/admin/users";
    private static final String BASE_ADMIN_AUTH = "/sample/openapi/admin/auth";
    private static final String BASE_APP_ARTICLES = "/sample/openapi/app/articles";

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
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "'].get.parameters[*].name", hasItem("X-Trace-Id")))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "'].get.parameters[*].name", hasItem("X-Tenant-Id")))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "'].get.parameters[*].name", hasItem("X-App-Version")))
                // 6. 通用标准响应补全
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "'].get.responses['400']").exists())
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "'].get.responses['401']").exists())
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "'].get.responses['500']").exists());
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
                // 2. ValidList 批量数组包装转换 (paths['/sample/openapi/admin/users/batch'].post.requestBody 为 array)
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "/batch'].post.requestBody.content['application/json'].schema.type").value("array"))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "/batch'].post.requestBody.content['application/json'].schema.items").exists())
                // 3. @FieldSensitive 敏感字段脱敏安全警示标签
                .andExpect(jsonPath("$.components.schemas.AdminUserVO.properties.phone.description", containsString("[脱敏字段: 手机号码掩码]")))
                .andExpect(jsonPath("$.components.schemas.AdminUserVO.properties.phone.example").value("138****5678"))
                .andExpect(jsonPath("$.components.schemas.AdminUserVO.properties.idCard.description", containsString("[脱敏字段: 身份证号掩码]")))
                .andExpect(jsonPath("$.components.schemas.AdminUserVO.properties.email.description", containsString("[脱敏字段: 电子邮箱掩码]")))
                .andExpect(jsonPath("$.components.schemas.AdminUserVO.properties.lastLoginIp.description", containsString("[脱敏字段: IPv4 地址掩码]")))
                // 4. DTO @InEnum 注解映射：AdminUserCreateRequest.statusCode 自动生成 enum=[1,0] 与 Markdown 字典
                .andExpect(jsonPath("$.components.schemas.AdminUserCreateRequest.properties.statusCode.type").value("integer"))
                .andExpect(jsonPath("$.components.schemas.AdminUserCreateRequest.properties.statusCode.enum[0]").value(1))
                .andExpect(jsonPath("$.components.schemas.AdminUserCreateRequest.properties.statusCode.enum[1]").value(0))
                .andExpect(jsonPath("$.components.schemas.AdminUserCreateRequest.properties.statusCode.description", containsString("**枚举选项**：")))
                // 5. Controller 方法参数 @InEnum 注解映射：/page?status= 自动生成 enum=[1,0] 与 Markdown 字典
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "/page'].get.parameters[?(@.name=='status')].schema.type", hasItem("integer")))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "/page'].get.parameters[?(@.name=='status')].schema.enum", hasItem(java.util.List.of(1, 0))))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "/page'].get.parameters[?(@.name=='status')].schema.description", hasItem(containsString("**枚举选项**："))));
    }

    @Test
    @DisplayName("测试管理端用户创建 @InEnum 校验与 PageQuery/PageResult 分页契约端点")
    void testAdminUserInEnumAndPaginationContract() throws Exception {
        // 1. 合法 @InEnum statusCode 创建成功
        String validPayload = "{\"username\":\"zhangsan\",\"realName\":\"张三\",\"email\":\"zhangsan@example.com\",\"statusCode\":1}";
        mockMvc.perform(post(BASE_ADMIN_USERS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("zhangsan"));

        // 2. 非法 @InEnum statusCode=99 触发 JSR-303 快速失败拦截
        String invalidPayload = "{\"username\":\"zhangsan\",\"realName\":\"张三\",\"email\":\"zhangsan@example.com\",\"statusCode\":99}";
        mockMvc.perform(post(BASE_ADMIN_USERS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 3. 分页查询接口返回标准 Result<PageResult<AdminUserVO>>
        mockMvc.perform(get(BASE_ADMIN_USERS + "/page")
                        .param("current", "1")
                        .param("size", "1")
                        .param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.pages").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(1));

        // 4. 分页查询接口非法 @InEnum 参数触发参数校验失败
        mockMvc.perform(get(BASE_ADMIN_USERS + "/page")
                        .param("status", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("测试 HeaderParam.example 回显、@SaIgnore 免锁头与跨模块治理契约内省及 @ApiSign 请求头注入")
    void testOperationGovernanceAndHeaderExampleContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // 1. HeaderParam.example 回显（application.yml 配置的 X-App-Version example=1.2.0）
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "'].get.parameters[?(@.name=='X-App-Version')].example", hasItem("1.2.0")))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "'].get.parameters[?(@.name=='X-App-Version')].schema.example", hasItem("1.2.0")))
                // 2. @SaIgnore 公开接口自动摘除锁头（security 为空数组）并追加免鉴权契约
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_AUTH + "/login'].post.security.length()").value(0))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_AUTH + "/login'].post.description", containsString("**接口治理契约**：")))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_AUTH + "/login'].post.description", containsString("@SaIgnore")))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_AUTH + "/ping'].get.security.length()").value(0))
                // 3. @Idempotent + @SaCheckPermission 治理说明内省
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "'].post.description", containsString("**接口治理契约**：")))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "'].post.description", containsString("防重幂等")))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "'].post.description", containsString("admin:user:create")))
                // 4. @RateLimiter 治理说明内省
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "/page'].get.description", containsString("限流策略")))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "/page'].get.description", containsString("IP")))
                // 5. @ApiSign + @IpAccessControl + @ApiVersion + @SaIgnore 综合契约与四大验签请求头自动注入
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "/sync-callback'].post.security.length()").value(0))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "/sync-callback'].post.description", containsString("接口加签")))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "/sync-callback'].post.description", containsString("IP 访问控制")))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "/sync-callback'].post.description", containsString("API 版本")))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "/sync-callback'].post.parameters[*].name", hasItem("X-App-Id")))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "/sync-callback'].post.parameters[*].name", hasItem("X-Timestamp")))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "/sync-callback'].post.parameters[*].name", hasItem("X-Nonce")))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "/sync-callback'].post.parameters[*].name", hasItem("X-Signature")))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "/sync-callback'].post.parameters[?(@.name=='X-Signature')].description", hasItem("自定义商户网关数字签名")))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "/sync-callback'].post.parameters[?(@.name=='X-Signature')]", hasSize(1)));

        // 6. 实际调用开放回调端点（携带 @ApiVersion("2") 要求的 X-API-Version: 2）验证端到端运行时正常响应
        mockMvc.perform(post(BASE_ADMIN_USERS + "/sync-callback")
                        .header("X-API-Version", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"sync_bot\",\"realName\":\"同步机器人\",\"email\":\"bot@example.com\",\"statusCode\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("SYNC_ACK:sync_bot"));
    }

    @Test
    @DisplayName("测试声明式 @IgnoreGlobalHeaders 精细化与全量豁免全局请求头")
    void testIgnoreGlobalHeadersFeature() throws Exception {
        mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // 1. 正常业务端点包含全部全局请求头
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "'].get.parameters[*].name", hasItem("X-Tenant-Id")))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "'].get.parameters[*].name", hasItem("X-Trace-Id")))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "'].get.parameters[*].name", hasItem("X-App-Version")))
                // 2. 登录接口声明 @IgnoreGlobalHeaders({"X-Tenant-Id"}) -> 仅排除 X-Tenant-Id，保留 X-Trace-Id 与 X-App-Version
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_AUTH + "/login'].post.parameters[*].name", not(hasItem("X-Tenant-Id"))))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_AUTH + "/login'].post.parameters[*].name", hasItem("X-Trace-Id")))
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_AUTH + "/login'].post.parameters[*].name", hasItem("X-App-Version")))
                // 3. 探针接口声明无参 @IgnoreGlobalHeaders -> 全量排除所有全局请求头
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_AUTH + "/ping'].get.parameters").doesNotExist());
    }

    @Test
    @DisplayName("测试多业务分组路由契约划分：Admin 分组仅包含后台用户与认证接口")
    void testAdminGroupContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs/admin").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "']").exists())
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "/{id}']").exists())
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "/batch']").exists())
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_AUTH + "/login']").exists())
                .andExpect(jsonPath("$.paths['" + BASE_APP_ARTICLES + "']").doesNotExist());
    }

    @Test
    @DisplayName("测试多业务分组路由契约划分：App 分组仅包含移动端文章接口")
    void testAppGroupContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs/app").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + BASE_APP_ARTICLES + "']").exists())
                .andExpect(jsonPath("$.paths['" + BASE_APP_ARTICLES + "/{id}']").exists())
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "']").doesNotExist())
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_AUTH + "/login']").doesNotExist());
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
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "']").exists())
                .andExpect(jsonPath("$.paths['" + BASE_APP_ARTICLES + "']").doesNotExist());

        // 4. App 分组指定下载
        mockMvc.perform(get("/docs/export/app.json"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"openapi-app.json\""))
                .andExpect(jsonPath("$.paths['" + BASE_APP_ARTICLES + "']").exists())
                .andExpect(jsonPath("$.paths['" + BASE_ADMIN_USERS + "']").doesNotExist());
    }

    @Test
    @DisplayName("测试 OpenApiExportHelper 公开门面类直接导出 JSON / YAML / Bytes 契约")
    void testOpenApiExportHelperDirectJavaApi() throws Exception {
        // 1. 静态方式导出默认分组 JSON 与 YAML
        String defaultJson = OpenApiExportHelper.exportJson();
        assertThat(defaultJson).contains("ateng-boot OpenAPI 生产级契约沙箱");

        String defaultYaml = OpenApiExportHelper.exportYaml();
        assertThat(defaultYaml).contains("title: ateng-boot OpenAPI 生产级契约沙箱");

        // 2. 静态方式导出 Admin 分组 JSON
        String adminJson = OpenApiExportHelper.exportJson("admin");
        assertThat(adminJson).contains(BASE_ADMIN_USERS);
        assertThat(adminJson).doesNotContain(BASE_APP_ARTICLES);

        // 3. 静态方式导出字节数组
        byte[] adminJsonBytes = OpenApiExportHelper.exportBytes("admin", OpenApiFormat.JSON);
        assertThat(adminJsonBytes).isNotEmpty();
        assertThat(new String(adminJsonBytes, StandardCharsets.UTF_8)).isEqualTo(adminJson);

        // 4. 流式导出至 OutputStream
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OpenApiExportHelper.export("admin", OpenApiFormat.YAML, out);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("title: ateng-boot OpenAPI 生产级契约沙箱");
    }
}

package io.github.atengk.boot.openapi.export;

import io.github.atengk.boot.openapi.guard.OpenApiGuardFilter;
import io.github.atengk.boot.openapi.properties.GuardMode;
import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI 离线契约导出 REST 控制器单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("OpenApiExportController 离线导出与安全守卫联动测试")
class OpenApiExportControllerTest {

    private OpenApiExportHelper exportHelper;
    private OpenApiExportController controller;
    private OpenApiProperties properties;
    private OpenApiGuardFilter filter;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        exportHelper = mock(OpenApiExportHelper.class);
        controller = new OpenApiExportController(exportHelper);
        properties = new OpenApiProperties();
        filter = new OpenApiGuardFilter(properties);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(filter)
                .build();
    }

    @Test
    @DisplayName("测试 /docs/export/json 下载默认分组 JSON 契约附件")
    void testExportDefaultJson() throws Exception {
        byte[] mockBytes = "{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"Test\"}}".getBytes(StandardCharsets.UTF_8);
        when(exportHelper.exportBytes(eq(null), eq(OpenApiFormat.JSON), any())).thenReturn(mockBytes);

        mockMvc.perform(get("/docs/export/json"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"openapi-default.json\""))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().bytes(mockBytes));
    }

    @Test
    @DisplayName("测试 /docs/export/yaml 下载默认分组 YAML 契约附件")
    void testExportDefaultYaml() throws Exception {
        byte[] mockBytes = "openapi: 3.0.1\ninfo:\n  title: Test".getBytes(StandardCharsets.UTF_8);
        when(exportHelper.exportBytes(eq(null), eq(OpenApiFormat.YAML), any())).thenReturn(mockBytes);

        mockMvc.perform(get("/docs/export/yaml"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"openapi-default.yaml\""))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("application/yaml")))
                .andExpect(content().bytes(mockBytes));
    }

    @Test
    @DisplayName("测试 /docs/export/{group}.json 下载指定分组 JSON 契约附件")
    void testExportGroupJson() throws Exception {
        byte[] mockBytes = "{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"Admin Spec\"}}".getBytes(StandardCharsets.UTF_8);
        when(exportHelper.exportBytes(eq("admin"), eq(OpenApiFormat.JSON), any())).thenReturn(mockBytes);

        mockMvc.perform(get("/docs/export/admin.json"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"openapi-admin.json\""))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().bytes(mockBytes));
    }

    @Test
    @DisplayName("测试 /docs/export/{group}.yaml 下载指定分组 YAML 契约附件")
    void testExportGroupYaml() throws Exception {
        byte[] mockBytes = "openapi: 3.0.1\ninfo:\n  title: App Spec".getBytes(StandardCharsets.UTF_8);
        when(exportHelper.exportBytes(eq("app"), eq(OpenApiFormat.YAML), any())).thenReturn(mockBytes);

        mockMvc.perform(get("/docs/export/app.yaml"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"openapi-app.yaml\""))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("application/yaml")))
                .andExpect(content().bytes(mockBytes));
    }

    @Test
    @DisplayName("测试当指定未知分组时响应 HTTP 404 Not Found")
    void testExportUnknownGroupNotFound() throws Exception {
        when(exportHelper.exportBytes(eq("non-existent"), any(), any()))
                .thenThrow(new IllegalArgumentException("OpenAPI group not found: non-existent"));

        mockMvc.perform(get("/docs/export/non-existent.json"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("测试安全守卫联动：当 enabled=false 物理切断时响应 HTTP 404")
    void testGuardCutoffWhenDisabled() throws Exception {
        properties.setEnabled(false);

        mockMvc.perform(get("/docs/export/json"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("测试安全守卫联动：当开启 TOKEN 守卫时未提供令牌被 401 拦截，提供正确令牌放行")
    void testGuardTokenAuthIntercept() throws Exception {
        properties.getGuard().setMode(GuardMode.TOKEN);
        properties.getGuard().getToken().setSecret("secret-doc-token-12345");

        byte[] mockBytes = "{\"openapi\":\"3.0.1\"}".getBytes(StandardCharsets.UTF_8);
        when(exportHelper.exportBytes(any(), any(), any())).thenReturn(mockBytes);

        // 1. 无 Token 请求 -> 401
        mockMvc.perform(get("/docs/export/json"))
                .andExpect(status().isUnauthorized());

        // 2. 携带错误 Token -> 401
        mockMvc.perform(get("/docs/export/json").header("X-Doc-Token", "wrong-token"))
                .andExpect(status().isUnauthorized());

        // 3. 携带正确 Token -> 200 放行
        mockMvc.perform(get("/docs/export/json").header("X-Doc-Token", "secret-doc-token-12345"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(mockBytes));
    }

    @Test
    @DisplayName("测试安全守卫联动：当开启 IP 白名单时非授权 IP 响应 403 Forbidden")
    void testGuardIpWhitelistIntercept() throws Exception {
        properties.getGuard().setMode(GuardMode.IP_WHITELIST);
        properties.getGuard().getIpWhitelist().setCidrs(Collections.singletonList("127.0.0.1"));

        byte[] mockBytes = "{\"openapi\":\"3.0.1\"}".getBytes(StandardCharsets.UTF_8);
        when(exportHelper.exportBytes(any(), any(), any())).thenReturn(mockBytes);

        // 1. 非白名单 IP -> 403
        mockMvc.perform(get("/docs/export/json").with(req -> {
            req.setRemoteAddr("192.168.1.100");
            return req;
        })).andExpect(status().isForbidden());

        // 2. 白名单 IP (127.0.0.1) -> 200
        mockMvc.perform(get("/docs/export/json").with(req -> {
            req.setRemoteAddr("127.0.0.1");
            return req;
        })).andExpect(status().isOk());
    }
}

package io.github.atengk.boot.sample.web;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 声明式 API 多版本控制端到端集成测试
 * 验证 URL 路径版本前缀、请求头版本分流、缺省版本回退与方法覆盖类级别
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Web 声明式 API 多版本分流端到端测试")
class WebVersionSampleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    @AfterEach
    void cleanContext() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("1. URL 路径版本分流：/v1/api/... 路由至 V1 控制器")
    void shouldRouteToV1ByUrlPathPrefix() throws Exception {
        mockMvc.perform(get("/v1/api/sample/version-user/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.version").value("1"))
                .andExpect(jsonPath("$.data.contract").value("legacy"));
    }

    @Test
    @DisplayName("2. URL 路径版本分流：/v2/api/... 路由至 V2 控制器")
    void shouldRouteToV2ByUrlPathPrefix() throws Exception {
        mockMvc.perform(get("/v2/api/sample/version-user/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.version").value("2"))
                .andExpect(jsonPath("$.data.level").value("VIP"))
                .andExpect(jsonPath("$.data.contract").value("enhanced"));
    }

    @Test
    @DisplayName("3. 请求头版本分流：携带 X-API-Version: 2 路由至 V2 控制器")
    void shouldRouteToV2ByHeader() throws Exception {
        mockMvc.perform(get("/api/sample/version-user/info")
                        .header("X-API-Version", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.version").value("2"))
                .andExpect(jsonPath("$.data.contract").value("enhanced"));
    }

    @Test
    @DisplayName("4. 请求头版本分流：携带 X-API-Version: 1 路由至 V1 控制器")
    void shouldRouteToV1ByHeader() throws Exception {
        mockMvc.perform(get("/api/sample/version-user/info")
                        .header("X-API-Version", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.version").value("1"))
                .andExpect(jsonPath("$.data.contract").value("legacy"));
    }

    @Test
    @DisplayName("5. 缺省版本回退：未提供 URL 前缀与 Header 时默认路由至配置的 default-version 1")
    void shouldFallbackToDefaultVersion() throws Exception {
        mockMvc.perform(get("/api/sample/version-user/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.version").value("1"))
                .andExpect(jsonPath("$.data.contract").value("legacy"));
    }

    @Test
    @DisplayName("6. 方法级版本覆盖：@ApiVersion('2.1') 覆盖类级别版本")
    void shouldSupportMethodLevelVersionOverride() throws Exception {
        // 通过 URL 路径前缀访问 2.1 特性端点
        mockMvc.perform(get("/v2.1/api/sample/version-user/feature"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("feature_v2.1"));

        // 通过 Header 访问 2.1 特性端点
        mockMvc.perform(get("/api/sample/version-user/feature")
                        .header("X-API-Version", "2.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("feature_v2.1"));
    }
}

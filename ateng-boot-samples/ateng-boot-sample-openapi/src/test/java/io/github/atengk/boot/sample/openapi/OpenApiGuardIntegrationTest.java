package io.github.atengk.boot.sample.openapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI 生产安全访问守卫集成测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("OpenAPI 生产安全访问守卫集成测试")
class OpenApiGuardIntegrationTest {

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "ateng.boot.openapi.enabled=false",
            "ateng.boot.web.shutdown.drain-timeout=0s"
    })
    @DisplayName("场景 1：生产环境物理切断 (enabled=false)")
    class DisabledCutoffTest {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("测试禁用时 /swagger-ui/index.html 响应 404 物理切断")
        void testSwaggerUiNotFound() throws Exception {
            mockMvc.perform(get("/swagger-ui/index.html"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("测试禁用时 /v3/api-docs 接口响应 404 物理切断")
        void testApiDocsNotFound() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("测试禁用时重定向路由 /docs 响应 404")
        void testDocsRedirectNotFound() throws Exception {
            mockMvc.perform(get("/docs"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "ateng.boot.openapi.guard.mode=BASIC",
            "ateng.boot.openapi.guard.basic.username=admin",
            "ateng.boot.openapi.guard.basic.password=secretPass",
            "ateng.boot.web.shutdown.drain-timeout=0s"
    })
    @DisplayName("场景 2：HTTP Basic Auth 访问守卫")
    class BasicAuthGuardTest {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("测试未携带 Basic 凭据访问 /docs 响应 401 并返回质询头")
        void testUnauthorizedChallenge() throws Exception {
            mockMvc.perform(get("/docs"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Basic realm=\"OpenAPI Document Guard\""));
        }

        @Test
        @DisplayName("测试输入错误凭据拒绝访问 401")
        void testWrongCredentials() throws Exception {
            String wrongCreds = Base64.getEncoder().encodeToString("admin:wrong".getBytes(StandardCharsets.UTF_8));
            mockMvc.perform(get("/docs").header("Authorization", "Basic " + wrongCreds))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("测试输入正确凭据正常放行并 302 重定向")
        void testValidCredentialsAllowed() throws Exception {
            String validCreds = Base64.getEncoder().encodeToString("admin:secretPass".getBytes(StandardCharsets.UTF_8));
            mockMvc.perform(get("/docs").header("Authorization", "Basic " + validCreds))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "/swagger-ui/index.html"));
        }

        @Test
        @DisplayName("测试静态资源在 Basic 守卫模式下白名单安全放行")
        void testStaticResourcesWhitelisted() throws Exception {
            mockMvc.perform(get("/swagger-ui/swagger-ui.css"))
                    // 只要不是 401 拦截即代表通过守卫白名单放行
                    .andExpect(status().isOk());
        }
    }
}

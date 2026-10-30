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
 * OpenAPI 生产安全访问守卫端到端集成测试
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

        @Test
        @DisplayName("测试禁用时离线导出端点 /docs/export/json 响应 404")
        void testExportEndpointNotFound() throws Exception {
            mockMvc.perform(get("/docs/export/json"))
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
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "ateng.boot.openapi.guard.mode=TOKEN",
            "ateng.boot.openapi.guard.token.secret=sample-token-secret",
            "ateng.boot.openapi.guard.token.header-name=X-Doc-Token",
            "ateng.boot.openapi.guard.token.allow-query-param=true",
            "ateng.boot.openapi.guard.token.query-param-name=doc-token",
            "ateng.boot.web.shutdown.drain-timeout=0s"
    })
    @DisplayName("场景 3：静态令牌 (TOKEN) 标头与 Query 参数访问守卫")
    class TokenAuthGuardTest {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("测试未携带 Token 访问 /docs 响应 401")
        void testMissingTokenRejected() throws Exception {
            mockMvc.perform(get("/docs"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("测试携带错误 Token 访问 /docs 响应 401")
        void testWrongTokenRejected() throws Exception {
            mockMvc.perform(get("/docs").header("X-Doc-Token", "wrong-secret"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("测试通过 Header X-Doc-Token 携带正确令牌放行并重定向")
        void testValidHeaderTokenAllowed() throws Exception {
            mockMvc.perform(get("/docs").header("X-Doc-Token", "sample-token-secret"))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "/swagger-ui/index.html"));
        }

        @Test
        @DisplayName("测试通过 Query 参数 ?doc-token=xxx 携带正确令牌放行")
        void testValidQueryTokenAllowed() throws Exception {
            mockMvc.perform(get("/docs").param("doc-token", "sample-token-secret"))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "/swagger-ui/index.html"));
        }

        @Test
        @DisplayName("测试离线导出端点同样受 Token 守卫管辖")
        void testExportGuardedByToken() throws Exception {
            // 无 Token -> 401
            mockMvc.perform(get("/docs/export/json"))
                    .andExpect(status().isUnauthorized());

            // 有 Token -> 200
            mockMvc.perform(get("/docs/export/json").header("X-Doc-Token", "sample-token-secret"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "ateng.boot.openapi.guard.mode=IP_WHITELIST",
            "ateng.boot.openapi.guard.ip-whitelist.cidrs=10.0.0.0/8,127.0.0.1",
            "ateng.boot.openapi.guard.ip-whitelist.trusted-header=X-Forwarded-For",
            "ateng.boot.web.shutdown.drain-timeout=0s"
    })
    @DisplayName("场景 4：CIDR 白名单与受信任反代标头网络防火墙")
    class IpWhitelistGuardTest {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("测试直接物理 IP 为 127.0.0.1 时允许访问")
        void testLocalhostIpAllowed() throws Exception {
            mockMvc.perform(get("/docs").with(req -> {
                req.setRemoteAddr("127.0.0.1");
                return req;
            })).andExpect(status().isFound());
        }

        @Test
        @DisplayName("测试未授权 IP (192.168.1.50) 访问被 403 Forbidden 拦截")
        void testUnauthorizedIpRejected() throws Exception {
            mockMvc.perform(get("/docs").with(req -> {
                req.setRemoteAddr("192.168.1.50");
                return req;
            })).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("测试受信任反代标头 X-Forwarded-For 携带内网 CIDR IP (10.10.1.2) 放行")
        void testTrustedHeaderCidrAllowed() throws Exception {
            mockMvc.perform(get("/docs")
                    .header("X-Forwarded-For", "10.10.1.2, 192.168.1.1")
                    .with(req -> {
                        req.setRemoteAddr("192.168.1.1");
                        return req;
                    })).andExpect(status().isFound());
        }

        @Test
        @DisplayName("测试受信任反代标头 X-Forwarded-For 客户端伪造外网 IP (172.16.0.1) 拦截 403")
        void testTrustedHeaderNonWhitelistedRejected() throws Exception {
            mockMvc.perform(get("/docs")
                    .header("X-Forwarded-For", "172.16.0.1")
                    .with(req -> {
                        req.setRemoteAddr("127.0.0.1");
                        return req;
                    })).andExpect(status().isForbidden());
        }
    }
}

package io.github.atengk.boot.sample.satoken;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.satoken.util.LoginHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sa-Token 全域强制登录模式 (check-login=true) 下的多账号体系路由校验集成测试
 * 验证 login-type-routes 路由隔离、跨端防越权拦截、多 Token 并存上下文对齐与 primary-login-type 兜底
 *
 * @author Ateng
 * @since 2026-11-17
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ateng.boot.satoken.primary-login-type=login",
        "ateng.boot.satoken.interceptor.check-login=true",
        "ateng.boot.satoken.interceptor.login-type-routes.admin[0]=/sample/satoken/multi-account/admin/**",
        "ateng.boot.satoken.interceptor.login-type-routes.member[0]=/sample/satoken/multi-account/member/**"
})
@DisplayName("Sa-Token check-login=true 多账号路由映射端到端集成测试")
class SampleSaTokenMultiAccountCheckLoginIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String adminToken;
    private String memberToken;
    private String defaultToken;

    @AfterEach
    void tearDown() {
        try {
            if (adminToken != null && !adminToken.isBlank()) {
                LoginHelper.logoutByTokenValue("admin", adminToken);
            }
            if (memberToken != null && !memberToken.isBlank()) {
                LoginHelper.logoutByTokenValue("member", memberToken);
            }
            if (defaultToken != null && !defaultToken.isBlank()) {
                LoginHelper.logoutByTokenValue(defaultToken);
            }
        } finally {
            adminToken = null;
            memberToken = null;
            defaultToken = null;
            UserContextHolder.clear();
            TenantContextHolder.clear();
        }
    }

    @Test
    @DisplayName("1. 多账号路由隔离与防越权：admin 与 member 按路径精确路由校验并精准同步上下文")
    void shouldRouteCheckLoginByLoginTypeRoutesAndAlignContext() throws Exception {
        // 1. 登录管理端 (admin) 与会员端 (member)（两者登录端点均标有 @Anonymous，严格模式下可直接放行签发）
        adminToken = extractToken(mockMvc.perform(post("/sample/satoken/multi-account/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"admin-901\",\"username\":\"ops_admin\",\"tenantId\":\"tenant_admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn());

        memberToken = extractToken(mockMvc.perform(post("/sample/satoken/multi-account/member/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"member-902\",\"username\":\"vip_buyer\",\"tenantId\":\"tenant_mall\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn());

        // 2. 携带 satoken-admin 请求管理端无显式 checkLogin 的 /admin/overview -> 200 且上下文绑定 admin
        mockMvc.perform(get("/sample/satoken/multi-account/admin/overview")
                        .header("satoken-admin", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value("admin-901"))
                .andExpect(jsonPath("$.data.tenantId").value("tenant_admin"))
                .andExpect(jsonPath("$.data.channel").value("admin"));

        // 3. 仅携带 satoken-member 越权访问管理端 /admin/overview -> 拦截并返回 401
        mockMvc.perform(get("/sample/satoken/multi-account/admin/overview")
                        .header("satoken-member", memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.success").value(false));

        // 4. 同时携带 satoken-admin 与 satoken-member 访问会员端 /member/overview -> 上下文严格对齐 member（防串号）
        mockMvc.perform(get("/sample/satoken/multi-account/member/overview")
                        .header("satoken-admin", adminToken)
                        .header("satoken-member", memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value("member-902"))
                .andExpect(jsonPath("$.data.tenantId").value("tenant_mall"))
                .andExpect(jsonPath("$.data.channel").value("member"));

        // 5. 未命中 login-type-routes 的默认接口 (/auth/current-tenant) 回退校验 primary-login-type=login
        mockMvc.perform(get("/sample/satoken/auth/current-tenant")
                        .header("satoken-admin", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.success").value(false));

        defaultToken = extractToken(mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"10086\",\"username\":\"default_user\",\"tenantId\":\"tenant_sz\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn());

        mockMvc.perform(get("/sample/satoken/auth/current-tenant")
                        .header("satoken", defaultToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("tenant_sz"));
    }

    private String extractToken(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").asText();
    }
}

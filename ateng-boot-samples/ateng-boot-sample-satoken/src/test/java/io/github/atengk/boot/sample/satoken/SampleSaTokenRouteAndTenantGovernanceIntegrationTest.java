package io.github.atengk.boot.sample.satoken;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.context.SaTokenRouteContextHolder;
import io.github.atengk.boot.satoken.spi.TenantHeaderSwitchValidator;
import io.github.atengk.boot.satoken.util.LoginHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sa-Token 请求链路与路由鉴权/租户闭环端到端集成测试
 * 联合验证：
 * 1. superAdminRoles / superAdminPermissions 收敛与 DataPermissionContextHolder 弱依赖自动桥接与清理 (#1077)
 * 2. @SaCheckPermission / @CheckDataOwner 按多账号路由自动识别 loginType (#1076)
 * 3. 未登录 X-Tenant-Id 解析、白名单带 Token 防绕过、平台超管受控切租户与 SPI 拒绝 Fail-Fast (#1078)
 * 4. YAML path-permissions 静态路径权限规则与共享路径多账号活跃体系探测放行 (#1095)
 *
 * @author Ateng
 * @since 2026-11-19
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(SampleSaTokenRouteAndTenantGovernanceIntegrationTest.GovernanceValidatorConfig.class)
@TestPropertySource(properties = {
        "ateng.boot.satoken.primary-login-type=admin",
        "ateng.boot.satoken.super-admin-roles[0]=super_admin",
        "ateng.boot.satoken.super-admin-permissions=",
        "ateng.boot.satoken.interceptor.check-login=true",
        "ateng.boot.satoken.interceptor.auto-route-annotation-type=true",
        "ateng.boot.satoken.interceptor.login-type-routes.admin[0]=/sample/satoken/governance/admin/**",
        "ateng.boot.satoken.interceptor.login-type-routes.member[0]=/sample/satoken/governance/member/**",
        "ateng.boot.satoken.interceptor.shared-login-type-paths[0]=/sample/satoken/governance/shared/**",
        "ateng.boot.satoken.interceptor.path-permissions[0].path-pattern=/sample/satoken/governance/shared/enums/**",
        "ateng.boot.satoken.interceptor.path-permissions[0].http-method=GET",
        "ateng.boot.satoken.interceptor.path-permissions[0].permissions[0]=system:enum:list",
        "ateng.boot.satoken.tenant.header-name=X-Tenant-Id",
        "ateng.boot.satoken.tenant.allow-anonymous-header=true",
        "ateng.boot.satoken.tenant.allow-super-admin-switch=true",
        "ateng.boot.satoken.tenant.platform-tenant-ids[0]=0"
})
@DisplayName("Sa-Token 路由鉴权、超管桥接、租户头与共享路径端到端集成测试")
class SampleSaTokenRouteAndTenantGovernanceIntegrationTest {

    @TestConfiguration
    static class GovernanceValidatorConfig {
        @Bean
        TenantHeaderSwitchValidator tenantHeaderSwitchValidator() {
            return (targetTenantId, currentTenantId, loginType, anonymous) ->
                    !"tenant-blocked".equals(targetTenantId);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String superAdminToken;
    private String tenantAdminToken;
    private String memberToken;
    private String memberNoPermToken;

    @BeforeEach
    void syncStaticConfigForCurrentContext() {
        LoginHelper.setPrimaryLoginType("admin");
        LoginHelper.setSuperAdminRoles(java.util.List.of("super_admin"));
        LoginHelper.setSuperAdminPermissions(java.util.Collections.emptyList());
    }

    @AfterAll
    static void restoreStaticDefaults() {
        LoginHelper.setPrimaryLoginType(cn.dev33.satoken.stp.StpUtil.TYPE);
        LoginHelper.resetSuperAdminConfig();
    }

    @AfterEach
    void tearDown() {
        try {
            logoutIfPresent("admin", superAdminToken);
            logoutIfPresent("admin", tenantAdminToken);
            logoutIfPresent("member", memberToken);
            logoutIfPresent("member", memberNoPermToken);
        } finally {
            superAdminToken = null;
            tenantAdminToken = null;
            memberToken = null;
            memberNoPermToken = null;
            UserContextHolder.clear();
            TenantContextHolder.clear();
            DataPermissionContextHolder.clear();
            SaTokenRouteContextHolder.clear();
        }
    }

    private void logoutIfPresent(String loginType, String token) {
        if (token != null && !token.isBlank()) {
            LoginHelper.logoutByTokenValue(loginType, token);
        }
    }

    @Test
    @DisplayName("1. 超管角色收敛与 DataPermissionContextHolder 桥接 + 无 type 注解按多账号路由自动鉴权")
    void shouldBridgeDataPermissionSuperAdminAndAutoRouteAnnotations() throws Exception {
        // 1.1 签发普通租户管理员（角色含 "admin" + 通配符权限 "*"，但已被 super-admin-roles=super_admin 与空权限列表收敛为非超管）
        tenantAdminToken = extractToken(mockMvc.perform(post("/sample/satoken/multi-account/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"ta-1\",\"username\":\"tenant_admin\",\"tenantId\":\"tenant-a\","
                                + "\"roles\":[\"admin\"],\"permissions\":[\"*\",\"system:user:export\"]}"))
                .andExpect(status().isOk())
                .andReturn());

        // 1.2 签发平台超级管理员（角色 "super_admin"，租户 "0"）
        superAdminToken = extractToken(mockMvc.perform(post("/sample/satoken/multi-account/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"sa-1\",\"username\":\"platform_sa\",\"tenantId\":\"0\","
                                + "\"roles\":[\"super_admin\"],\"permissions\":[\"system:user:export\"]}"))
                .andExpect(status().isOk())
                .andReturn());

        // 1.3 签发会员（路由 /member/**，具备 member:order:export 与 system:enum:list）
        memberToken = extractToken(mockMvc.perform(post("/sample/satoken/multi-account/member/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"mem-1\",\"username\":\"vip_mem\",\"tenantId\":\"tenant-mall\","
                                + "\"roles\":[\"member\"],\"permissions\":[\"member:order:export\",\"system:enum:list\"]}"))
                .andExpect(status().isOk())
                .andReturn());

        // 2. 租户管理员访问无 type 的 @SaCheckPermission("system:user:export")：
        //    自动路由命中 admin 体系放行，DataPermissionContextHolder.isSuperAdmin() 必须为 false，且伪造 X-Tenant-Id 被忽略
        mockMvc.perform(get("/sample/satoken/governance/admin/users/export")
                        .header("satoken-admin", tenantAdminToken)
                        .header("X-Tenant-Id", "tenant-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value("ta-1"))
                .andExpect(jsonPath("$.data.tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.data.routeLoginType").value("admin"))
                .andExpect(jsonPath("$.data.dataPermissionSuperAdmin").value(false));
        assertThat(DataPermissionContextHolder.isSuperAdmin()).isFalse();
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(SaTokenRouteContextHolder.get()).isNull();

        // 3. 平台超管访问同一接口并携带合法 X-Tenant-Id=tenant-b：
        //    DataPermissionContextHolder.isSuperAdmin() 为 true，租户上下文受控切换为 tenant-b，结束后全量清理
        mockMvc.perform(get("/sample/satoken/governance/admin/users/export")
                        .header("satoken-admin", superAdminToken)
                        .header("X-Tenant-Id", "tenant-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value("sa-1"))
                .andExpect(jsonPath("$.data.tenantId").value("tenant-b"))
                .andExpect(jsonPath("$.data.dataPermissionSuperAdmin").value(true));
        assertThat(DataPermissionContextHolder.isSuperAdmin()).isFalse();
        assertThat(TenantContextHolder.getTenantId()).isNull();

        // 4. 平台超管切换被 SPI 拒绝的租户 tenant-blocked -> Fail-Fast 403
        mockMvc.perform(get("/sample/satoken/governance/admin/users/export")
                        .header("satoken-admin", superAdminToken)
                        .header("X-Tenant-Id", "tenant-blocked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.success").value(false));
        assertThat(DataPermissionContextHolder.isSuperAdmin()).isFalse();
        assertThat(TenantContextHolder.getTenantId()).isNull();

        // 5. 会员访问无 type 的 @SaCheckPermission("member:order:export") 与 @CheckDataOwner(expr="#userId")
        mockMvc.perform(get("/sample/satoken/governance/member/orders/export")
                        .header("satoken-member", memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value("mem-1"))
                .andExpect(jsonPath("$.data.routeLoginType").value("member"));

        mockMvc.perform(get("/sample/satoken/governance/member/profile/mem-1")
                        .header("satoken-member", memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 水平越权访问他人资料 -> 403
        mockMvc.perform(get("/sample/satoken/governance/member/profile/mem-other")
                        .header("satoken-member", memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("2. 匿名白名单 X-Tenant-Id 解析、非法/SPI 拒绝与带 Token 防绕过")
    void shouldHandleAnonymousTenantHeaderAndPreventBypassWithToken() throws Exception {
        // 2.1 匿名请求携带合法 X-Tenant-Id -> 成功解析并清理
        mockMvc.perform(get("/sample/satoken/governance/public/tenant-config")
                        .header("X-Tenant-Id", "tenant-init"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.tenantId").value("tenant-init"));
        assertThat(TenantContextHolder.getTenantId()).isNull();

        // 2.2 匿名请求携带非法格式 -> 400 Fail-Fast
        mockMvc.perform(get("/sample/satoken/governance/public/tenant-config")
                        .header("X-Tenant-Id", "../etc/passwd"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));

        // 2.3 匿名请求携带被 SPI 拒绝的租户 -> 403 Fail-Fast
        mockMvc.perform(get("/sample/satoken/governance/public/tenant-config")
                        .header("X-Tenant-Id", "tenant-blocked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.success").value(false));

        // 2.4 白名单携带普通已登录用户 Token + 伪造 X-Tenant-Id -> 按已登录规则锁定会话租户防绕过
        tenantAdminToken = extractToken(mockMvc.perform(post("/sample/satoken/multi-account/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"ta-2\",\"username\":\"ta2\",\"tenantId\":\"tenant-own\","
                                + "\"roles\":[\"admin\"],\"permissions\":[]}"))
                .andExpect(status().isOk())
                .andReturn());

        mockMvc.perform(get("/sample/satoken/governance/public/tenant-config")
                        .header("satoken-admin", tenantAdminToken)
                        .header("X-Tenant-Id", "tenant-evil"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value("ta-2"))
                .andExpect(jsonPath("$.data.tenantId").value("tenant-own"));
    }

    @Test
    @DisplayName("3. YAML path-permissions 与 shared-login-type-paths 跨端探测放行及权限拦截")
    void shouldEnforceYamlPathPermissionsAndSharedActiveLoginTypeProbe() throws Exception {
        memberToken = extractToken(mockMvc.perform(post("/sample/satoken/multi-account/member/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"mem-enum-1\",\"username\":\"mem_enum\",\"tenantId\":\"tenant-mall\","
                                + "\"roles\":[\"member\"],\"permissions\":[\"system:enum:list\"]}"))
                .andExpect(status().isOk())
                .andReturn());

        memberNoPermToken = extractToken(mockMvc.perform(post("/sample/satoken/multi-account/member/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"mem-enum-2\",\"username\":\"mem_noperm\",\"tenantId\":\"tenant-mall\","
                                + "\"roles\":[\"member\"],\"permissions\":[\"member:points:view\"]}"))
                .andExpect(status().isOk())
                .andReturn());

        // 3.1 primary-login-type=admin 且 check-login=true 下，持有 member Token 且有权限 -> 探测 member 放行
        mockMvc.perform(get("/sample/satoken/governance/shared/enums/OrderStatus")
                        .header("satoken-member", memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value("mem-enum-1"))
                .andExpect(jsonPath("$.data.routeLoginType").value("member"))
                .andExpect(jsonPath("$.data.scene").value("shared-enum:OrderStatus"));

        // 3.2 持有 member Token 但缺少 YAML path-permissions 要求的 system:enum:list -> 403
        mockMvc.perform(get("/sample/satoken/governance/shared/enums/OrderStatus")
                        .header("satoken-member", memberNoPermToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.success").value(false));

        // 3.3 未携带任何 Token -> 401
        mockMvc.perform(get("/sample/satoken/governance/shared/enums/OrderStatus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.success").value(false));
    }

    private String extractToken(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").asText();
    }
}

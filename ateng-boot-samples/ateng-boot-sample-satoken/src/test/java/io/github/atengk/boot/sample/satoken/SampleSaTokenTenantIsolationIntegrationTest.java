package io.github.atengk.boot.sample.satoken;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.satoken.cache.DefaultUserRolePermissionLoader;
import io.github.atengk.boot.satoken.cache.PermissionCacheService;
import io.github.atengk.boot.satoken.cache.UserRolePermissionLoader;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
import io.github.atengk.boot.satoken.util.LoginHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sa-Token 多租户键值隔离（防爆破锁定隔离 + 角色权限缓存隔离）端到端集成测试
 * 联合验证：
 * 1. LoginFailureLockService 多租户同名账号与跨端隔离防爆破（#1079）
 * 2. CachedStpInterface 与 PermissionCacheService tenant-scoped=true 同名角色缓存隔离与定向刷新（#1093）
 *
 * @author Ateng
 * @since 2026-11-19
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(SampleSaTokenTenantIsolationIntegrationTest.TenantScopedLoaderTestConfig.class)
@TestPropertySource(properties = {
        "ateng.boot.satoken.permission-cache.tenant-scoped=true"
})
@DisplayName("Sa-Token 多租户防爆破隔离与角色权限缓存隔离端到端集成测试")
class SampleSaTokenTenantIsolationIntegrationTest {

    @TestConfiguration
    static class TenantScopedLoaderTestConfig {

        static final Map<String, List<String>> TENANT_ROLE_PERMS = new ConcurrentHashMap<>();
        static final AtomicInteger LOAD_COUNT = new AtomicInteger(0);

        static {
            TENANT_ROLE_PERMS.put("tenant-a:tenant_manager", List.of("tenantA:finance:approve"));
            TENANT_ROLE_PERMS.put("tenant-b:tenant_manager", List.of("tenantB:inventory:view"));
        }

        @Bean
        @Primary
        UserRolePermissionLoader tenantScopedUserRolePermissionLoader() {
            DefaultUserRolePermissionLoader delegate = new DefaultUserRolePermissionLoader();
            return new UserRolePermissionLoader() {
                @Override
                public List<String> loadRolesByUserId(String userId) {
                    return delegate.loadRolesByUserId(userId);
                }

                @Override
                public List<String> loadRolesByUserId(String userId, String loginType) {
                    return delegate.loadRolesByUserId(userId, loginType);
                }

                @Override
                public List<String> loadPermissionsByRole(String roleCode) {
                    return delegate.loadPermissionsByRole(roleCode);
                }

                @Override
                public List<String> loadPermissionsByRole(String roleCode, String loginType, String tenantId) {
                    if ("tenant_manager".equals(roleCode) && tenantId != null) {
                        LOAD_COUNT.incrementAndGet();
                        return TENANT_ROLE_PERMS.getOrDefault(tenantId + ":" + roleCode, List.of());
                    }
                    return delegate.loadPermissionsByRole(roleCode, loginType, tenantId);
                }

                @Override
                public List<String> loadRolesByUserId(String userId, String loginType, String tenantId) {
                    if ("operator_88001".equals(userId) && tenantId != null) {
                        // 断言 DB 回源期间当前线程 TenantContextHolder 已被精确锁定为主体归属租户
                        LAST_DB_CONTEXT_TENANT.set(TenantContextHolder.getExplicit());
                        return List.of("tenant_manager");
                    }
                    return delegate.loadRolesByUserId(userId, loginType, tenantId);
                }

                @Override
                public List<String> loadDirectPermissionsByUserId(String userId, String loginType, String tenantId) {
                    if ("operator_88001".equals(userId) && tenantId != null) {
                        return List.of(tenantId + ":user:direct");
                    }
                    return delegate.loadDirectPermissionsByUserId(userId, loginType, tenantId);
                }
            };
        }

        static final java.util.concurrent.atomic.AtomicReference<String> LAST_DB_CONTEXT_TENANT =
                new java.util.concurrent.atomic.AtomicReference<>();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoginFailureLockService lockService;

    @Autowired
    private PermissionCacheService permissionCacheService;

    @Autowired
    private cn.dev33.satoken.stp.StpInterface stpInterface;

    private String tenantAToken;
    private String tenantBToken;
    private String memberToken;

    @AfterEach
    void tearDown() {
        try {
            if (tenantAToken != null) {
                LoginHelper.logoutByTokenValue("admin", tenantAToken);
            }
            if (tenantBToken != null) {
                LoginHelper.logoutByTokenValue("admin", tenantBToken);
            }
            if (memberToken != null) {
                LoginHelper.logoutByTokenValue("member", memberToken);
            }
            String keyA = lockService.buildScopedAccountKey("tenant-a", "admin", "zhangsan");
            String keyB = lockService.buildScopedAccountKey("tenant-b", "admin", "zhangsan");
            String keyMember = lockService.buildScopedAccountKey("tenant-a", "member", "zhangsan");
            LoginHelper.untieDisableByLoginType("admin", keyA);
            LoginHelper.untieDisableByLoginType("admin", keyB);
            LoginHelper.untieDisableByLoginType("member", keyMember);
            lockService.clearLocalMemory();
            permissionCacheService.clearAll();
            TenantScopedLoaderTestConfig.TENANT_ROLE_PERMS.put("tenant-a:tenant_manager", List.of("tenantA:finance:approve"));
            TenantScopedLoaderTestConfig.TENANT_ROLE_PERMS.put("tenant-b:tenant_manager", List.of("tenantB:inventory:view"));
            TenantScopedLoaderTestConfig.LOAD_COUNT.set(0);
            TenantScopedLoaderTestConfig.LAST_DB_CONTEXT_TENANT.set(null);
        } finally {
            tenantAToken = null;
            tenantBToken = null;
            memberToken = null;
            UserContextHolder.clear();
            TenantContextHolder.clear();
        }
    }

    @Test
    @DisplayName("1. 登录防爆破多租户与跨体系隔离：租户 A admin/zhangsan 锁定不影响租户 B 与 member 体系")
    void shouldIsolateLoginFailureLockAcrossTenantsAndLoginTypesViaHttp() throws Exception {
        // 1. 租户 A admin/zhangsan 连续输错 5 次触发锁定
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/sample/satoken/defense/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"account\":\"zhangsan\",\"password\":\"wrong\",\"clientIp\":\"127.0.0.1\","
                                    + "\"tenantId\":\"tenant-a\",\"loginType\":\"admin\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(false));
        }

        // 2. 确认租户 A admin/zhangsan 状态为已锁定
        mockMvc.perform(get("/sample/satoken/defense/account-status")
                        .param("account", "zhangsan")
                        .param("tenantId", "tenant-a")
                        .param("loginType", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.locked").value(true));

        // 3. 租户 B 同名 admin/zhangsan 与租户 A member/zhangsan 均未锁定，且使用正确密码正常签发 Token
        mockMvc.perform(get("/sample/satoken/defense/account-status")
                        .param("account", "zhangsan")
                        .param("tenantId", "tenant-b")
                        .param("loginType", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.locked").value(false))
                .andExpect(jsonPath("$.data.remainRetryCount").value(5));

        MvcResult tenantBLoginRes = mockMvc.perform(post("/sample/satoken/defense/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"zhangsan\",\"password\":\"123456\",\"clientIp\":\"127.0.0.1\","
                                + "\"tenantId\":\"tenant-b\",\"loginType\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        tenantBToken = extractToken(tenantBLoginRes);

        MvcResult memberLoginRes = mockMvc.perform(post("/sample/satoken/defense/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"zhangsan\",\"password\":\"123456\",\"clientIp\":\"127.0.0.1\","
                                + "\"tenantId\":\"tenant-a\",\"loginType\":\"member\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        memberToken = extractToken(memberLoginRes);

        // 4. 管理员仅解锁租户 A admin/zhangsan
        mockMvc.perform(post("/sample/satoken/defense/unlock-account")
                        .param("account", "zhangsan")
                        .param("tenantId", "tenant-a")
                        .param("loginType", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/sample/satoken/defense/account-status")
                        .param("account", "zhangsan")
                        .param("tenantId", "tenant-a")
                        .param("loginType", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.locked").value(false));
    }

    @Test
    @DisplayName("2. 多租户同名角色权限缓存隔离与定向刷新：tenant-a 与 tenant-b 互不串味")
    void shouldIsolateRolePermissionCacheAcrossTenantsAndRefreshSelectivelyViaHttp() throws Exception {
        // 1. 通过 HTTP 端点分别签发 tenant-a 与 tenant-b 下持有同名角色 tenant_manager 的 admin 会话
        tenantAToken = extractToken(mockMvc.perform(post("/sample/satoken/multi-account/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"mgr-a\",\"username\":\"mgrA\",\"tenantId\":\"tenant-a\","
                                + "\"roles\":[\"tenant_manager\"],\"permissions\":[]}"))
                .andExpect(status().isOk())
                .andReturn());

        tenantBToken = extractToken(mockMvc.perform(post("/sample/satoken/multi-account/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"mgr-b\",\"username\":\"mgrB\",\"tenantId\":\"tenant-b\","
                                + "\"roles\":[\"tenant_manager\"],\"permissions\":[]}"))
                .andExpect(status().isOk())
                .andReturn());

        // 2. 各自查询权限，严禁串味
        mockMvc.perform(get("/sample/satoken/permission-cache/permissions")
                        .param("loginType", "admin")
                        .header("satoken-admin", tenantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0]").value("tenantA:finance:approve"));

        mockMvc.perform(get("/sample/satoken/permission-cache/permissions")
                        .param("loginType", "admin")
                        .header("satoken-admin", tenantBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0]").value("tenantB:inventory:view"));

        assertThat(TenantScopedLoaderTestConfig.LOAD_COUNT.get()).isEqualTo(2);

        // 3. 更新底层权限数据源并仅刷新 tenant-a 的 tenant_manager 缓存
        TenantScopedLoaderTestConfig.TENANT_ROLE_PERMS.put("tenant-a:tenant_manager", List.of("tenantA:finance:audit"));
        TenantScopedLoaderTestConfig.TENANT_ROLE_PERMS.put("tenant-b:tenant_manager", List.of("tenantB:should-not-reload"));

        mockMvc.perform(post("/sample/satoken/permission-cache/refresh-role")
                        .param("roleCode", "tenant_manager")
                        .param("loginType", "admin")
                        .param("tenantId", "tenant-a")
                        .header("satoken-admin", tenantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 4. tenant-a 立即读取到新权限，tenant-b 仍命中原有缓存而不触发回源
        mockMvc.perform(get("/sample/satoken/permission-cache/permissions")
                        .param("loginType", "admin")
                        .header("satoken-admin", tenantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("tenantA:finance:audit"));

        mockMvc.perform(get("/sample/satoken/permission-cache/permissions")
                        .param("loginType", "admin")
                        .header("satoken-admin", tenantBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("tenantB:inventory:view"));

        assertThat(TenantScopedLoaderTestConfig.LOAD_COUNT.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("3. 复合 loginId 下划线消歧、切租户回源主租户锁定与用户级三参定向刷新")
    void shouldResolveCompositeUnderscoreIdsAndIsolateUserCacheAcrossTenantsViaHttp() throws Exception {
        tenantAToken = extractToken(mockMvc.perform(post("/sample/satoken/multi-account/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"operator_88001\",\"username\":\"opA\",\"tenantId\":\"tenant-a\","
                                + "\"roles\":[\"tenant_manager\"],\"permissions\":[]}"))
                .andExpect(status().isOk())
                .andReturn());

        tenantBToken = extractToken(mockMvc.perform(post("/sample/satoken/multi-account/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"operator_88001\",\"username\":\"opB\",\"tenantId\":\"tenant-b\","
                                + "\"roles\":[\"tenant_manager\"],\"permissions\":[]}"))
                .andExpect(status().isOk())
                .andReturn());

        // 通过 HTTP 触发租户 A 权限缓存回源，DB 回源锁定主体归属 tenant-a，MockMvc 请求结束后由拦截器统一清理上下文
        mockMvc.perform(get("/sample/satoken/permission-cache/permissions")
                        .param("loginType", "admin")
                        .header("satoken-admin", tenantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.hasItems(
                        "tenantA:finance:approve", "tenant-a:user:direct")));
        assertThat(TenantScopedLoaderTestConfig.LAST_DB_CONTEXT_TENANT.get()).isEqualTo("tenant-a");

        // 同名 operator_88001 在 tenant-b 读取自己专属角色与直属权限
        mockMvc.perform(get("/sample/satoken/permission-cache/permissions")
                        .param("loginType", "admin")
                        .header("satoken-admin", tenantBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.hasItems(
                        "tenantB:inventory:view", "tenant-b:user:direct")));

        // 用户级三参刷新仅作用于 tenant-a:operator_88001
        mockMvc.perform(post("/sample/satoken/permission-cache/refresh-user")
                        .param("userId", "operator_88001")
                        .param("loginType", "admin")
                        .param("tenantId", "tenant-a")
                        .header("satoken-admin", tenantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(permissionCacheService.get(
                PermissionCacheService.buildUserPermsKey("operator_88001", "admin", "tenant-a"))).isNull();
        assertThat(permissionCacheService.get(
                PermissionCacheService.buildUserPermsKey("operator_88001", "admin", "tenant-b"))).isNotNull();

        // 在显式切换外层工作租户为 tenant-other 下再次回源 tenant-a，DB 回调锁定 tenant-a 并在返回后精准恢复外层 tenant-other
        TenantScopedLoaderTestConfig.LAST_DB_CONTEXT_TENANT.set(null);
        TenantContextHolder.runWithTenant("tenant-other", () -> {
            List<String> perms = stpInterface.getPermissionList("tenant-a_operator_88001", "admin");
            assertThat(perms).contains("tenantA:finance:approve", "tenant-a:user:direct");
            assertThat(TenantScopedLoaderTestConfig.LAST_DB_CONTEXT_TENANT.get()).isEqualTo("tenant-a");
            assertThat(TenantContextHolder.getExplicit()).isEqualTo("tenant-other");
        });
    }

    @Test
    @DisplayName("4. 在线会话多租户/IP 条件分页及跨租户 Token 防越权强退与注销")
    void shouldFilterOnlineSessionsByTenantAndCidrAndGuardCrossTenantKickoutViaHttp() throws Exception {
        tenantAToken = extractToken(mockMvc.perform(post("/sample/satoken/multi-account/admin/login")
                        .header("X-Forwarded-For", "10.10.5.10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"online-a1\",\"username\":\"online_alice\",\"tenantId\":\"tenant-a\"}"))
                .andExpect(status().isOk())
                .andReturn());

        tenantBToken = extractToken(mockMvc.perform(post("/sample/satoken/multi-account/admin/login")
                        .header("X-Forwarded-For", "10.10.6.20")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"online-b1\",\"username\":\"online_bob\",\"tenantId\":\"tenant-b\"}"))
                .andExpect(status().isOk())
                .andReturn());

        // 1. 按 tenantId + CIDR 子网组合检索，仅返回 tenant-a 会话
        mockMvc.perform(get("/sample/satoken/online/page")
                        .param("loginType", "admin")
                        .param("tenantId", "tenant-a")
                        .param("loginIp", "10.10.5.0/24")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.data.records[0].userId").value("online-a1"));

        // 2. 查询 token-info 确认归属租户
        mockMvc.perform(get("/sample/satoken/online/token-info")
                        .param("loginType", "admin")
                        .param("tokenValue", tenantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.tenantId").value("tenant-a"));

        // 3. 跨租户尝试强退 tenantAToken（声称属于 tenant-b）必须返回 403 越权拦截
        mockMvc.perform(post("/sample/satoken/online/kickout-token-for-tenant")
                        .param("loginType", "admin")
                        .param("tokenValue", tenantAToken)
                        .param("expectedTenantId", "tenant-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        // 4. 目标租户 tenant-a 合法强退成功并返回 true，二次操作幂等返回 false
        mockMvc.perform(post("/sample/satoken/online/kickout-token-for-tenant")
                        .param("loginType", "admin")
                        .param("tokenValue", tenantAToken)
                        .param("expectedTenantId", "tenant-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(post("/sample/satoken/online/kickout-token-for-tenant")
                        .param("loginType", "admin")
                        .param("tokenValue", tenantAToken)
                        .param("expectedTenantId", "tenant-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(false));

        // 5. 同租户安全注销 tenantBToken 成功
        mockMvc.perform(post("/sample/satoken/online/logout-token-for-tenant")
                        .param("loginType", "admin")
                        .param("tokenValue", tenantBToken)
                        .param("expectedTenantId", "tenant-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    private String extractToken(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").asText();
    }
}

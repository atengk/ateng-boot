package io.github.atengk.boot.sample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.mybatisplus.context.TenantContextHolder;
import io.github.atengk.boot.sample.entity.TenantOrderEntity;
import io.github.atengk.boot.sample.mapper.TenantOrderMapper;
import io.github.atengk.boot.satoken.model.LoginUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sa-Token 安全鉴权与单机内存会话端到端集成测试
 * 覆盖单机会话登录、双轨路由白名单拦截、Controller/Service 全域 AOP 注解鉴权、CurrentUserProvider SPI 审计自动填充与多租户上下文生命周期治理
 *
 * @author Ateng
 * @since 2026-10-17
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Sa-Token 全栈安全鉴权与多租户持久层协同端到端集成测试")
class SampleSaTokenIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantOrderMapper orderMapper;

    @Autowired
    private io.github.atengk.boot.satoken.cache.PermissionCacheService permissionCacheService;

    @Autowired
    private cn.dev33.satoken.stp.StpInterface stpInterface;

    @Test
    @DisplayName("1. 未登录拦截防御：未提供凭据访问受保护接口，统一返回 HTTP 200 + 业务码 401 与细粒度提示")
    void shouldInterceptWhenNotLogin() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("未提供访问凭据 (Token)"));
    }

    @Test
    @DisplayName("2. 完整登录、上下文读取、多租户主键剥离与注销全生命周期端到端验证")
    void shouldCompleteLoginLifecycleAndContextQuery() throws Exception {
        // 1. 构建登录请求数据
        LoginUser loginReq = LoginUser.builder()
                .userId("10086")
                .tenantId("tenant_sz")
                .userAccount("admin")
                .userName("系统管理员")
                .roles(Set.of("super_admin", "auditor"))
                .permissions(Set.of("user:query", "user:export"))
                .build()
                .setExtra("deptCode", "TECH_DEV");

        // 2. 执行登录，获取 Token
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isString())
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String token = loginJson.get("data").asText();
        assertThat(token).isNotBlank();

        // 3. 携带 Token 查询当前登录主体详情
        mockMvc.perform(get("/api/auth/me")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value("10086"))
                .andExpect(jsonPath("$.data.tenantId").value("tenant_sz"))
                .andExpect(jsonPath("$.data.userAccount").value("admin"))
                .andExpect(jsonPath("$.data.userName").value("系统管理员"))
                .andExpect(jsonPath("$.data.roles").isArray())
                .andExpect(jsonPath("$.data.permissions").isArray())
                .andExpect(jsonPath("$.data.extraData.deptCode").value("TECH_DEV"));

        // 4. 验证多租户复合 LoginId 前缀透明剥离
        mockMvc.perform(get("/api/auth/user-id")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("10086"));

        // 5. 校验登录状态
        mockMvc.perform(get("/api/auth/check-login")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 6. 执行会话注销
        mockMvc.perform(post("/api/auth/logout")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 7. 注销后再次携带原 Token 访问，验证已被无效拦截
        mockMvc.perform(get("/api/auth/me")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("访问凭据 (Token) 无效"));
    }

    @Test
    @DisplayName("3. 编程式二级安全认证全生命周期：未开启 403 拦截 -> 开启通过 -> 手动关闭恢复拦截")
    void shouldHandleSafeActionLifecycle() throws Exception {
        // 1. 登录
        LoginUser loginReq = LoginUser.builder()
                .userId("20001")
                .tenantId("tenant_bj")
                .userAccount("finance_mgr")
                .userName("财务经理")
                .build();

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        // 2. 未开启二级认证，访问敏感操作返回 403
        mockMvc.perform(get("/api/auth/safe-action")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问：当前操作需要二级安全认证"));

        // 3. 开启二级安全认证
        mockMvc.perform(post("/api/auth/open-safe")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 4. 开启后再次执行敏感操作，成功通过
        mockMvc.perform(get("/api/auth/safe-action")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("支付操作通过"));

        // 5. 主动关闭二级安全认证
        mockMvc.perform(post("/api/auth/close-safe")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 6. 关闭后再次访问，重新被 403 拦截
        mockMvc.perform(get("/api/auth/safe-action")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问：当前操作需要二级安全认证"));
    }

    @Test
    @DisplayName("4. Controller 层 AOP 注解鉴权：@SaCheckRole 与 @SaCheckPermission 拦截与放行验证")
    void shouldCheckAopRoleAndPermissionOnController() throws Exception {
        LoginUser loginReq = LoginUser.builder()
                .userId("30001")
                .userName("审计员")
                .roles(Set.of("super_admin"))
                .permissions(Set.of("user:query"))
                .build();

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        // 具备 super_admin 角色，访问通过
        mockMvc.perform(get("/api/auth/check-role")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("角色校验通过"));

        // 不具备 financial_boss 角色，拦截返回 403
        mockMvc.perform(get("/api/auth/check-role-forbidden")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问：缺少所需角色 [financial_boss]"));

        // 具备 user:query 权限，访问通过
        mockMvc.perform(get("/api/auth/check-permission")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("权限码校验通过"));

        // 不具备 user:delete 权限，拦截返回 403
        mockMvc.perform(get("/api/auth/check-permission-forbidden")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问：缺少所需权限码 [user:delete]"));
    }

    @Test
    @DisplayName("5. Service 业务层 AOP 穿透鉴权：验证 SaAroundAnnotationPointcutAdvisor 全域覆盖 Service")
    void shouldCheckAopRoleAndPermissionOnServiceLayer() throws Exception {
        LoginUser loginReq = LoginUser.builder()
                .userId("40001")
                .userName("业务专员")
                .roles(Set.of("super_admin"))
                .permissions(Set.of("order:view"))
                .build();

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        // 调用标注 @SaCheckRole("super_admin") 的 Service 方法，成功通过
        mockMvc.perform(get("/api/auth/service-role")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("Service 角色校验通过"));

        // 调用标注 @SaCheckPermission("order:cancel") 的 Service 方法，因缺少权限被拦截返回 403
        mockMvc.perform(get("/api/auth/service-permission")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问：缺少所需权限码 [order:cancel]"));
    }

    @Test
    @DisplayName("6. AOP 注解式二级安全认证：@SaCheckSafe 注解未开启 403 -> 开启通过 -> 关闭恢复拦截")
    void shouldCheckAopSafeAnnotation() throws Exception {
        LoginUser loginReq = LoginUser.builder()
                .userId("50001")
                .userName("风控专员")
                .build();

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        // 1. 未开启 safe 时访问 @SaCheckSafe("payment") 接口，拦截返回 403
        mockMvc.perform(get("/api/auth/check-safe-annotation")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问：当前操作需要二级安全认证"));

        // 2. 开启 safe 认证
        mockMvc.perform(post("/api/auth/open-safe")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 3. 再次访问 @SaCheckSafe("payment") 接口，通过返回 200
        mockMvc.perform(get("/api/auth/check-safe-annotation")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("注解式安全支付通过"));

        // 4. 关闭 safe 认证
        mockMvc.perform(post("/api/auth/close-safe")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 5. 关闭后再次访问，恢复 403 拦截
        mockMvc.perform(get("/api/auth/check-safe-annotation")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问：当前操作需要二级安全认证"));
    }

    @Test
    @DisplayName("7. 白名单公共路由匿名放行验证：无需 Token 凭据直接访问放行")
    void shouldAllowAnonymousAccessOnPublicEndpoint() throws Exception {
        mockMvc.perform(get("/api/auth/public/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("pong"));
    }

    @Test
    @DisplayName("8. CurrentUserProvider 自动桥接验证：登录状态下持久层实体插入自动回填当前操作人 createBy/updateBy")
    void shouldAutoFillAuditFieldsViaCurrentUserProvider() throws Exception {
        // 1. 登录用户 88001
        LoginUser loginReq = LoginUser.builder()
                .userId("88001")
                .userName("审计创建专员")
                .tenantId("tenant_auto_audit")
                .build();

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        // 2. 携带 Token 发起创建订单请求
        Map<String, Object> orderReq = Map.of(
                "orderNo", "ORD-AUDIT-88001",
                "amount", 999.50
        );

        MvcResult createResult = mockMvc.perform(post("/api/auth/order")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        Long orderId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("data").asLong();
        assertThat(orderId).isNotNull();

        // 3. 从数据库直接查询并校验审计字段回填
        TenantOrderEntity orderInDb = orderMapper.selectById(orderId);
        assertThat(orderInDb).isNotNull();
        assertThat(orderInDb.getCreateBy()).isEqualTo("88001");
        assertThat(orderInDb.getUpdateBy()).isEqualTo("88001");
        assertThat(orderInDb.getCreateTime()).isNotNull();
        assertThat(orderInDb.getUpdateTime()).isNotNull();
    }

    @Test
    @DisplayName("9. 多租户上下文生命周期协同验证：请求中自动注入 TenantContextHolder，请求结束后强制安全清空")
    void shouldSyncAndClearTenantContextLifecycle() throws Exception {
        // 1. 登录包含租户 ID 的用户
        LoginUser loginReq = LoginUser.builder()
                .userId("99001")
                .userName("华东租户管理员")
                .tenantId("tenant_east_china")
                .build();

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        // 2. 请求期间：拦截器 preHandle 自动将 tenantId 注入 TenantContextHolder
        mockMvc.perform(get("/api/auth/current-tenant")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("tenant_east_china"));

        // 3. 请求结束后：拦截器 afterCompletion 强制清空租户上下文，当前测试线程池状态应干净无串染
        assertThat(TenantContextHolder.getTenantId()).isNull();

        // 4. 后续不携带 Token 的公共请求，租户上下文依然保持干净为 null
        mockMvc.perform(get("/api/auth/public/ping"))
                .andExpect(status().isOk());
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    @DisplayName("10. 两级权限缓存切片与即时热刷新端到端验证：登录触发两级缓存，调用热刷新接口后缓存即刻清理")
    void shouldHandleTwoLevelPermissionCacheAndHotRefresh() throws Exception {
        // 1. 登录用户
        LoginUser loginReq = LoginUser.builder()
                .userId("66001")
                .userName("权限测试员")
                .roles(Set.of("finance_operator"))
                .permissions(Set.of("invoice:create", "invoice:print"))
                .build();

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        // 2. 校验 StpInterface 类型为 CachedStpInterface
        assertThat(stpInterface).isInstanceOf(io.github.atengk.boot.satoken.cache.CachedStpInterface.class);

        // 3. 访问并触发权限与角色加载，验证端到端 HTTP 返回正确
        mockMvc.perform(get("/api/auth/current-roles")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0]").value("finance_operator"));

        mockMvc.perform(get("/api/auth/current-permissions")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2));

        // 验证底层缓存中存在该用户的角色与直属权限键
        String userRolesKey = io.github.atengk.boot.satoken.cache.PermissionCacheService.USER_ROLES_PREFIX + "66001";
        String userPermsKey = io.github.atengk.boot.satoken.cache.PermissionCacheService.USER_PERMS_PREFIX + "66001";
        assertThat(permissionCacheService.get(userRolesKey)).isNotNull();
        assertThat(permissionCacheService.get(userPermsKey)).isNotNull();

        // 4. 调用热刷新角色缓存端点
        Map<String, String> refreshRoleReq = Map.of("roleCode", "finance_operator");
        mockMvc.perform(post("/api/auth/cache/refresh-role")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRoleReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 5. 调用热刷新用户缓存端点，并验证缓存键已被即时清理为 null
        Map<String, String> refreshUserReq = Map.of("userId", "66001");
        mockMvc.perform(post("/api/auth/cache/refresh-user")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshUserReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(permissionCacheService.get(userRolesKey)).isNull();
        assertThat(permissionCacheService.get(userPermsKey)).isNull();

        // 6. 清空全域缓存
        mockMvc.perform(post("/api/auth/cache/clear-all")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("11. 空角色/空权限哨兵防穿透端到端验证：针对空权限用户，生成 __EMPTY__ 哨兵杜绝击穿")
    void shouldPreventCachePenetrationWithEmptySentinel() throws Exception {
        LoginUser emptyUser = LoginUser.builder()
                .userId("77001")
                .userName("无权限访客")
                .build();

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyUser)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        // HTTP 端点查询空权限与角色
        mockMvc.perform(get("/api/auth/current-roles")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(0));

        // 验证缓存已被空哨兵占位，杜绝缓存穿透
        String userRolesKey = io.github.atengk.boot.satoken.cache.PermissionCacheService.USER_ROLES_PREFIX + "77001";
        assertThat(permissionCacheService.get(userRolesKey)).isEqualTo(io.github.atengk.boot.satoken.cache.CachedStpInterface.EMPTY_SENTINEL);

        // 再次 HTTP 查询依然秒级返回空集合
        mockMvc.perform(get("/api/auth/current-roles")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}

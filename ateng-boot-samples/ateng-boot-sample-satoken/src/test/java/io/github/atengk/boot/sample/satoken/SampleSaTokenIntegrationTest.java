package io.github.atengk.boot.sample.satoken;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.sample.satoken.dto.UserProfileDTO;
import io.github.atengk.boot.sample.satoken.entity.TenantOrderEntity;
import io.github.atengk.boot.sample.satoken.mapper.TenantOrderMapper;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.util.LoginHelper;
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

    @Autowired
    private io.github.atengk.boot.sample.satoken.listener.SampleSecurityEventListener securityEventListener;

    @Autowired(required = false)
    private io.github.atengk.boot.satoken.cluster.broadcaster.SaTokenClusterBroadcaster broadcaster;

    @Autowired(required = false)
    private io.github.atengk.boot.satoken.cluster.listener.SaTokenClusterSyncListener clusterSyncListener;

    @Autowired(required = false)
    private io.github.atengk.boot.satoken.defense.LoginFailureLockService loginDefenseService;

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

    @Test
    @DisplayName("12. Spring 原生事件广播端到端验证：登录与踢人触发 @EventListener 并捕获事件载荷")
    void shouldBroadcastSpringSecurityEventsOnLoginAndKickout() throws Exception {
        securityEventListener.clear();

        LoginUser user = LoginUser.builder()
                .userId("88001")
                .userName("事件测试员")
                .build();

        // 1. 登录
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        // 验证 EventListener 捕获登录事件
        assertThat(securityEventListener.getLoginEvents()).hasSize(1);
        io.github.atengk.boot.satoken.event.SaLoginEvent loginEvent = securityEventListener.getLoginEvents().get(0);
        assertThat(loginEvent.getLoginId()).isEqualTo("88001");
        assertThat(loginEvent.getTokenValue()).isEqualTo(token);

        // 2. 踢人下线
        mockMvc.perform(post("/api/auth/kickout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", "88001"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 验证 EventListener 捕获踢下线事件
        assertThat(securityEventListener.getKickoutEvents()).hasSize(1);
        io.github.atengk.boot.satoken.event.SaKickoutEvent kickoutEvent = securityEventListener.getKickoutEvents().get(0);
        assertThat(kickoutEvent.getLoginId()).isEqualTo("88001");
    }

    @Test
    @DisplayName("13. Knife4j / OpenAPI 自动鉴权弹窗端到端验证：校验 /v3/api-docs 包含 satoken 安全方案")
    void shouldAutoMountKnife4jAndOpenApiSecurityScheme() throws Exception {
        MvcResult apiDocsResult = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(apiDocsResult.getResponse().getContentAsString());

        // 验证 components.securitySchemes 包含 satoken
        JsonNode satokenScheme = root.path("components").path("securitySchemes").path("satoken");
        assertThat(satokenScheme.isMissingNode()).isFalse();
        assertThat(satokenScheme.path("type").asText()).isEqualTo("apiKey");
        assertThat(satokenScheme.path("name").asText()).isEqualTo("satoken");
        assertThat(satokenScheme.path("in").asText()).isEqualTo("header");

        // 验证全局 security 列表包含 satoken
        JsonNode securityArray = root.path("security");
        assertThat(securityArray.isArray()).isTrue();
        boolean hasSatokenSecurity = false;
        for (JsonNode item : securityArray) {
            if (item.has("satoken")) {
                hasSatokenSecurity = true;
                break;
            }
        }
        assertThat(hasSatokenSecurity).isTrue();
    }

    @Test
    @DisplayName("14. Same-Token 零信任安全端到端验证：未携带/非法凭据拦截，合法凭据放行")
    void shouldEnforceSameTokenZeroTrustSecurity() throws Exception {
        // 1. 未携带 Same-Token 访问内部机密接口，返回 HTTP 200 + code: 403
        mockMvc.perform(get("/api/auth/internal/data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("微服务内部鉴权凭据无效或已失效"));

        // 2. 携带无效 Same-Token 访问，被 403 拦截
        mockMvc.perform(get("/api/auth/internal/data")
                        .header(cn.dev33.satoken.same.SaSameUtil.SAME_TOKEN, "invalid-same-token-xxx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("微服务内部鉴权凭据无效或已失效"));

        // 3. 携带系统实时合法 Same-Token 访问，成功放行
        String validSameToken = cn.dev33.satoken.same.SaSameUtil.getToken();
        mockMvc.perform(get("/api/auth/internal/data")
                        .header(cn.dev33.satoken.same.SaSameUtil.SAME_TOKEN, validSameToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("内部机密数据访问成功"));
    }

    @Test
    @DisplayName("15. 多端设备登录与按设备精准踢下线端到端验证")
    void shouldSupportDeviceTypeLoginAndKickoutByDevice() throws Exception {
        LoginUser pcUser = LoginUser.builder()
                .userId("99001")
                .userAccount("multidevice")
                .userName("多端用户")
                .build();

        // 1. PC 端登录并获取 Token
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login?device=PC")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pcUser)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        // 2. 按设备踢下线 PC 端
        mockMvc.perform(post("/api/auth/kickout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", "99001", "device", io.github.atengk.boot.satoken.constant.SaTokenConstant.DEVICE_PC))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 3. 携带已失效的 Token 访问受保护接口，验证已被踢下线拦截 (401)
        mockMvc.perform(get("/api/auth/me")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("当前账号已被踢下线"));
    }

    @Test
    @DisplayName("16. Sa-Token 防火墙非法路径穿越拦截端到端验证：输出标准 Result 400 契约")
    void shouldEnforceFirewallStandardResultContract() throws Exception {
        // 请求包含非法路径穿越的请求路径
        mockMvc.perform(get("/api/auth/test/..%2F..%2Fetc%2Fpasswd"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("17. 多端设备顶替下线与会话隔离端到端验证：账号被顶替后返回 401 提示已在其他设备登录")
    void shouldHandleDeviceReplacedAndSessionIsolation() throws Exception {
        LoginUser user = LoginUser.builder()
                .userId("99002")
                .userName("顶替测试用户")
                .build();

        // 1. 登录并获取 Token
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        // 2. 正常访问受保护接口
        mockMvc.perform(get("/api/auth/me")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 3. 执行顶替下线
        LoginHelper.replaced("99002");

        // 4. 再次携带原 Token 访问，验证已被顶下线拦截 (401)
        mockMvc.perform(get("/api/auth/me")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("当前账号已在其他设备登录，您已被顶下线"));
    }

    @Test
    @DisplayName("22. 水平越权防御端到端：直接参数 @CheckDataOwner 本人放行，越权拦截返回 403")
    void testDataOwnerDefenseDirectParam() throws Exception {
        // 1. 登录普通用户 10086
        LoginUser user = LoginUser.builder()
                .userId("10086")
                .tenantId("tenant_sz")
                .userAccount("user10086")
                .userName("普通用户10086")
                .build();
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        // 2. 本人修改资料：放行
        UserProfileDTO selfDto = new UserProfileDTO("10086", "小明", "ming@example.com");
        mockMvc.perform(post("/api/auth/user/profile")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(selfDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("个人资料更新成功: 10086"));

        // 3. 越权篡改他人资料 (20001)：拦截返回 HTTP 200 + 403
        UserProfileDTO othersDto = new UserProfileDTO("20001", "黑客篡改", "hacker@example.com");
        mockMvc.perform(post("/api/auth/user/profile")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(othersDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问该资源"));
    }

    @Test
    @DisplayName("23. 水平越权防御端到端：间接资源 SPI 反查订单归属，本人订单放行，他人订单拦截 403")
    void testDataOwnerDefenseIndirectResourceSpi() throws Exception {
        // 1. 登录普通用户 10086
        LoginUser user = LoginUser.builder()
                .userId("10086")
                .tenantId("tenant_sz")
                .userAccount("user10086")
                .userName("普通用户10086")
                .build();
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        // 2. 取消归属于自己的订单 88001：放行
        mockMvc.perform(post("/api/auth/order/88001/cancel")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("订单取消成功: 88001"));

        // 3. 越权取消归属于用户 20001 的订单 88002：拦截返回 HTTP 200 + 403 定制提示
        mockMvc.perform(post("/api/auth/order/88002/cancel")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权取消该订单"));
    }

    @Test
    @DisplayName("24. 编程式断言门面端到端：LoginHelper.checkSelf 与 checkBelongsToTenant 正常放行与越权 403")
    void testProgrammaticAssertSelfAndTenant() throws Exception {
        // 1. 登录用户 10086（租户 tenant_sz）
        LoginUser user = LoginUser.builder()
                .userId("10086")
                .tenantId("tenant_sz")
                .userAccount("user10086")
                .userName("普通用户10086")
                .build();
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        // 2. 本人校验放行
        mockMvc.perform(post("/api/auth/assert/self")
                        .header("satoken", token)
                        .param("targetUserId", "10086"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("本人校验通过: 10086"));

        // 3. 非本人校验拦截 403
        mockMvc.perform(post("/api/auth/assert/self")
                        .header("satoken", token)
                        .param("targetUserId", "20001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问该资源"));

        // 4. 同租户校验放行
        mockMvc.perform(post("/api/auth/assert/tenant")
                        .header("satoken", token)
                        .param("targetTenantId", "tenant_sz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("租户归属校验通过: tenant_sz"));

        // 5. 跨租户校验拦截 403
        mockMvc.perform(post("/api/auth/assert/tenant")
                        .header("satoken", token)
                        .param("targetTenantId", "tenant_bj"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("非法跨租户数据访问"));
    }

    @Test
    @DisplayName("25. 方法级 @Anonymous 声明式放行端到端：未携带 Token 访问公开方法及路径参数通配接口直接放行")
    void testAnonymousMethodEndpointWithoutToken() throws Exception {
        // 1. 无参普通 GET 接口
        mockMvc.perform(get("/api/auth/public/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("pong"));

        // 2. 带路径变量占位符接口（自动转译为 AntPath 通配符）
        mockMvc.perform(get("/api/auth/public/article/10086"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("公开文章详情: 10086"));
    }

    @Test
    @DisplayName("26. 类级 @Anonymous 声明式放行端到端：整类所有方法免鉴权放行，支持路径参数")
    void testAnonymousClassEndpointWithoutToken() throws Exception {
        // 1. 访问类级公开接口
        mockMvc.perform(get("/api/anonymous/hello"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("类级匿名访问成功"));

        // 2. 访问类级公开带路径参数接口
        mockMvc.perform(get("/api/anonymous/item/ITEM-999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("类级匿名条目详情: ITEM-999"));
    }

    @Test
    @DisplayName("27. SaTokenWhitelistProvider SPI 动态白名单端到端：外部拓展路径未携带 Token 成功放行")
    void testDynamicSpiWhitelistWithoutToken() throws Exception {
        mockMvc.perform(get("/api/custom-spi/data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("SPI白名单访问成功"));
    }

    @Test
    @DisplayName("28. 身份模拟 (RunAs / Impersonate) 全栈端到端：会话切换、所有权对齐、持久层审计绑定与还原全流程")
    void testImpersonateLifecycleAndAuditIntegration() throws Exception {
        // 1. 管理员登录
        LoginUser adminUser = LoginUser.builder()
                .userId("90001")
                .userName("系统运维管理员")
                .tenantId("tenant_sz")
                .build();
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminUser)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        // 2. 验证初始状态：未开启模拟
        mockMvc.perform(get("/api/auth/impersonate/status")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.isRunAs").value(false))
                .andExpect(jsonPath("$.data.currentUserId").value("90001"))
                .andExpect(jsonPath("$.data.realUserId").value("90001"))
                .andExpect(jsonPath("$.data.originalUserId").doesNotExist());

        // 3. 执行身份模拟切换为用户 66002
        mockMvc.perform(post("/api/auth/impersonate/switch")
                        .header("satoken", token)
                        .param("targetUserId", "66002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 4. 验证模拟状态：currentUserId 被替换为 66002，同时保留 originalUserId 为 90001
        mockMvc.perform(get("/api/auth/impersonate/status")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.isRunAs").value(true))
                .andExpect(jsonPath("$.data.currentUserId").value("66002"))
                .andExpect(jsonPath("$.data.realUserId").value("90001"))
                .andExpect(jsonPath("$.data.originalUserId").value("90001"));

        // 5. 验证业务防越权与数据所有权对齐：当前身份为 66002
        mockMvc.perform(post("/api/auth/assert/self")
                        .header("satoken", token)
                        .param("targetUserId", "66002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("本人校验通过: 66002"));

        mockMvc.perform(post("/api/auth/assert/self")
                        .header("satoken", token)
                        .param("targetUserId", "99999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问该资源"));

        // 6. 验证持久层审计自动填充对齐被模拟人 66002
        Map<String, Object> orderReq1 = Map.of(
                "orderNo", "ORD-RUNAS-66002",
                "amount", 128.00
        );
        MvcResult orderResult1 = mockMvc.perform(post("/api/auth/order")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderReq1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        Long orderId1 = objectMapper.readTree(orderResult1.getResponse().getContentAsString()).get("data").asLong();
        TenantOrderEntity orderInDb1 = orderMapper.selectById(orderId1);
        assertThat(orderInDb1).isNotNull();
        // 自动审计字段填入被模拟人 66002
        assertThat(orderInDb1.getCreateBy()).isEqualTo("66002");
        assertThat(orderInDb1.getUpdateBy()).isEqualTo("66002");

        // 7. 验证瞬态代码块 RunAs
        mockMvc.perform(post("/api/auth/impersonate/run-as")
                        .header("satoken", token)
                        .param("targetUserId", "77003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.isRunAs").value(true))
                .andExpect(jsonPath("$.data.currentUserId").value("77003"))
                .andExpect(jsonPath("$.data.realUserId").value("90001"))
                .andExpect(jsonPath("$.data.originalUserId").value("90001"));

        // 瞬态执行完毕后，当前会话仍保持在 66002
        mockMvc.perform(get("/api/auth/impersonate/status")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentUserId").value("66002"));

        // 8. 结束身份模拟，恢复真实登录人 90001
        mockMvc.perform(post("/api/auth/impersonate/end")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 验证状态已恢复
        mockMvc.perform(get("/api/auth/impersonate/status")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.isRunAs").value(false))
                .andExpect(jsonPath("$.data.currentUserId").value("90001"))
                .andExpect(jsonPath("$.data.originalUserId").doesNotExist());

        // 9. 恢复后创建订单，持久层审计恢复为 90001
        Map<String, Object> orderReq2 = Map.of(
                "orderNo", "ORD-RESTORE-90001",
                "amount", 256.00
        );
        MvcResult orderResult2 = mockMvc.perform(post("/api/auth/order")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderReq2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        Long orderId2 = objectMapper.readTree(orderResult2.getResponse().getContentAsString()).get("data").asLong();
        TenantOrderEntity orderInDb2 = orderMapper.selectById(orderId2);
        assertThat(orderInDb2).isNotNull();
        assertThat(orderInDb2.getCreateBy()).isEqualTo("90001");
        assertThat(orderInDb2.getUpdateBy()).isEqualTo("90001");
    }

    @Test
    @DisplayName("20. 分布式集群同步：验证广播器实例生成、单机静默降级与触发接口端到端")
    void testClusterSyncEndpointsAndInstanceId() throws Exception {
        // 1. 验证广播器与监听器 Bean 正确装配
        assertThat(broadcaster).isNotNull();
        assertThat(broadcaster.getInstanceId()).isNotBlank();
        assertThat(clusterSyncListener).isNotNull();

        // 2. 调用 /api/auth/cluster/instance-id 端点验证返回的 instanceId
        mockMvc.perform(get("/api/auth/cluster/instance-id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(broadcaster.getInstanceId()));

        // 3. 调用 /api/auth/cluster/refresh-user 端点
        mockMvc.perform(post("/api/auth/cluster/refresh-user")
                        .param("userId", "10086"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("用户权限缓存已刷新并广播: userId=10086"));

        // 4. 调用 /api/auth/cluster/refresh-role 端点
        mockMvc.perform(post("/api/auth/cluster/refresh-role")
                        .param("roleCode", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("角色权限缓存已刷新并广播: roleCode=admin"));

        // 5. 调用 /api/auth/cluster/clear-all 端点
        mockMvc.perform(post("/api/auth/cluster/clear-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("全量权限缓存已清空并广播"));
    }

    @Test
    @DisplayName("21. 分布式集群同步：异地广播消息精准失效本地缓存与自消费防重入")
    void testClusterRemoteMessageConsumptionAndSelfGuard() {
        assertThat(clusterSyncListener).isNotNull();
        assertThat(broadcaster).isNotNull();

        String testUserId = "cluster_user_888";
        String userRolesKey = io.github.atengk.boot.satoken.cache.PermissionCacheService.USER_ROLES_PREFIX + testUserId;
        String userPermsKey = io.github.atengk.boot.satoken.cache.PermissionCacheService.USER_PERMS_PREFIX + testUserId;

        // 1. 预置本地缓存
        permissionCacheService.set(userRolesKey, "[\"role_sample\"]", 300);
        permissionCacheService.set(userPermsKey, "[\"user:sample\"]", 300);
        assertThat(permissionCacheService.get(userRolesKey)).isEqualTo("[\"role_sample\"]");
        assertThat(permissionCacheService.get(userPermsKey)).isEqualTo("[\"user:sample\"]");

        // 2. 模拟本机消息（相同 instanceId），验证自消费防卫丢弃，缓存不被清理
        io.github.atengk.boot.satoken.cluster.model.SaTokenClusterMessage selfMsg =
                io.github.atengk.boot.satoken.cluster.model.SaTokenClusterMessage.of(
                        broadcaster.getInstanceId(),
                        io.github.atengk.boot.satoken.cluster.enums.SaTokenClusterEventType.USER_PERMISSION_REFRESH,
                        testUserId);
        clusterSyncListener.onClusterMessage(io.github.atengk.boot.core.util.JsonUtil.toJsonString(selfMsg));

        // 缓存依然存在
        assertThat(permissionCacheService.get(userRolesKey)).isEqualTo("[\"role_sample\"]");
        assertThat(permissionCacheService.get(userPermsKey)).isEqualTo("[\"user:sample\"]");

        // 3. 模拟异地节点广播消息（不同 instanceId），精准触发本地用户缓存失效
        io.github.atengk.boot.satoken.cluster.model.SaTokenClusterMessage remoteMsg =
                io.github.atengk.boot.satoken.cluster.model.SaTokenClusterMessage.of(
                        "remote-node-xyz",
                        io.github.atengk.boot.satoken.cluster.enums.SaTokenClusterEventType.USER_PERMISSION_REFRESH,
                        testUserId);
        clusterSyncListener.onClusterMessage(io.github.atengk.boot.core.util.JsonUtil.toJsonString(remoteMsg));

        // 缓存已被精准清理
        assertThat(permissionCacheService.get(userRolesKey)).isNull();
        assertThat(permissionCacheService.get(userPermsKey)).isNull();
    }

    @Test
    @DisplayName("22. 登录防暴力破解：连续输错密码触发账号锁定与解封全生命周期")
    void testLoginDefenseLockAndUnlockLifecycle() throws Exception {
        assertThat(loginDefenseService).isNotNull();

        String testUsername = "brute_force_user";
        try {
            // 0. 初始状态自检
            mockMvc.perform(get("/api/auth/defense/status")
                            .param("username", testUsername))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.isLocked").value(false))
                    .andExpect(jsonPath("$.data.remainRetryCount").value(5))
                    .andExpect(jsonPath("$.data.failureCount").value(0));

            // 1. 模拟连续 4 次输错密码（剩余次数从 4 递减至 1）
            for (int i = 1; i <= 4; i++) {
                int expectedRemain = 5 - i;
                io.github.atengk.boot.sample.satoken.dto.LoginDefenseRequest failReq =
                        new io.github.atengk.boot.sample.satoken.dto.LoginDefenseRequest(testUsername, "wrong-password-" + i);
                mockMvc.perform(post("/api/auth/login-with-defense")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(failReq)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(401))
                        .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("剩余重试机会: " + expectedRemain + " 次")));

                assertThat(loginDefenseService.getFailureCount(testUsername)).isEqualTo(i);
                assertThat(loginDefenseService.getRemainRetryCount(testUsername)).isEqualTo(expectedRemain);
                assertThat(loginDefenseService.isLocked(testUsername)).isFalse();
            }

            // 2. 第 5 次输错密码：达到阈值，触发账号锁定封禁（返回 403 锁定提示）
            io.github.atengk.boot.sample.satoken.dto.LoginDefenseRequest lockReq =
                    new io.github.atengk.boot.sample.satoken.dto.LoginDefenseRequest(testUsername, "wrong-password-5");
            mockMvc.perform(post("/api/auth/login-with-defense")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(lockReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403))
                    .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("登录失败次数过多，账号已被锁定")));

            // 验证防爆破状态
            assertThat(loginDefenseService.isLocked(testUsername)).isTrue();
            assertThat(loginDefenseService.getRemainRetryCount(testUsername)).isEqualTo(0);
            assertThat(loginDefenseService.getDisableTime(testUsername)).isGreaterThan(0);

            // 3. 第 6 次尝试：即便输入正确密码，也在前置被锁定拦截
            io.github.atengk.boot.sample.satoken.dto.LoginDefenseRequest correctReq =
                    new io.github.atengk.boot.sample.satoken.dto.LoginDefenseRequest(testUsername, "correct-pass-123");
            mockMvc.perform(post("/api/auth/login-with-defense")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(correctReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));

            // 4. 手动解除封禁并清空失败计数
            LoginHelper.untieDisable(testUsername);
            loginDefenseService.clearFailure(testUsername);

            assertThat(loginDefenseService.isLocked(testUsername)).isFalse();
            assertThat(loginDefenseService.getRemainRetryCount(testUsername)).isEqualTo(5);
            assertThat(loginDefenseService.getFailureCount(testUsername)).isEqualTo(0);

            // 5. 解封后使用正确密码登录成功
            mockMvc.perform(post("/api/auth/login-with-defense")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(correctReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isString());
        } finally {
            // 上下文零污染清理
            LoginHelper.untieDisable(testUsername);
            loginDefenseService.clearFailure(testUsername);
        }
    }
}


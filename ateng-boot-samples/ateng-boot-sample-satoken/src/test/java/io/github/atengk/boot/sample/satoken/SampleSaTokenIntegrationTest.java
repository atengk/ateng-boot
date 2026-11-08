package io.github.atengk.boot.sample.satoken;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.sample.satoken.dto.DefenseLoginRequestDTO;
import io.github.atengk.boot.sample.satoken.dto.LoginRequestDTO;
import io.github.atengk.boot.sample.satoken.dto.OrderCreateDTO;
import io.github.atengk.boot.sample.satoken.dto.SettlementRequestDTO;
import io.github.atengk.boot.sample.satoken.dto.UserProfileDTO;
import io.github.atengk.boot.sample.satoken.entity.TenantOrderEntity;
import io.github.atengk.boot.sample.satoken.mapper.TenantOrderMapper;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.util.LoginHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    @Autowired(required = false)
    private org.springframework.boot.web.client.RestTemplateBuilder restTemplateBuilder;

    @Autowired(required = false)
    private org.springframework.web.client.RestClient.Builder restClientBuilder;

    @Autowired(required = false)
    private io.github.atengk.boot.sample.satoken.controller.InternalClusterController internalClusterController;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired
    private Environment environment;

    @AfterEach
    void cleanUpDatabaseAndRedis() {
        try {
            orderMapper.delete(new LambdaQueryWrapper<TenantOrderEntity>()
                    .isNotNull(TenantOrderEntity::getId));
        } catch (Exception ignored) {
        }
        if (redisTemplate != null && isLocalProfileActive()) {
            cleanTestRedisKeys();
        }
    }

    private boolean isLocalProfileActive() {
        return Arrays.asList(environment.getActiveProfiles()).contains("local")
                || Boolean.TRUE.equals(environment.getProperty("ateng.boot.redis.enabled", Boolean.class));
    }

    private void cleanTestRedisKeys() {
        try {
            String[] testUserPatterns = {"*1001*", "*1002*", "*1003*", "*9999*", "*2001*", "*2002*", "*3001*", "*4001*", "*5001*", "*8888*", "*88001*", "*66001*", "*66002*", "*90001*", "*test-local-user*", "*admin-root*", "*merchant-shop1*"};
            for (String pattern : testUserPatterns) {
                Set<String> keys = redisTemplate.keys("satoken:" + pattern);
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            }
            Set<String> defenseKeys = redisTemplate.keys("satoken:defense:*");
            if (defenseKeys != null && !defenseKeys.isEmpty()) {
                redisTemplate.delete(defenseKeys);
            }
            Set<String> atengSatokenKeys = redisTemplate.keys("ateng:satoken:*");
            if (atengSatokenKeys != null && !atengSatokenKeys.isEmpty()) {
                redisTemplate.delete(atengSatokenKeys);
            }
        } catch (Exception ignored) {
        }
    }

    @Test
    @DisplayName("1. 未登录拦截防御：未提供凭据访问受保护接口，统一返回 HTTP 200 + 业务码 401 与细粒度提示")
    void shouldInterceptWhenNotLogin() throws Exception {
        mockMvc.perform(get("/sample/satoken/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("未提供访问凭据 (Token)"));
    }

    @Test
    @DisplayName("2. 完整登录、上下文读取、多租户主键剥离与注销全生命周期端到端验证")
    void shouldCompleteLoginLifecycleAndContextQuery() throws Exception {
        LoginRequestDTO loginReq = LoginRequestDTO.builder()
                .userId("10086")
                .tenantId("tenant_sz")
                .username("admin")
                .roles(Set.of("super_admin", "auditor"))
                .permissions(Set.of("user:query", "user:export"))
                .build();

        MvcResult loginResult = mockMvc.perform(post("/sample/satoken/auth/login")
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

        mockMvc.perform(get("/sample/satoken/auth/me")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value("10086"))
                .andExpect(jsonPath("$.data.tenantId").value("tenant_sz"))
                .andExpect(jsonPath("$.data.userAccount").value("admin"))
                .andExpect(jsonPath("$.data.userName").value("admin"))
                .andExpect(jsonPath("$.data.roles").isArray())
                .andExpect(jsonPath("$.data.permissions").isArray());

        mockMvc.perform(get("/sample/satoken/auth/user-id")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("10086"));

        mockMvc.perform(get("/sample/satoken/auth/check-login")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("已登录"));

        mockMvc.perform(post("/sample/satoken/auth/logout")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/sample/satoken/auth/me")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("访问凭据 (Token) 无效"));
    }

    @Test
    @DisplayName("3. 编程式二级安全认证全生命周期：未开启 403 拦截 -> 开启通过 -> 手动关闭恢复拦截")
    void shouldHandleSafeActionLifecycle() throws Exception {
        LoginRequestDTO loginReq = LoginRequestDTO.builder()
                .userId("20001")
                .tenantId("tenant_bj")
                .username("finance_mgr")
                .build();

        MvcResult loginResult = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        SettlementRequestDTO settleDto = new SettlementRequestDTO("SETTLE-20001", new BigDecimal("50000.00"), "ACC-TEST-001");

        mockMvc.perform(post("/sample/satoken/access/safe-settle")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(settleDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问：当前操作需要二级安全认证"));

        mockMvc.perform(post("/sample/satoken/access/open-safe")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/sample/satoken/access/safe-settle")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(settleDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsString("50000.00")));

        mockMvc.perform(post("/sample/satoken/access/close-safe")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/sample/satoken/access/safe-settle")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(settleDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问：当前操作需要二级安全认证"));
    }

    @Test
    @DisplayName("4. Controller 层 AOP 注解鉴权：@SaCheckRole 与 @SaCheckPermission 拦截与放行验证")
    void shouldCheckAopRoleAndPermissionOnController() throws Exception {
        LoginRequestDTO loginReq = LoginRequestDTO.builder()
                .userId("30001")
                .username("审计员")
                .roles(Set.of("audit_manager"))
                .permissions(Set.of("order:query"))
                .build();

        MvcResult loginResult = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        mockMvc.perform(get("/sample/satoken/access/role-audit")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("审计角色鉴权通过"));

        mockMvc.perform(get("/sample/satoken/access/role-finance-only")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问：缺少所需角色 [finance_director]"));

        mockMvc.perform(get("/sample/satoken/access/perm-order-query")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("订单查询权限鉴权通过"));

        mockMvc.perform(get("/sample/satoken/access/perm-order-delete")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问：缺少所需权限码 [order:delete]"));
    }

    @Test
    @DisplayName("5. Service 业务层 AOP 穿透鉴权：验证 SaAroundAnnotationPointcutAdvisor 全域覆盖 Service")
    void shouldCheckAopRoleAndPermissionOnServiceLayer() throws Exception {
        LoginRequestDTO loginReq = LoginRequestDTO.builder()
                .userId("40001")
                .username("业务专员")
                .roles(Set.of("audit_manager"))
                .permissions(Set.of("order:cancel"))
                .build();

        MvcResult loginResult = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        mockMvc.perform(get("/sample/satoken/access/service-role")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问：缺少所需角色 [finance_director]"));

        mockMvc.perform(get("/sample/satoken/access/service-permission")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("Service 权限校验通过"));
    }

    @Test
    @DisplayName("6. AOP 注解式二级安全认证：@SaCheckSafe 注解未开启 403 -> 开启通过 -> 关闭恢复拦截")
    void shouldCheckAopSafeAnnotation() throws Exception {
        LoginRequestDTO loginReq = LoginRequestDTO.builder()
                .userId("50001")
                .username("风控专员")
                .build();

        MvcResult loginResult = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        SettlementRequestDTO settleDto = new SettlementRequestDTO("SETTLE-50001", new BigDecimal("60000.00"), "ACC-SAFE-001");

        mockMvc.perform(post("/sample/satoken/access/safe-settle-anno")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(settleDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问：当前操作需要二级安全认证"));

        mockMvc.perform(post("/sample/satoken/access/open-safe")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/sample/satoken/access/safe-settle-anno")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(settleDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsString("60000.00")));

        mockMvc.perform(post("/sample/satoken/access/close-safe")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/sample/satoken/access/safe-settle-anno")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(settleDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问：当前操作需要二级安全认证"));
    }

    @Test
    @DisplayName("7. 白名单公共路由匿名放行验证：无需 Token 凭据直接访问放行")
    void shouldAllowAnonymousAccessOnPublicEndpoint() throws Exception {
        mockMvc.perform(get("/sample/satoken/auth/public/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("pong"));
    }

    @Test
    @DisplayName("8. CurrentUserProvider 自动桥接验证：登录状态下持久层实体插入自动回填当前操作人 createBy/updateBy")
    void shouldAutoFillAuditFieldsViaCurrentUserProvider() throws Exception {
        LoginRequestDTO loginReq = LoginRequestDTO.builder()
                .userId("88001")
                .username("审计创建专员")
                .tenantId("tenant_auto_audit")
                .build();

        MvcResult loginResult = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        OrderCreateDTO orderReq = new OrderCreateDTO();
        orderReq.setOrderNo("ORD-AUDIT-88001");
        orderReq.setAmount(new BigDecimal("999.50"));

        MvcResult createResult = mockMvc.perform(post("/sample/satoken/auth/orders")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        Long orderId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("data").asLong();
        assertThat(orderId).isNotNull();

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
        LoginRequestDTO loginReq = LoginRequestDTO.builder()
                .userId("99001")
                .username("华东租户管理员")
                .tenantId("tenant_east_china")
                .build();

        MvcResult loginResult = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        mockMvc.perform(get("/sample/satoken/auth/current-tenant")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("tenant_east_china"));

        assertThat(TenantContextHolder.getTenantId()).isNull();

        mockMvc.perform(get("/sample/satoken/auth/public/ping"))
                .andExpect(status().isOk());
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    @DisplayName("10. 两级权限缓存切片与即时热刷新端到端验证：登录触发两级缓存，调用热刷新接口后缓存即刻清理")
    void shouldHandleTwoLevelPermissionCacheAndHotRefresh() throws Exception {
        LoginRequestDTO loginReq = LoginRequestDTO.builder()
                .userId("66001")
                .username("权限测试员")
                .roles(Set.of("finance_operator"))
                .permissions(Set.of("invoice:create", "invoice:print"))
                .build();

        MvcResult loginResult = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        assertThat(stpInterface).isInstanceOf(io.github.atengk.boot.satoken.cache.CachedStpInterface.class);

        mockMvc.perform(get("/sample/satoken/permission-cache/roles")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0]").value("finance_operator"));

        mockMvc.perform(get("/sample/satoken/permission-cache/permissions")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2));

        String userRolesKey = io.github.atengk.boot.satoken.cache.PermissionCacheService.USER_ROLES_PREFIX + "66001";
        String userPermsKey = io.github.atengk.boot.satoken.cache.PermissionCacheService.USER_PERMS_PREFIX + "66001";
        assertThat(permissionCacheService.get(userRolesKey)).isNotNull();
        assertThat(permissionCacheService.get(userPermsKey)).isNotNull();

        mockMvc.perform(post("/sample/satoken/permission-cache/refresh-role")
                        .header("satoken", token)
                        .param("roleCode", "finance_operator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/sample/satoken/permission-cache/refresh-user")
                        .header("satoken", token)
                        .param("userId", "66001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(permissionCacheService.get(userRolesKey)).isNull();
        assertThat(permissionCacheService.get(userPermsKey)).isNull();

        mockMvc.perform(post("/sample/satoken/permission-cache/clear-all")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("11. 空角色/空权限哨兵防穿透端到端验证：针对空权限用户，生成 __EMPTY__ 哨兵杜绝击穿")
    void shouldPreventCachePenetrationWithEmptySentinel() throws Exception {
        LoginRequestDTO emptyUser = LoginRequestDTO.builder()
                .userId("77001")
                .username("无权限访客")
                .build();

        MvcResult loginResult = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyUser)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        mockMvc.perform(get("/sample/satoken/permission-cache/roles")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(0));

        String userRolesKey = io.github.atengk.boot.satoken.cache.PermissionCacheService.USER_ROLES_PREFIX + "77001";
        assertThat(permissionCacheService.get(userRolesKey)).isEqualTo(io.github.atengk.boot.satoken.cache.CachedStpInterface.EMPTY_SENTINEL);

        mockMvc.perform(get("/sample/satoken/permission-cache/roles")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("12. Spring 原生事件广播端到端验证：登录与踢人触发 @EventListener 并捕获事件载荷")
    void shouldBroadcastSpringSecurityEventsOnLoginAndKickout() throws Exception {
        securityEventListener.clear();

        LoginRequestDTO user = LoginRequestDTO.builder()
                .userId("88001")
                .username("事件测试员")
                .build();

        MvcResult loginResult = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        assertThat(securityEventListener.getLoginEvents()).hasSize(1);
        io.github.atengk.boot.satoken.event.SaLoginEvent loginEvent = securityEventListener.getLoginEvents().get(0);
        assertThat(loginEvent.getLoginId()).isEqualTo("88001");
        assertThat(loginEvent.getTokenValue()).isEqualTo(token);

        mockMvc.perform(post("/sample/satoken/online/kickout-user")
                        .param("userId", "88001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

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

        JsonNode satokenScheme = root.path("components").path("securitySchemes").path("satoken");
        assertThat(satokenScheme.isMissingNode()).isFalse();
        assertThat(satokenScheme.path("type").asText()).isEqualTo("apiKey");
        assertThat(satokenScheme.path("name").asText()).isEqualTo("satoken");
        assertThat(satokenScheme.path("in").asText()).isEqualTo("header");

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
        mockMvc.perform(get("/sample/satoken/internal/clearing/voucher"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("微服务内部鉴权凭据无效或已失效"));

        mockMvc.perform(get("/sample/satoken/internal/clearing/voucher")
                        .header("SA-SAME-TOKEN", "invalid-same-token-xxx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("微服务内部鉴权凭据无效或已失效"));

        mockMvc.perform(get("/sample/satoken/internal/clearing/voucher")
                        .header("SA-SAME-TOKEN", "SATOKEN_INTERNAL_SAME_TOKEN_SECRET_987654"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("清算凭证数据 [CLR-20261108-9988]"));
    }

    @Test
    @DisplayName("15. 多端设备登录与按设备精准踢下线端到端验证")
    void shouldSupportDeviceTypeLoginAndKickoutByDevice() throws Exception {
        LoginRequestDTO pcUser = LoginRequestDTO.builder()
                .userId("99001")
                .username("多端用户")
                .device("PC")
                .build();

        MvcResult loginResult = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pcUser)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        mockMvc.perform(post("/sample/satoken/online/kickout-device")
                        .param("userId", "99001")
                        .param("device", "PC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/sample/satoken/auth/me")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("当前账号已被踢下线"));
    }

    @Test
    @DisplayName("16. Sa-Token 防火墙非法路径穿越拦截端到端验证：输出标准 Result 400 契约")
    void shouldEnforceFirewallStandardResultContract() throws Exception {
        mockMvc.perform(get("/sample/satoken/auth/test/..%2F..%2Fetc%2Fpasswd"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("17. 多端设备顶替下线与会话隔离端到端验证：账号被顶替后返回 401 提示已在其他设备登录")
    void shouldHandleDeviceReplacedAndSessionIsolation() throws Exception {
        LoginRequestDTO user = LoginRequestDTO.builder()
                .userId("99002")
                .username("顶替测试用户")
                .build();

        MvcResult loginResult = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        mockMvc.perform(get("/sample/satoken/auth/me")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        LoginHelper.replaced("99002");

        mockMvc.perform(get("/sample/satoken/auth/me")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("当前账号已在其他设备登录，您已被顶下线"));
    }

    @Test
    @DisplayName("22. 水平越权防御端到端：直接参数 @CheckDataOwner 本人放行，越权拦截返回 403")
    void testDataOwnerDefenseDirectParam() throws Exception {
        LoginRequestDTO user = LoginRequestDTO.builder()
                .userId("10086")
                .tenantId("tenant_sz")
                .username("user10086")
                .build();
        MvcResult loginResult = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        UserProfileDTO selfDto = new UserProfileDTO("10086", "小明", "ming@example.com");
        mockMvc.perform(post("/sample/satoken/access/profile/update")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(selfDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("个人资料更新成功: 小明"));

        UserProfileDTO othersDto = new UserProfileDTO("20001", "黑客篡改", "hacker@example.com");
        mockMvc.perform(post("/sample/satoken/access/profile/update")
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
        LoginRequestDTO user = LoginRequestDTO.builder()
                .userId("10086")
                .tenantId("tenant_sz")
                .username("user10086")
                .build();
        MvcResult loginResult = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        mockMvc.perform(post("/sample/satoken/access/orders/88001/cancel")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("订单已成功取消: 88001"));

        mockMvc.perform(post("/sample/satoken/access/orders/88002/cancel")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问该资源"));
    }

    @Test
    @DisplayName("24. 编程式断言门面端到端：LoginHelper.checkSelf 与 checkBelongsToTenant 正常放行与越权 403")
    void testProgrammaticAssertSelfAndTenant() throws Exception {
        LoginRequestDTO user = LoginRequestDTO.builder()
                .userId("10086")
                .tenantId("tenant_sz")
                .username("user10086")
                .build();
        MvcResult loginResult = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        mockMvc.perform(post("/sample/satoken/access/assert-self")
                        .header("satoken", token)
                        .param("userId", "10086"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("本人所有权断言校验通过: 10086"));

        mockMvc.perform(post("/sample/satoken/access/assert-self")
                        .header("satoken", token)
                        .param("userId", "20001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问该资源"));

        mockMvc.perform(post("/sample/satoken/access/assert-tenant")
                        .header("satoken", token)
                        .param("tenantId", "tenant_sz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("当前租户归属断言校验通过: tenant_sz"));

        mockMvc.perform(post("/sample/satoken/access/assert-tenant")
                        .header("satoken", token)
                        .param("tenantId", "tenant_bj"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("非法跨租户数据访问"));
    }

    @Test
    @DisplayName("25. 方法级 @Anonymous 声明式放行端到端：未携带 Token 访问公开方法及路径参数通配接口直接放行")
    void testAnonymousMethodEndpointWithoutToken() throws Exception {
        mockMvc.perform(get("/sample/satoken/auth/public/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("pong"));

        mockMvc.perform(get("/sample/satoken/auth/public/article/10086"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("公开文章详情: 10086"));
    }

    @Test
    @DisplayName("26. 类级 @Anonymous 声明式放行端到端：整类所有方法免鉴权放行，支持路径参数")
    void testAnonymousClassEndpointWithoutToken() throws Exception {
        mockMvc.perform(get("/sample/satoken/portal/announcement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("【系统公告】双十一全场云原生基础设施 Starter 限时特惠开启！"));

        mockMvc.perform(get("/sample/satoken/portal/products/ITEM-999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.productId").value("ITEM-999"));
    }

    @Test
    @DisplayName("27. SaTokenWhitelistProvider SPI 动态白名单端到端：外部拓展路径未携带 Token 成功放行")
    void testDynamicSpiWhitelistWithoutToken() throws Exception {
        mockMvc.perform(get("/sample/satoken/portal/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0]").value("云计算"));
    }

    @Test
    @DisplayName("28. 身份模拟 (RunAs / Impersonate) 全栈端到端：会话切换、所有权对齐、持久层审计绑定与还原全流程")
    void testImpersonateLifecycleAndAuditIntegration() throws Exception {
        LoginRequestDTO adminUser = LoginRequestDTO.builder()
                .userId("90001")
                .username("系统运维管理员")
                .tenantId("tenant_sz")
                .build();
        MvcResult loginResult = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminUser)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        mockMvc.perform(get("/sample/satoken/impersonate/status")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.isRunAs").value(false))
                .andExpect(jsonPath("$.data.currentUserId").value("90001"))
                .andExpect(jsonPath("$.data.realUserId").value("90001"));

        mockMvc.perform(post("/sample/satoken/impersonate/switch")
                        .header("satoken", token)
                        .param("targetUserId", "66002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/sample/satoken/impersonate/status")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.isRunAs").value(true))
                .andExpect(jsonPath("$.data.currentUserId").value("66002"))
                .andExpect(jsonPath("$.data.realUserId").value("90001"))
                .andExpect(jsonPath("$.data.originalUserId").value("90001"));

        mockMvc.perform(post("/sample/satoken/impersonate/end-switch")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/sample/satoken/impersonate/status")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.isRunAs").value(false))
                .andExpect(jsonPath("$.data.currentUserId").value("90001"));
    }

    @Test
    @DisplayName("20. 分布式集群同步：验证广播器实例生成、单机静默降级与触发接口端到端")
    void testClusterSyncEndpointsAndInstanceId() throws Exception {
        assertThat(broadcaster).isNotNull();
        assertThat(broadcaster.getInstanceId()).isNotBlank();
        assertThat(clusterSyncListener).isNotNull();

        mockMvc.perform(get("/sample/satoken/cluster/instance-id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(broadcaster.getInstanceId()));

        mockMvc.perform(post("/sample/satoken/cluster/refresh-user")
                        .param("userId", "10086"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/sample/satoken/cluster/refresh-role")
                        .param("roleCode", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/sample/satoken/cluster/clear-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("21. 分布式集群同步：异地广播消息精准失效本地缓存与自消费防重入")
    void testClusterRemoteMessageConsumptionAndSelfGuard() {
        assertThat(clusterSyncListener).isNotNull();
        assertThat(broadcaster).isNotNull();

        String testUserId = "cluster_user_888";
        String userRolesKey = io.github.atengk.boot.satoken.cache.PermissionCacheService.USER_ROLES_PREFIX + testUserId;
        String userPermsKey = io.github.atengk.boot.satoken.cache.PermissionCacheService.USER_PERMS_PREFIX + testUserId;

        permissionCacheService.set(userRolesKey, "[\"role_sample\"]", 300);
        permissionCacheService.set(userPermsKey, "[\"user:sample\"]", 300);
        assertThat(permissionCacheService.get(userRolesKey)).isEqualTo("[\"role_sample\"]");
        assertThat(permissionCacheService.get(userPermsKey)).isEqualTo("[\"user:sample\"]");

        io.github.atengk.boot.satoken.cluster.model.SaTokenClusterMessage selfMsg =
                io.github.atengk.boot.satoken.cluster.model.SaTokenClusterMessage.of(
                        broadcaster.getInstanceId(),
                        io.github.atengk.boot.satoken.cluster.enums.SaTokenClusterEventType.USER_PERMISSION_REFRESH,
                        testUserId);
        clusterSyncListener.onClusterMessage(io.github.atengk.boot.core.util.JsonUtil.toJsonString(selfMsg));

        assertThat(permissionCacheService.get(userRolesKey)).isEqualTo("[\"role_sample\"]");
        assertThat(permissionCacheService.get(userPermsKey)).isEqualTo("[\"user:sample\"]");

        if (redisTemplate != null) {
            redisTemplate.delete(userRolesKey);
            redisTemplate.delete(userPermsKey);
        }
        io.github.atengk.boot.satoken.cluster.model.SaTokenClusterMessage remoteMsg =
                io.github.atengk.boot.satoken.cluster.model.SaTokenClusterMessage.of(
                        "remote-node-xyz",
                        io.github.atengk.boot.satoken.cluster.enums.SaTokenClusterEventType.USER_PERMISSION_REFRESH,
                        testUserId);
        clusterSyncListener.onClusterMessage(io.github.atengk.boot.core.util.JsonUtil.toJsonString(remoteMsg));

        assertThat(permissionCacheService.get(userRolesKey)).isNull();
        assertThat(permissionCacheService.get(userPermsKey)).isNull();
    }

    @Test
    @DisplayName("22. 登录防暴力破解：连续输错密码触发账号锁定与解封全生命周期")
    void testLoginDefenseLockAndUnlockLifecycle() throws Exception {
        assertThat(loginDefenseService).isNotNull();

        String testUsername = "brute_force_user";
        try {
            mockMvc.perform(get("/sample/satoken/defense/account-status")
                            .param("account", testUsername))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.locked").value(false))
                    .andExpect(jsonPath("$.data.remainRetryCount").value(5))
                    .andExpect(jsonPath("$.data.failureCount").value(0));

            for (int i = 1; i <= 4; i++) {
                int expectedRemain = 5 - i;
                DefenseLoginRequestDTO failReq = new DefenseLoginRequestDTO(testUsername, "wrong-password-" + i, "127.0.0.1");
                mockMvc.perform(post("/sample/satoken/defense/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(failReq)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(401))
                        .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("剩余重试机会: " + expectedRemain + " 次")));
            }

            DefenseLoginRequestDTO lockReq = new DefenseLoginRequestDTO(testUsername, "wrong-password-5", "127.0.0.1");
            mockMvc.perform(post("/sample/satoken/defense/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(lockReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403))
                    .andExpect(jsonPath("$.msg").value("登录失败次数过多，已被锁定，请稍后再试"));

            assertThat(loginDefenseService.isLocked(testUsername)).isTrue();

            mockMvc.perform(post("/sample/satoken/defense/unlock-account")
                            .param("account", testUsername))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            assertThat(loginDefenseService.isLocked(testUsername)).isFalse();
        } finally {
            LoginHelper.untieDisable(testUsername);
            loginDefenseService.clearFailure(testUsername);
        }
    }

    @Test
    @DisplayName("29. 多账号体系 (Multi-Account / loginType) 独立登录、会话与注销隔离端到端")
    void testMultiAccountIsolationLifecycle() throws Exception {
        LoginRequestDTO adminReq = LoginRequestDTO.builder()
                .userId("admin_888")
                .username("sys_admin")
                .roles(Set.of("super_admin"))
                .build();
        MvcResult adminLoginRes = mockMvc.perform(post("/sample/satoken/multi-account/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isString())
                .andReturn();
        String adminToken = objectMapper.readTree(adminLoginRes.getResponse().getContentAsString()).get("data").asText();
        assertThat(adminToken).isNotBlank();

        LoginRequestDTO memberReq = LoginRequestDTO.builder()
                .userId("member_999")
                .username("vip_member")
                .roles(Set.of("vip"))
                .build();
        MvcResult memberLoginRes = mockMvc.perform(post("/sample/satoken/multi-account/member/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(memberReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isString())
                .andReturn();
        String memberToken = objectMapper.readTree(memberLoginRes.getResponse().getContentAsString()).get("data").asText();
        assertThat(memberToken).isNotBlank();

        try {
            mockMvc.perform(get("/sample/satoken/multi-account/admin/me")
                            .header("satoken-admin", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.userId").value("admin_888"))
                    .andExpect(jsonPath("$.data.userName").value("sys_admin"));

            mockMvc.perform(get("/sample/satoken/multi-account/member/me")
                            .header("satoken-member", memberToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.userId").value("member_999"))
                    .andExpect(jsonPath("$.data.userName").value("vip_member"));

            mockMvc.perform(post("/sample/satoken/multi-account/admin/logout")
                            .header("satoken-admin", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(get("/sample/satoken/multi-account/admin/me")
                            .header("satoken-admin", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401));

            mockMvc.perform(get("/sample/satoken/multi-account/member/me")
                            .header("satoken-member", memberToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.userId").value("member_999"));
        } finally {
            mockMvc.perform(post("/sample/satoken/multi-account/member/logout")
                    .header("satoken-member", memberToken));
        }
    }

    @Test
    @DisplayName("30. 客户端 IP 自动嗅探与防爆破熔断端到端（多账号撞库触发 IP 锁定拦截 403）")
    void testClientIpBruteForceDefense() throws Exception {
        assertThat(loginDefenseService).isNotNull();

        String attackerIp = "198.51.100.99";
        try {
            for (int i = 1; i <= 5; i++) {
                String victimAccount = "victim_account_" + i;
                DefenseLoginRequestDTO failReq = new DefenseLoginRequestDTO(victimAccount, "wrong-pass", attackerIp);

                mockMvc.perform(post("/sample/satoken/defense/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(failReq)))
                        .andExpect(status().isOk());
            }

            assertThat(loginDefenseService.isIpLocked(attackerIp)).isTrue();

            DefenseLoginRequestDTO nextReq = new DefenseLoginRequestDTO("victim_account_new", "any-pass", attackerIp);
            mockMvc.perform(post("/sample/satoken/defense/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(nextReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403))
                    .andExpect(jsonPath("$.msg").value("客户端 IP 连续登录失败次数超限，已被临时锁定"));

            mockMvc.perform(get("/sample/satoken/defense/ip-status")
                            .param("clientIp", attackerIp))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.locked").value(true));
        } finally {
            loginDefenseService.clearIpFailure(attackerIp);
            loginDefenseService.unlockIp(attackerIp);
            for (int i = 1; i <= 5; i++) {
                loginDefenseService.clearFailure("victim_account_" + i);
            }
        }
    }

    @Test
    @DisplayName("31. 微服务内部 Same-Token 发起端客户端拦截器自动透传端到端")
    void testSameTokenClientInterceptorAutoInjection() {
        if (restTemplateBuilder != null) {
            RestTemplate restTemplate = restTemplateBuilder.build();
            assertThat(restTemplate.getInterceptors())
                    .anyMatch(i -> i instanceof io.github.atengk.boot.satoken.interceptor.SameTokenClientHttpRequestInterceptor);

            org.springframework.test.web.client.MockRestServiceServer server =
                    org.springframework.test.web.client.MockRestServiceServer.bindTo(restTemplate).build();

            server.expect(requestTo("http://internal-service/sample/satoken/internal/clearing/voucher"))
                    .andExpect(header("SA-SAME-TOKEN", org.hamcrest.Matchers.notNullValue()))
                    .andRespond(withSuccess("{\"code\":200,\"success\":true,\"data\":\"ok\"}", MediaType.APPLICATION_JSON));

            String response = restTemplate.getForObject("http://internal-service/sample/satoken/internal/clearing/voucher", String.class);
            assertThat(response).contains("200");
            server.verify();
        }
    }

    @Test
    @DisplayName("32. 在线会话实时监控与精准强退治理端到端（人数统计、游标分页与 Token/Device 踢出）")
    void testOnlineUserGovernanceLifecycle() throws Exception {
        LoginRequestDTO user1 = LoginRequestDTO.builder()
                .userId("online_user_01")
                .username("tester_pc")
                .device("PC")
                .build();
        MvcResult res1 = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user1)))
                .andExpect(status().isOk())
                .andReturn();
        String token1 = objectMapper.readTree(res1.getResponse().getContentAsString()).get("data").asText();

        LoginRequestDTO user2 = LoginRequestDTO.builder()
                .userId("online_user_02")
                .username("tester_app")
                .device("APP")
                .build();
        MvcResult res2 = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user2)))
                .andExpect(status().isOk())
                .andReturn();
        String token2 = objectMapper.readTree(res2.getResponse().getContentAsString()).get("data").asText();

        try {
            MvcResult countRes = mockMvc.perform(get("/sample/satoken/online/count"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn();
            long onlineCount = Long.parseLong(objectMapper.readTree(countRes.getResponse().getContentAsString()).get("data").asText());
            assertThat(onlineCount).isGreaterThanOrEqualTo(2);

            MvcResult pageRes = mockMvc.perform(get("/sample/satoken/online/page")
                            .param("keyword", "tester_pc")
                            .param("page", "1")
                            .param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray())
                    .andReturn();

            mockMvc.perform(post("/sample/satoken/online/kickout-token")
                            .param("tokenValue", token1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(get("/sample/satoken/auth/me")
                            .header("satoken", token1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401));

            mockMvc.perform(get("/sample/satoken/auth/me")
                            .header("satoken", token2))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(post("/sample/satoken/online/kickout-device")
                            .param("userId", "online_user_02")
                            .param("device", "APP"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(get("/sample/satoken/auth/me")
                            .header("satoken", token2))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401));
        } finally {
            LoginHelper.kickoutByToken(token1);
            LoginHelper.kickoutByToken(token2);
        }
    }

    @Test
    @DisplayName("33. 数据库驱动的动态 URL 路径权限匹配端到端（AntPath + HTTP Method 鉴权与 403 阻断）")
    void testDynamicPathPermissionPipeline() throws Exception {
        LoginRequestDTO normalUser = LoginRequestDTO.builder()
                .userId("dyn_normal_user")
                .username("normal_user")
                .permissions(Set.of("user:query"))
                .build();
        MvcResult normalRes = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(normalUser)))
                .andExpect(status().isOk())
                .andReturn();
        String normalToken = objectMapper.readTree(normalRes.getResponse().getContentAsString()).get("data").asText();

        LoginRequestDTO authUser = LoginRequestDTO.builder()
                .userId("dyn_auth_user")
                .username("auth_user")
                .permissions(Set.of("portal:report:view", "portal:report:export"))
                .build();
        MvcResult authRes = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(authUser)))
                .andExpect(status().isOk())
                .andReturn();
        String authToken = objectMapper.readTree(authRes.getResponse().getContentAsString()).get("data").asText();

        LoginRequestDTO superUser = LoginRequestDTO.builder()
                .userId("dyn_super_user")
                .username("super_admin")
                .roles(Set.of("super_admin"))
                .build();
        MvcResult superRes = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(superUser)))
                .andExpect(status().isOk())
                .andReturn();
        String superToken = objectMapper.readTree(superRes.getResponse().getContentAsString()).get("data").asText();

        try {
            mockMvc.perform(get("/sample/satoken/portal/reports/daily")
                            .header("satoken", normalToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403))
                    .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("portal:report:view")));

            mockMvc.perform(get("/sample/satoken/portal/reports/unprotected")
                            .header("satoken", normalToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("无约束报表数据公开汇总"));

            mockMvc.perform(get("/sample/satoken/portal/reports/daily")
                            .header("satoken", authToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("每日运营分析报表数据汇总"));

            mockMvc.perform(post("/sample/satoken/portal/reports/daily")
                            .header("satoken", authToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("每日运营分析报表导出任务已提交"));

            mockMvc.perform(delete("/sample/satoken/portal/reports/daily")
                            .header("satoken", authToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));

            mockMvc.perform(delete("/sample/satoken/portal/reports/daily")
                            .header("satoken", superToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("每日运营历史归档报表已彻底销毁"));
        } finally {
            LoginHelper.kickoutByToken(normalToken);
            LoginHelper.kickoutByToken(authToken);
            LoginHelper.kickoutByToken(superToken);
        }
    }

    @Test
    @DisplayName("36. 全真模式验证：Local 激活时真实 Redis 基础设施与分布式会话持久层正常生效")
    void shouldVerifyRealRedisInfrastructureWhenLocalActive() throws Exception {
        Assumptions.assumeTrue(isLocalProfileActive(), "当前非 local 真实联调环境，自动跳过真实 Redis 底层存储与 TTL 校验");
        assertThat(redisTemplate).isNotNull();

        String pingResult = redisTemplate.getConnectionFactory().getConnection().ping();
        assertThat(pingResult).isEqualTo("PONG");

        LoginRequestDTO localUser = LoginRequestDTO.builder()
                .userId("999001")
                .username("test-local-user")
                .tenantId("tenant-local")
                .build();
        MvcResult res = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(localUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String token = objectMapper.readTree(res.getResponse().getContentAsString()).get("data").asText();
        assertThat(token).isNotBlank();

        try {
            Set<String> tokenKeys = redisTemplate.keys("satoken:login:token:" + token);
            assertThat(tokenKeys).isNotEmpty();
            Long expire = redisTemplate.getExpire(tokenKeys.iterator().next());
            assertThat(expire).isGreaterThan(0L);

            if (loginDefenseService != null) {
                for (int i = 0; i < 3; i++) {
                    loginDefenseService.recordFailure("test-defense-account");
                }
                Set<String> defenseKeys = redisTemplate.keys(io.github.atengk.boot.satoken.defense.LoginFailureLockService.KEY_PREFIX + "test-defense-account*");
                assertThat(defenseKeys).isNotEmpty();
                loginDefenseService.clearFailure("test-defense-account");
            }
        } finally {
            LoginHelper.kickoutByToken(token);
        }
    }

    @Test
    @DisplayName("37. 验证 AuthSessionController 会话认证、多端登录、租户上下文提取与租户订单全链路")
    void shouldVerifyAuthSessionControllerEndpoints() throws Exception {
        LoginRequestDTO req = new LoginRequestDTO();
        req.setUserId("10086");
        req.setUsername("admin-satoken");
        req.setTenantId("tenant_sz");
        req.setDevice("PC");
        req.setRoles(Set.of("audit_manager"));
        req.setPermissions(Set.of("order:query"));

        MvcResult loginRes = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        String token = objectMapper.readTree(loginRes.getResponse().getContentAsString()).get("data").asText();
        assertThat(token).isNotBlank();

        try {
            // 验证 /sample/satoken/auth/me
            mockMvc.perform(get("/sample/satoken/auth/me")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.userId").value("10086"))
                    .andExpect(jsonPath("$.data.tenantId").value("tenant_sz"));

            // 验证 /sample/satoken/auth/user-id
            mockMvc.perform(get("/sample/satoken/auth/user-id")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("10086"));

            // 验证 /sample/satoken/auth/current-tenant
            mockMvc.perform(get("/sample/satoken/auth/current-tenant")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("tenant_sz"));

            // 验证 /sample/satoken/auth/check-login
            mockMvc.perform(get("/sample/satoken/auth/check-login")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("已登录"));

            // 验证 /sample/satoken/auth/orders 与 /sample/satoken/auth/orders/{orderId}
            OrderCreateDTO orderReq = new OrderCreateDTO();
            orderReq.setOrderNo("ORDER-SAMPLE-AUTH-001");
            orderReq.setAmount(new BigDecimal("199.99"));

            MvcResult orderRes = mockMvc.perform(post("/sample/satoken/auth/orders")
                            .header("satoken", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(orderReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn();

            Long orderId = objectMapper.readTree(orderRes.getResponse().getContentAsString()).get("data").asLong();
            assertThat(orderId).isNotNull();

            mockMvc.perform(get("/sample/satoken/auth/orders/" + orderId)
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.orderNo").value("ORDER-SAMPLE-AUTH-001"))
                    .andExpect(jsonPath("$.data.tenantId").value("tenant_sz"))
                    .andExpect(jsonPath("$.data.createBy").value("10086"));

            // 验证 /sample/satoken/auth/logout-device
            mockMvc.perform(post("/sample/satoken/auth/logout-device")
                            .param("device", "PC")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 注销后访问受保护接口返回 401
            mockMvc.perform(get("/sample/satoken/auth/check-login")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401));
        } finally {
            LoginHelper.kickoutByToken(token);
        }
    }

    @Test
    @DisplayName("38. 验证 AccessControlController RBAC 角色/权限注解校验、二级安全认证与防水平越权防御")
    void shouldVerifyAccessControlControllerEndpoints() throws Exception {
        LoginRequestDTO req = new LoginRequestDTO();
        req.setUserId("10086");
        req.setUsername("admin-access");
        req.setTenantId("tenant_sz");
        req.setRoles(Set.of("audit_manager"));
        req.setPermissions(Set.of("order:query", "order:cancel"));

        MvcResult loginRes = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        String token = objectMapper.readTree(loginRes.getResponse().getContentAsString()).get("data").asText();
        assertThat(token).isNotBlank();

        try {
            // 1. RBAC 角色注解校验
            mockMvc.perform(get("/sample/satoken/access/role-audit")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("审计角色鉴权通过"));

            mockMvc.perform(get("/sample/satoken/access/role-finance-only")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));

            // 2. RBAC 权限注解校验
            mockMvc.perform(get("/sample/satoken/access/perm-order-query")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("订单查询权限鉴权通过"));

            mockMvc.perform(get("/sample/satoken/access/perm-order-delete")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));

            // 3. Service 层切面拦截
            mockMvc.perform(get("/sample/satoken/access/service-role")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));

            mockMvc.perform(get("/sample/satoken/access/service-permission")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("Service 权限校验通过"));

            // 4. 二级安全认证
            SettlementRequestDTO settleDto = new SettlementRequestDTO();
            settleDto.setSettlementNo("SETTLE-20261108-001");
            settleDto.setAmount(new BigDecimal("8888.00"));
            settleDto.setRecipientAccount("ACC-6222-0001-9999");

            mockMvc.perform(post("/sample/satoken/access/safe-settle")
                            .header("satoken", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(settleDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));

            mockMvc.perform(post("/sample/satoken/access/open-safe")
                            .header("satoken", token)
                            .param("timeout", "60"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(60));

            mockMvc.perform(post("/sample/satoken/access/safe-settle")
                            .header("satoken", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(settleDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(post("/sample/satoken/access/safe-settle-anno")
                            .header("satoken", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(settleDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(post("/sample/satoken/access/close-safe")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(post("/sample/satoken/access/safe-settle")
                            .header("satoken", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(settleDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));

            // 5. @CheckDataOwner 防水平越权
            UserProfileDTO selfProfile = new UserProfileDTO();
            selfProfile.setUserId("10086");
            selfProfile.setNickname("本人昵称");
            mockMvc.perform(post("/sample/satoken/access/profile/update")
                            .header("satoken", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(selfProfile)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            UserProfileDTO otherProfile = new UserProfileDTO();
            otherProfile.setUserId("20001");
            otherProfile.setNickname("越权昵称");
            mockMvc.perform(post("/sample/satoken/access/profile/update")
                            .header("satoken", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(otherProfile)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));

            // 6. @CheckDataOwner + Provider 反查所有者
            mockMvc.perform(post("/sample/satoken/access/orders/88001/cancel")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(post("/sample/satoken/access/orders/88002/cancel")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));

            // 7. 编程式断言本人与租户
            mockMvc.perform(post("/sample/satoken/access/assert-self")
                            .header("satoken", token)
                            .param("userId", "10086"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(post("/sample/satoken/access/assert-self")
                            .header("satoken", token)
                            .param("userId", "99999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));

            mockMvc.perform(post("/sample/satoken/access/assert-tenant")
                            .header("satoken", token)
                            .param("tenantId", "tenant_sz"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(post("/sample/satoken/access/assert-tenant")
                            .header("satoken", token)
                            .param("tenantId", "tenant_other"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
        } finally {
            LoginHelper.kickoutByToken(token);
        }
    }

    @Test
    @DisplayName("39. 验证 PermissionCacheController 缓存角色与权限查询、单角色/单用户刷新与全量清空")
    void shouldVerifyPermissionCacheControllerEndpoints() throws Exception {
        LoginRequestDTO req = new LoginRequestDTO();
        req.setUserId("10086");
        req.setUsername("admin-cache");
        req.setTenantId("tenant_sz");
        req.setRoles(Set.of("audit_manager"));
        req.setPermissions(Set.of("order:query"));

        MvcResult loginRes = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        String token = objectMapper.readTree(loginRes.getResponse().getContentAsString()).get("data").asText();
        assertThat(token).isNotBlank();

        try {
            // 1. 查询当前角色与权限
            mockMvc.perform(get("/sample/satoken/permission-cache/roles")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data[0]").value("audit_manager"));

            mockMvc.perform(get("/sample/satoken/permission-cache/permissions")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data[0]").value("order:query"));

            // 2. 刷新角色缓存
            mockMvc.perform(post("/sample/satoken/permission-cache/refresh-role")
                            .param("roleCode", "audit_manager")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 3. 刷新用户缓存
            mockMvc.perform(post("/sample/satoken/permission-cache/refresh-user")
                            .param("userId", "10086")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 4. 清空全量缓存
            mockMvc.perform(post("/sample/satoken/permission-cache/clear-all")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        } finally {
            LoginHelper.kickoutByToken(token);
        }
    }

    @Test
    @DisplayName("40. 验证 LoginDefenseController 账号与客户端 IP 双轨防爆破、锁定状态查询与管理员紧急解锁")
    void shouldVerifyLoginDefenseControllerEndpoints() throws Exception {
        String testAccount = "defense_slice_user";
        String testIp = "192.168.10.88";

        try {
            // 0. 初始防爆破锁定状态查询
            mockMvc.perform(get("/sample/satoken/defense/account-status")
                            .param("account", testAccount))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.locked").value(false))
                    .andExpect(jsonPath("$.data.failureCount").value(0))
                    .andExpect(jsonPath("$.data.remainRetryCount").value(5));

            mockMvc.perform(get("/sample/satoken/defense/ip-status")
                            .param("clientIp", testIp))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.locked").value(false))
                    .andExpect(jsonPath("$.data.whiteListed").value(false));

            // 1. 模拟连续 4 次输错密码（剩余机会 4 -> 1 递减，HTTP 200 + 业务码 401）
            for (int i = 1; i <= 4; i++) {
                int expectedRemain = 5 - i;
                DefenseLoginRequestDTO failReq = new DefenseLoginRequestDTO();
                failReq.setAccount(testAccount);
                failReq.setPassword("wrong_pwd_" + i);
                failReq.setClientIp(testIp);

                mockMvc.perform(post("/sample/satoken/defense/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(failReq)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(401))
                        .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("剩余重试机会: " + expectedRemain + " 次")));
            }

            // 2. 第 5 次输错密码：达到阈值，触发锁定阻断（HTTP 200 + 业务码 403）
            DefenseLoginRequestDTO lockReq = new DefenseLoginRequestDTO();
            lockReq.setAccount(testAccount);
            lockReq.setPassword("wrong_pwd_5");
            lockReq.setClientIp(testIp);

            mockMvc.perform(post("/sample/satoken/defense/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(lockReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403))
                    .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("登录失败次数过多，已被锁定")));

            // 3. 验证锁定状态
            mockMvc.perform(get("/sample/satoken/defense/account-status")
                            .param("account", testAccount))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.locked").value(true))
                    .andExpect(jsonPath("$.data.failureCount").value(5))
                    .andExpect(jsonPath("$.data.remainRetryCount").value(0))
                    .andExpect(jsonPath("$.data.lockTime").value(org.hamcrest.Matchers.notNullValue()));

            mockMvc.perform(get("/sample/satoken/defense/ip-status")
                            .param("clientIp", testIp))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.locked").value(true));

            // 4. 被锁定后，即使输入正确密码也直接在防卫拦截处 Fail-Fast（HTTP 200 + 业务码 403）
            DefenseLoginRequestDTO blockedReq = new DefenseLoginRequestDTO();
            blockedReq.setAccount(testAccount);
            blockedReq.setPassword("123456");
            blockedReq.setClientIp(testIp);

            mockMvc.perform(post("/sample/satoken/defense/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(blockedReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));

            // 5. 管理员紧急解锁账号与解除客户端 IP 熔断
            mockMvc.perform(post("/sample/satoken/defense/unlock-account")
                            .param("account", testAccount))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsString(testAccount)));

            mockMvc.perform(post("/sample/satoken/defense/unlock-ip")
                            .param("clientIp", testIp))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsString(testIp)));

            // 6. 解锁后验证状态恢复正常
            mockMvc.perform(get("/sample/satoken/defense/account-status")
                            .param("account", testAccount))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.locked").value(false))
                    .andExpect(jsonPath("$.data.remainRetryCount").value(5));

            mockMvc.perform(get("/sample/satoken/defense/ip-status")
                            .param("clientIp", testIp))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.locked").value(false));

            // 7. 解锁后使用正确密码成功登录并下发 Token
            MvcResult successRes = mockMvc.perform(post("/sample/satoken/defense/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(blockedReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isString())
                    .andReturn();

            String token = objectMapper.readTree(successRes.getResponse().getContentAsString()).get("data").asText();
            assertThat(token).isNotBlank();
            LoginHelper.kickoutByToken(token);
        } finally {
            if (loginDefenseService != null) {
                loginDefenseService.clearAllFailure(testAccount, testIp);
            }
            LoginHelper.untieDisable(testAccount);
            LoginHelper.kickout("user-" + testAccount);
        }
    }

    @Test
    @DisplayName("41. 验证 OnlineSessionController 在线会话统计与检索、按Token/设备/全端强退、顶替下线、阶梯封禁解封与受限业务校验")
    void shouldVerifyOnlineSessionControllerEndpoints() throws Exception {
        LoginRequestDTO user1Req = new LoginRequestDTO();
        user1Req.setUserId("online_user_a");
        user1Req.setUsername("online_user_a");
        user1Req.setDevice("PC");

        MvcResult login1Res = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user1Req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String token1 = objectMapper.readTree(login1Res.getResponse().getContentAsString()).get("data").asText();

        LoginRequestDTO user2Req = new LoginRequestDTO();
        user2Req.setUserId("online_user_b");
        user2Req.setUsername("online_user_b");

        MvcResult login2Res = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user2Req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String token2 = objectMapper.readTree(login2Res.getResponse().getContentAsString()).get("data").asText();

        try {
            // 1. 获取在线会话总数
            mockMvc.perform(get("/sample/satoken/online/count"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.notNullValue()));

            // 2. 分页检索在线会话
            mockMvc.perform(get("/sample/satoken/online/page")
                            .param("keyword", "online_user")
                            .param("page", "1")
                            .param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray())
                    .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.notNullValue()));

            // 3. 初始阶梯封禁状态与受限操作调用
            mockMvc.perform(get("/sample/satoken/online/disable-status")
                            .param("userId", "online_user_a"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.disabled").value(false));

            mockMvc.perform(get("/sample/satoken/online/restricted-action")
                            .header("satoken", token1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 4. 对 online_user_a 施加阶梯封禁 (120 秒)
            mockMvc.perform(post("/sample/satoken/online/disable")
                            .param("userId", "online_user_a")
                            .param("durationSeconds", "120"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(get("/sample/satoken/online/disable-status")
                            .param("userId", "online_user_a"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.disabled").value(true))
                    .andExpect(jsonPath("$.data.disableTime").value(org.hamcrest.Matchers.notNullValue()));

            // 5. 封禁期间受限操作被 @SaCheckDisable 拦截 (HTTP 200 + 业务码 403)
            mockMvc.perform(get("/sample/satoken/online/restricted-action")
                            .header("satoken", token1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));

            // 6. 解封账号并验证恢复可用
            mockMvc.perform(post("/sample/satoken/online/untie-disable")
                            .param("userId", "online_user_a"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(get("/sample/satoken/online/restricted-action")
                            .header("satoken", token1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 7. 模拟同端顶替下线 (replaced)
            mockMvc.perform(post("/sample/satoken/online/replaced")
                            .param("userId", "online_user_a")
                            .param("device", "PC"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 顶替后原 Token 调用受限接口被识别为 401 顶替下线
            mockMvc.perform(get("/sample/satoken/online/restricted-action")
                            .header("satoken", token1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401));

            // 8. 强退测试 (kickout-token, kickout-device, kickout-user)
            mockMvc.perform(post("/sample/satoken/online/kickout-token")
                            .param("tokenValue", token2))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(post("/sample/satoken/online/kickout-device")
                            .param("userId", "online_user_b")
                            .param("device", "PC"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(post("/sample/satoken/online/kickout-user")
                            .param("userId", "online_user_b"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        } finally {
            LoginHelper.untieDisable("online_user_a");
            LoginHelper.untieDisable("online_user_b");
            LoginHelper.kickoutByToken(token1);
            LoginHelper.kickoutByToken(token2);
            LoginHelper.kickout("online_user_a");
            LoginHelper.kickout("online_user_b");
        }
    }

    @Test
    @DisplayName("42. 验证 MultiAccountController 管理端与会员端独立双轨登录、凭据隔离、基于 loginType 权限校验与独立注销")
    void shouldVerifyMultiAccountControllerEndpoints() throws Exception {
        LoginRequestDTO adminReq = new LoginRequestDTO();
        adminReq.setUserId("admin_multi_01");
        adminReq.setUsername("admin_multi_01");
        adminReq.setRoles(Set.of("super_admin"));
        adminReq.setPermissions(Set.of("system:manage"));

        LoginRequestDTO memberReq = new LoginRequestDTO();
        memberReq.setUserId("member_multi_01");
        memberReq.setUsername("member_multi_01");
        memberReq.setRoles(Set.of("vip_member"));
        memberReq.setPermissions(Set.of("member:points:view"));

        // 1. 管理端登录并获取 admin Token
        MvcResult adminRes = mockMvc.perform(post("/sample/satoken/multi-account/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isString())
                .andReturn();
        String adminToken = objectMapper.readTree(adminRes.getResponse().getContentAsString()).get("data").asText();
        assertThat(adminToken).isNotBlank();

        // 2. 会员端登录并获取 member Token
        MvcResult memberRes = mockMvc.perform(post("/sample/satoken/multi-account/member/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(memberReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isString())
                .andReturn();
        String memberToken = objectMapper.readTree(memberRes.getResponse().getContentAsString()).get("data").asText();
        assertThat(memberToken).isNotBlank();

        try {
            // 3. 管理端身份校验与特权角色校验 (satoken-admin 请求头)
            mockMvc.perform(get("/sample/satoken/multi-account/admin/me")
                            .header("satoken-admin", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.userId").value("admin_multi_01"));

            mockMvc.perform(get("/sample/satoken/multi-account/admin/manage-roles")
                            .header("satoken-admin", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 跨体系越权防卫：使用会员凭据请求管理端特权接口触发 401
            mockMvc.perform(get("/sample/satoken/multi-account/admin/manage-roles")
                            .header("satoken-admin", memberToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401));

            // 4. 会员端身份校验与特权权限校验 (satoken-member 请求头)
            mockMvc.perform(get("/sample/satoken/multi-account/member/me")
                            .header("satoken-member", memberToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.userId").value("member_multi_01"));

            mockMvc.perform(get("/sample/satoken/multi-account/member/points")
                            .header("satoken-member", memberToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 跨体系越权防卫：使用管理员凭据请求会员专属积分特权接口触发 401
            mockMvc.perform(get("/sample/satoken/multi-account/member/points")
                            .header("satoken-member", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401));

            // 5. 注销管理端会话
            mockMvc.perform(post("/sample/satoken/multi-account/admin/logout")
                            .header("satoken-admin", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 验证管理端会话已注销失效 (401)
            mockMvc.perform(get("/sample/satoken/multi-account/admin/me")
                            .header("satoken-admin", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401));

            // 验证会员端会话依然完全活跃，独立不受影响 (200)
            mockMvc.perform(get("/sample/satoken/multi-account/member/me")
                            .header("satoken-member", memberToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.userId").value("member_multi_01"));

            // 6. 注销会员端会话
            mockMvc.perform(post("/sample/satoken/multi-account/member/logout")
                            .header("satoken-member", memberToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(get("/sample/satoken/multi-account/member/me")
                            .header("satoken-member", memberToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401));
        } finally {
            try {
                LoginHelper.logoutByTokenValue("admin", adminToken);
            } catch (Exception ignored) {
            }
            try {
                LoginHelper.logoutByTokenValue("member", memberToken);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @DisplayName("43. 验证 ImpersonateAuditController 客服持久会话切换、瞬态函数式模拟订单补偿与安全审计事件流水")
    void shouldVerifyImpersonateAuditControllerEndpoints() throws Exception {
        LoginRequestDTO operatorReq = new LoginRequestDTO();
        operatorReq.setUserId("operator_88001");
        operatorReq.setUsername("operator_88001");
        operatorReq.setTenantId("tenant_sz");

        MvcResult loginRes = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(operatorReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        String token = objectMapper.readTree(loginRes.getResponse().getContentAsString()).get("data").asText();
        assertThat(token).isNotBlank();

        try {
            // 0. 清空审计事件流水
            mockMvc.perform(post("/sample/satoken/impersonate/events/clear"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 1. 查询初始模拟状态
            mockMvc.perform(get("/sample/satoken/impersonate/status")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.isRunAs").value(false))
                    .andExpect(jsonPath("$.data.currentUserId").value("operator_88001"))
                    .andExpect(jsonPath("$.data.realUserId").value("operator_88001"));

            // 2. 开启客服持久会话切换 (switchTo)
            mockMvc.perform(post("/sample/satoken/impersonate/switch")
                            .header("satoken", token)
                            .param("targetUserId", "target_user_10086"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 3. 验证切换后身份状态 (currentUserId 变为 10086，real/original 仍为 operator_88001)
            mockMvc.perform(get("/sample/satoken/impersonate/status")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.isRunAs").value(true))
                    .andExpect(jsonPath("$.data.currentUserId").value("target_user_10086"))
                    .andExpect(jsonPath("$.data.realUserId").value("operator_88001"))
                    .andExpect(jsonPath("$.data.originalUserId").value("operator_88001"));

            // 4. 结束会话切换 (endSwitch)
            mockMvc.perform(post("/sample/satoken/impersonate/end-switch")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 验证恢复原始身份
            mockMvc.perform(get("/sample/satoken/impersonate/status")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.isRunAs").value(false))
                    .andExpect(jsonPath("$.data.currentUserId").value("operator_88001"));

            // 5. 瞬态函数式模拟补偿客诉订单 (runAs)
            MvcResult orderRes = mockMvc.perform(post("/sample/satoken/impersonate/compensate-order")
                            .header("satoken", token)
                            .param("targetUserId", "vip_client_20002")
                            .param("orderNo", "COMP-ORD-9999")
                            .param("amount", "188.50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.notNullValue()))
                    .andReturn();

            Long orderId = objectMapper.readTree(orderRes.getResponse().getContentAsString()).get("data").asLong();
            assertThat(orderId).isNotNull();

            // 验证数据库持久层：createBy 自动注入瞬态模拟的目标用户 vip_client_20002
            TenantOrderEntity order = orderMapper.selectById(orderId);
            assertThat(order).isNotNull();
            assertThat(order.getCreateBy()).isEqualTo("vip_client_20002");
            assertThat(order.getOrderNo()).isEqualTo("COMP-ORD-9999");
            assertThat(order.getAmount()).isEqualByComparingTo(new BigDecimal("188.50"));

            // 验证代码块执行完毕后，现场自动恢复为 operator_88001
            mockMvc.perform(get("/sample/satoken/impersonate/status")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.isRunAs").value(false))
                    .andExpect(jsonPath("$.data.currentUserId").value("operator_88001"));

            // 6. 审计事件流水检索与清空验证
            // 触发一次封禁与解封以产生安全事件
            mockMvc.perform(post("/sample/satoken/online/disable")
                            .param("userId", "audit_test_user")
                            .param("durationSeconds", "60"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(get("/sample/satoken/impersonate/events"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].eventType").exists());

            mockMvc.perform(post("/sample/satoken/online/untie-disable")
                            .param("userId", "audit_test_user"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(post("/sample/satoken/impersonate/events/clear"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(get("/sample/satoken/impersonate/events"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isEmpty());
        } finally {
            if (LoginHelper.isRunAs()) {
                LoginHelper.endSwitch();
            }
            LoginHelper.kickoutByToken(token);
            LoginHelper.kickout("operator_88001");
            LoginHelper.kickout("target_user_10086");
        }
    }

    @Test
    @DisplayName("44. 验证 PublicPortalController 公开门户、方法级 @Anonymous(false) 翻转保护、SPI 开放类目与动态报表权限热刷新")
    void shouldVerifyPublicPortalControllerEndpoints() throws Exception {
        // 1. 公开公告与商品详情（类级 @Anonymous 放行）
        mockMvc.perform(get("/sample/satoken/portal/announcement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("【系统公告】双十一全场云原生基础设施 Starter 限时特惠开启！"));

        mockMvc.perform(get("/sample/satoken/portal/products/P-1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.productId").value("P-1001"))
                .andExpect(jsonPath("$.data.title").value("Spring Boot 3 企业级微服务开发实战"));

        // 2. VIP 专属底价查询（方法级 @Anonymous(false) 翻转拦截，未登录 401）
        mockMvc.perform(get("/sample/satoken/portal/products/P-1001/vip-price"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        // 登录用户访问 VIP 专属底价
        LoginRequestDTO userReq = new LoginRequestDTO();
        userReq.setUserId("portal_vip_user");
        userReq.setUsername("vip_user");
        userReq.setRoles(Set.of("vip_member"));
        userReq.setPermissions(Set.of("portal:report:view", "portal:report:export"));

        MvcResult loginRes = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(userReq)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginRes.getResponse().getContentAsString()).get("data").asText();

        try {
            mockMvc.perform(get("/sample/satoken/portal/products/P-1001/vip-price")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.vipPrice").value(69.00));

            // 3. SPI 动态白名单：开放商品类目（方法级 @Anonymous(false)，由 SampleWhitelistProvider 放行）
            mockMvc.perform(get("/sample/satoken/portal/categories"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasItems("云计算", "中间件", "数据库", "开发框架")));

            // 4. 查看当前生效的动态路径权限规则
            mockMvc.perform(get("/sample/satoken/portal/rules"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));

            // 5. 动态路径权限受控接口验证：具备 portal:report:view
            mockMvc.perform(get("/sample/satoken/portal/reports/daily")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("每日运营分析报表数据汇总"));

            // 具备 portal:report:export
            mockMvc.perform(post("/sample/satoken/portal/reports/daily")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("每日运营分析报表导出任务已提交"));

            // 缺少 portal:report:delete 和 audit 双权限 -> 403
            mockMvc.perform(delete("/sample/satoken/portal/reports/daily")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));

            // 6. 无规则约束报表
            mockMvc.perform(get("/sample/satoken/portal/reports/unprotected")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("无约束报表数据公开汇总"));

            // 7. 运行时热刷新动态路径权限规则
            mockMvc.perform(post("/sample/satoken/portal/rules/refresh"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        } finally {
            LoginHelper.kickoutByToken(token);
        }
    }

    @Test
    @DisplayName("45. 验证 InternalClusterController 微服务内部 @CheckSameToken 零信任安全、RestClient 自动透传与集群广播")
    void shouldVerifyInternalClusterControllerEndpoints() throws Exception {
        // 1. 未携带 Same-Token 凭据访问机密清算接口 -> 403
        mockMvc.perform(get("/sample/satoken/internal/clearing/voucher"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("微服务内部鉴权凭据无效或已失效"));

        // 2. 携带错误 Same-Token 凭据 -> 403
        mockMvc.perform(get("/sample/satoken/internal/clearing/voucher")
                        .header("SA-SAME-TOKEN", "ILLEGAL_TOKEN_BAD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("微服务内部鉴权凭据无效或已失效"));

        // 3. 携带合法 Same-Token 凭据（静态配置值） -> 200
        mockMvc.perform(get("/sample/satoken/internal/clearing/voucher")
                        .header("SA-SAME-TOKEN", "SATOKEN_INTERNAL_SAME_TOKEN_SECRET_987654"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("清算凭证数据 [CLR-20261108-9988]"));

        // 4. 发起内部调用演示，验证 RestClient 自动挂载 SameTokenClientHttpRequestInterceptor 透传凭据
        if (restClientBuilder != null) {
            org.springframework.test.web.client.MockRestServiceServer server =
                    org.springframework.test.web.client.MockRestServiceServer.bindTo(
                            (internalClusterController != null && internalClusterController.getRestClientBuilder() != null)
                                    ? internalClusterController.getRestClientBuilder()
                                    : restClientBuilder).build();
            server.expect(requestTo("http://localhost:8080/sample/satoken/internal/clearing/voucher"))
                    .andExpect(header("SA-SAME-TOKEN", "SATOKEN_INTERNAL_SAME_TOKEN_SECRET_987654"))
                    .andRespond(withSuccess("{\"code\":200,\"success\":true,\"data\":\"clearing_ok\"}", MediaType.APPLICATION_JSON));

            mockMvc.perform(post("/sample/satoken/internal/call-clearing"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsString("clearing_ok")));

            server.verify();
        }

        // 5. 查询集群节点唯一实例 ID
        mockMvc.perform(get("/sample/satoken/cluster/instance-id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(broadcaster.getInstanceId()));

        // 6. 广播指定用户权限缓存失效
        mockMvc.perform(post("/sample/satoken/cluster/refresh-user")
                        .param("userId", "10086"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 7. 广播指定角色权限缓存失效
        mockMvc.perform(post("/sample/satoken/cluster/refresh-role")
                        .param("roleCode", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 8. 广播全量权限缓存清空
        mockMvc.perform(post("/sample/satoken/cluster/clear-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}

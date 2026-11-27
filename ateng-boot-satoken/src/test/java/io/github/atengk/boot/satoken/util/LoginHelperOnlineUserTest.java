package io.github.atengk.boot.satoken.util;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.context.model.SaCookie;
import cn.dev33.satoken.context.model.SaRequest;
import cn.dev33.satoken.context.model.SaResponse;
import cn.dev33.satoken.context.model.SaStorage;
import cn.dev33.satoken.context.model.SaTokenContextModelBox;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.model.OnlineUser;
import io.github.atengk.boot.satoken.model.OnlineUserQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LoginHelper 在线会话监控与强退治理门面单元测试
 * 覆盖：
 * 1. 在线会话总数统计 getOnlineUserCount
 * 2. 在线会话分页检索 searchOnlineUsers 与 PageResult 封装
 * 3. 关键字过滤（按账号、用户名、用户唯一标识与 Token 匹配）
 * 4. kickoutByToken 与 kickoutDevice 精确强退与状态失效
 * 5. 多账号体系 loginType 在线会话与踢出隔离
 * 6. 入参防御与空安全保底返回
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("LoginHelper 在线会话监控与强退治理门面测试")
class LoginHelperOnlineUserTest {

    private SaTokenContext originalContext;
    private final Map<String, Object> storageMap = new ConcurrentHashMap<>();
    private final Map<String, String> headerMap = new ConcurrentHashMap<>();
    private final Map<String, String> cookieMap = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        storageMap.clear();
        headerMap.clear();
        cookieMap.clear();

        originalContext = SaManager.getSaTokenContext();

        SaRequest mockRequest = mock(SaRequest.class);
        when(mockRequest.getHeader(anyString())).thenAnswer(inv -> headerMap.get(inv.getArgument(0)));
        when(mockRequest.getCookieValue(anyString())).thenAnswer(inv -> cookieMap.get(inv.getArgument(0)));

        SaResponse mockResponse = mock(SaResponse.class);
        org.mockito.Mockito.doAnswer(inv -> {
            String name = inv.getArgument(0);
            String value = inv.getArgument(1);
            if (name != null && value != null) {
                headerMap.put(name, value);
            }
            return null;
        }).when(mockResponse).setHeader(anyString(), anyString());

        org.mockito.Mockito.doAnswer(inv -> {
            SaCookie cookie = inv.getArgument(0);
            if (cookie != null && cookie.getName() != null) {
                if (cookie.getMaxAge() == 0) {
                    cookieMap.remove(cookie.getName());
                } else {
                    cookieMap.put(cookie.getName(), cookie.getValue());
                }
            }
            return null;
        }).when(mockResponse).addCookie(org.mockito.ArgumentMatchers.any());

        SaStorage mockStorage = mock(SaStorage.class);
        when(mockStorage.get(anyString())).thenAnswer(inv -> storageMap.get(inv.getArgument(0)));
        org.mockito.Mockito.doAnswer(inv -> {
            storageMap.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(mockStorage).set(anyString(), org.mockito.ArgumentMatchers.any());

        SaTokenContext testContext = new SaTokenContext() {
            @Override
            public void setContext(SaRequest req, SaResponse res, SaStorage sto) {
            }

            @Override
            public void clearContext() {
            }

            @Override
            public SaRequest getRequest() {
                return mockRequest;
            }

            @Override
            public SaResponse getResponse() {
                return mockResponse;
            }

            @Override
            public SaStorage getStorage() {
                return mockStorage;
            }

            @Override
            public boolean isValid() {
                return true;
            }

            @Override
            public SaTokenContextModelBox getModelBox() {
                return new SaTokenContextModelBox(mockRequest, mockResponse, mockStorage);
            }
        };

        SaManager.setSaTokenContext(testContext);
        clearAllSessions();
    }

    @AfterEach
    void tearDown() {
        clearAllSessions();
        SaManager.setSaTokenContext(originalContext);
        storageMap.clear();
        headerMap.clear();
        cookieMap.clear();
    }

    private void clearAllSessions() {
        clearSessionsForLogic(StpUtil.getStpLogic());
        clearSessionsForLogic(LoginHelper.getStpLogic("admin"));
        try {
            StpUtil.logout();
            LoginHelper.getStpLogic("admin").logout();
        } catch (Exception ignored) {
        }
        try {
            SaTokenDao dao = SaManager.getSaTokenDao();
            if (dao != null) {
                Class<?> clazz = dao.getClass();
                while (clazz != null && clazz != Object.class) {
                    for (Field field : clazz.getDeclaredFields()) {
                        if (Map.class.isAssignableFrom(field.getType())) {
                            field.setAccessible(true);
                            Map<?, ?> map = (Map<?, ?>) field.get(dao);
                            if (map != null) {
                                map.clear();
                            }
                        }
                    }
                    clazz = clazz.getSuperclass();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void clearSessionsForLogic(StpLogic logic) {
        try {
            List<String> rawTokens = logic.searchTokenValue("", 0, -1, false);
            if (rawTokens != null) {
                String prefix = logic.splicingKeyTokenValue("");
                for (String raw : rawTokens) {
                    String token = raw.startsWith(prefix) ? raw.substring(prefix.length()) : raw;
                    try {
                        logic.kickoutByTokenValue(token);
                    } catch (Exception ignored) {
                    }
                    try {
                        SaManager.getSaTokenDao().delete(raw);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Test
    @DisplayName("1. 在线会话统计与分页查询：多用户登录后正常组装与分页")
    void testSearchOnlineUsersAndPagination() {
        // 1. 初始状态：在线人数为 0
        assertThat(LoginHelper.getOnlineUserCount()).isEqualTo(0);
        assertThat(LoginHelper.searchOnlineUsers(1, 10)).isEmpty();

        // 2. 依次登录 3 位用户
        LoginUser user1 = LoginUser.builder()
                .userId("1001")
                .userAccount("admin_root")
                .userName("系统管理员")
                .extraData(Map.of("loginIp", "192.168.1.10"))
                .build();
        LoginHelper.login(user1, new SaLoginParameter().setDeviceType("PC"));
        String token1 = StpUtil.getTokenValue();

        LoginUser user2 = LoginUser.builder()
                .userId("1002")
                .userAccount("dev_tom")
                .userName("开发汤姆")
                .extraData(Map.of("loginIp", "192.168.1.20"))
                .build();
        LoginHelper.login(user2, new SaLoginParameter().setDeviceType("APP"));
        String token2 = StpUtil.getTokenValue();

        LoginUser user3 = LoginUser.builder()
                .userId("1003")
                .userAccount("ops_jerry")
                .userName("运维杰瑞")
                .extraData(Map.of("loginIp", "192.168.1.30"))
                .build();
        LoginHelper.login(user3, new SaLoginParameter().setDeviceType("H5"));
        String token3 = StpUtil.getTokenValue();

        // 3. 统计总在线数
        assertThat(LoginHelper.getOnlineUserCount()).isEqualTo(3);

        // 4. 关键字过滤检索
        List<OnlineUser> adminUsers = LoginHelper.searchOnlineUsers("admin", 1, 10);
        assertThat(adminUsers).hasSize(1);
        assertThat(adminUsers.get(0).getUserId()).isEqualTo("1001");
        assertThat(adminUsers.get(0).getUserAccount()).isEqualTo("admin_root");
        assertThat(adminUsers.get(0).getUserName()).isEqualTo("系统管理员");
        assertThat(adminUsers.get(0).getLoginDevice()).isEqualTo("PC");
        assertThat(adminUsers.get(0).getLoginIp()).isEqualTo("192.168.1.10");
        assertThat(adminUsers.get(0).getTokenValue()).isEqualTo(token1);
        assertThat(adminUsers.get(0).getTokenTimeout()).isGreaterThan(0L);

        List<OnlineUser> tomUsers = LoginHelper.searchOnlineUsers("tom", 1, 10);
        assertThat(tomUsers).hasSize(1);
        assertThat(tomUsers.get(0).getUserId()).isEqualTo("1002");
        assertThat(tomUsers.get(0).getUserAccount()).isEqualTo("dev_tom");
        assertThat(tomUsers.get(0).getLoginDevice()).isEqualTo("APP");

        List<OnlineUser> nameUsers = LoginHelper.searchOnlineUsers("杰瑞", 1, 10);
        assertThat(nameUsers).hasSize(1);
        assertThat(nameUsers.get(0).getUserId()).isEqualTo("1003");

        // 5. 分页截断测试 (共 3 条，size = 2)
        List<OnlineUser> page1 = LoginHelper.searchOnlineUsers(null, 1, 2);
        assertThat(page1).hasSize(2);

        List<OnlineUser> page2 = LoginHelper.searchOnlineUsers(null, 2, 2);
        assertThat(page2).hasSize(1);

        List<OnlineUser> page3 = LoginHelper.searchOnlineUsers(null, 3, 2);
        assertThat(page3).isEmpty();

        // 6. PageResult 封装测试
        PageResult<OnlineUser> pageResult = LoginHelper.searchOnlineUserPage(null, 1, 2);
        assertThat(pageResult.getTotal()).isEqualTo(3);
        assertThat(pageResult.getSize()).isEqualTo(2);
        assertThat(pageResult.getCurrent()).isEqualTo(1);
        assertThat(pageResult.getPages()).isEqualTo(2);
        assertThat(pageResult.getRecords()).hasSize(2);
    }

    @Test
    @DisplayName("2. kickoutByToken 精确强退测试：指定 Token 强退后会话即时失效")
    void testKickoutByToken() {
        LoginUser user = LoginUser.builder()
                .userId("2001")
                .userAccount("target_user")
                .userName("被踢用户")
                .build();
        LoginHelper.login(user);
        String token = StpUtil.getTokenValue();
        assertThat(token).isNotBlank();
        assertThat(LoginHelper.getOnlineUserCount()).isEqualTo(1);

        // 调用 kickoutByToken 强退
        LoginHelper.kickoutByToken(token);

        // 校验在线用户与会话均已失效
        assertThat(LoginHelper.getOnlineUserCount()).isEqualTo(0);
        assertThat(LoginHelper.searchOnlineUsers(1, 10)).isEmpty();
    }

    @Test
    @DisplayName("3. kickoutDevice 设备维度强退测试")
    void testKickoutDevice() {
        LoginUser user = LoginUser.builder()
                .userId("3001")
                .userAccount("device_user")
                .userName("设备用户")
                .build();
        LoginHelper.login(user, "APP");
        assertThat(LoginHelper.getOnlineUserCount()).isEqualTo(1);

        // 按账号和设备端踢出
        LoginHelper.kickoutDevice("3001", "APP");

        assertThat(LoginHelper.getOnlineUserCount()).isEqualTo(0);
        assertThat(LoginHelper.searchOnlineUsers(1, 10)).isEmpty();
    }

    @Test
    @DisplayName("4. 多账号体系 loginType 在线会话与踢出隔离")
    void testMultiAccountOnlineUserIsolation() {
        // 1. 登录默认体系用户
        LoginUser defaultUser = LoginUser.builder()
                .userId("4001")
                .userAccount("user_member")
                .userName("普通会员")
                .build();
        LoginHelper.login("login", defaultUser);
        String defaultToken = StpUtil.getTokenValue();

        // 2. 登录 admin 独立体系用户
        LoginUser adminUser = LoginUser.builder()
                .userId("5001")
                .userAccount("admin_master")
                .userName("系统超管")
                .build();
        LoginHelper.login("admin", adminUser);
        StpLogic adminLogic = LoginHelper.getStpLogic("admin");
        String adminToken = adminLogic.getTokenValue();

        // 3. 统计与查询隔离验证
        assertThat(LoginHelper.getOnlineUserCount("login")).isEqualTo(1);
        assertThat(LoginHelper.getOnlineUserCount("admin")).isEqualTo(1);

        List<OnlineUser> defaultList = LoginHelper.searchOnlineUsers("login", null, 1, 10);
        assertThat(defaultList).hasSize(1);
        assertThat(defaultList.get(0).getUserAccount()).isEqualTo("user_member");

        List<OnlineUser> adminList = LoginHelper.searchOnlineUsers("admin", null, 1, 10);
        assertThat(adminList).hasSize(1);
        assertThat(adminList.get(0).getUserAccount()).isEqualTo("admin_master");

        // 4. 强退 admin 会话，不影响 login 默认会话
        LoginHelper.kickoutByToken("admin", adminToken);
        assertThat(LoginHelper.getOnlineUserCount("admin")).isEqualTo(0);
        assertThat(LoginHelper.getOnlineUserCount("login")).isEqualTo(1);
    }

    @Test
    @DisplayName("5. 防御性边界测试：null 与非法分页参数安全防卫")
    void testDefensiveArguments() {
        // kickoutByToken 与 kickoutDevice/kickoutByDevice 空参数不抛异常
        LoginHelper.kickoutByToken(null);
        LoginHelper.kickoutByToken("   ");
        LoginHelper.kickoutByToken("admin", null);
        LoginHelper.kickoutDevice(null, "PC");
        LoginHelper.kickoutDevice("admin", null, "APP");
        LoginHelper.kickoutByDevice(null, "PC");
        LoginHelper.kickoutByDevice("admin", null, "APP");

        // getOnlineUsers 别名方法自适应规整
        PageResult<OnlineUser> aliasResult = LoginHelper.getOnlineUsers(1, 10, null);
        assertThat(aliasResult).isNotNull();
        PageResult<OnlineUser> aliasAdminResult = LoginHelper.getOnlineUsers("admin", 1, 10, null);
        assertThat(aliasAdminResult).isNotNull();

        // 非法分页页码与容量自适应规整
        List<OnlineUser> list = LoginHelper.searchOnlineUsers(-1, -5);
        assertThat(list).isNotNull().isEmpty();

        PageResult<OnlineUser> pageResult = LoginHelper.searchOnlineUserPage(-1, 0);
        assertThat(pageResult).isNotNull();
        assertThat(pageResult.getRecords()).isEmpty();
        assertThat(pageResult.getTotal()).isEqualTo(0);

        // 超大 size 自适应截断至 100 边界防卫
        List<OnlineUser> hugeList = LoginHelper.searchOnlineUsers("unknown_keyword_xxx", 1, 9999);
        assertThat(hugeList).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("6. Web 请求上下文自动采集真实 loginIp，并在 OnlineUser 自动填充 tenantId 与 loginType")
    void testOnlineUserEnrichesClientIpTenantIdAndLoginType() {
        org.springframework.mock.web.MockHttpServletRequest servletRequest =
                new org.springframework.mock.web.MockHttpServletRequest();
        servletRequest.setRemoteAddr("127.0.0.1");
        servletRequest.addHeader("X-Forwarded-For", "203.0.113.88");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(servletRequest));
        try {
            LoginUser adminUser = LoginUser.builder()
                    .userId("6001")
                    .tenantId("tenant-88")
                    .userAccount("admin_ip")
                    .userName("审计员")
                    .build();
            LoginHelper.login("admin", adminUser);

            List<OnlineUser> adminList = LoginHelper.searchOnlineUsers("admin", null, 1, 10);
            assertThat(adminList).hasSize(1);
            OnlineUser onlineAdmin = adminList.get(0);
            assertThat(onlineAdmin.getTenantId()).isEqualTo("tenant-88");
            assertThat(onlineAdmin.getLoginType()).isEqualTo("admin");
            assertThat(onlineAdmin.getLoginIp()).isEqualTo("203.0.113.88");

            // 若调用方显式传入 loginIp，保留显式值不覆盖
            LoginUser explicitUser = LoginUser.builder()
                    .userId("6002")
                    .tenantId("tenant-99")
                    .userAccount("user_explicit")
                    .userName("显式IP用户")
                    .build();
            LoginHelper.login(explicitUser, new SaLoginParameter().setTerminalExtra("loginIp", "10.0.0.99"));
            List<OnlineUser> defaultList = LoginHelper.searchOnlineUsers("login", null, 1, 10);
            assertThat(defaultList).hasSize(1);
            OnlineUser onlineDefault = defaultList.get(0);
            assertThat(onlineDefault.getTenantId()).isEqualTo("tenant-99");
            assertThat(onlineDefault.getLoginType()).isEqualTo("login");
            assertThat(onlineDefault.getLoginIp()).isEqualTo("10.0.0.99");

            // 复用调用方的 SaLoginParameter 模板时不原地污染其 terminalExtra("loginIp")
            SaLoginParameter reusableParam = new SaLoginParameter().setDeviceType("PAD");
            LoginUser reuseUser = LoginUser.builder()
                    .userId("6003")
                    .tenantId("tenant-77")
                    .userAccount("user_reuse")
                    .userName("复用参数用户")
                    .build();
            LoginHelper.login("admin", reuseUser, reusableParam);
            assertThat(reusableParam.getTerminalExtra("loginIp")).isNull();
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("7. OnlineUserQuery 多维度组合分页筛选（tenantId 精确 / CIDR IP / 设备 / 关键字与字段）")
    void testSearchOnlineUserPageWithQueryFilters() {
        LoginHelper.login(LoginUser.builder()
                        .userId("u-701").tenantId("tenant-a").userAccount("alice_a").userName("Alice").build(),
                new SaLoginParameter().setDeviceType("PC").setTerminalExtra("loginIp", "10.10.2.55"));
        LoginHelper.login(LoginUser.builder()
                        .userId("u-702").tenantId("tenant-a").userAccount("bob_a").userName("Bob").build(),
                new SaLoginParameter().setDeviceType("APP").setTerminalExtra("loginIp", "10.10.2.88"));
        LoginHelper.login(LoginUser.builder()
                        .userId("u-703").tenantId("tenant-b").userAccount("alice_b").userName("Alice B").build(),
                new SaLoginParameter().setDeviceType("PC").setTerminalExtra("loginIp", "10.10.2.55"));
        LoginHelper.login(LoginUser.builder()
                        .userId("u-704").tenantId("tenant-a").userAccount("carol_a").userName("Carol").build(),
                new SaLoginParameter().setDeviceType("PC").setTerminalExtra("loginIp", "192.168.9.10"));

        // 1. 按 tenantId 精确隔离，仅返回租户 A 三条会话
        PageResult<OnlineUser> tenantAResult = LoginHelper.searchOnlineUserPage(
                1, 10, OnlineUserQuery.builder().tenantId("tenant-a").build());
        assertThat(tenantAResult.getTotal()).isEqualTo(3);
        assertThat(tenantAResult.getRecords()).extracting(OnlineUser::getUserId)
                .containsExactlyInAnyOrder("u-701", "u-702", "u-704");

        // 2. tenantId + CIDR 子网 + 设备组合筛选
        PageResult<OnlineUser> cidrDeviceResult = LoginHelper.searchOnlineUserPage(
                "login",
                OnlineUserQuery.builder()
                        .tenantId("tenant-a")
                        .loginIp("10.10.2.0/24")
                        .loginDevice("PC")
                        .build(),
                1, 10);
        assertThat(cidrDeviceResult.getTotal()).isEqualTo(1);
        assertThat(cidrDeviceResult.getRecords().get(0).getUserId()).isEqualTo("u-701");

        // 3. 精确 IP 与 userAccount/keyword 筛选
        PageResult<OnlineUser> exactIpResult = LoginHelper.searchOnlineUserPage(
                1, 10, OnlineUserQuery.builder().loginIp("10.10.2.55").userAccount("alice").build());
        assertThat(exactIpResult.getTotal()).isEqualTo(2);

        PageResult<OnlineUser> exactUserResult = LoginHelper.searchOnlineUserPage(
                1, 10, OnlineUserQuery.builder().tenantId("tenant-a").userId("u-702").keyword("bob").build());
        assertThat(exactUserResult.getTotal()).isEqualTo(1);
        assertThat(exactUserResult.getRecords().get(0).getUserName()).isEqualTo("Bob");

        // 4. 空条件查询对象兼容常规无过滤分页
        PageResult<OnlineUser> allResult = LoginHelper.searchOnlineUserPage(1, 2, new OnlineUserQuery());
        assertThat(allResult.getTotal()).isEqualTo(4);
        assertThat(allResult.getRecords()).hasSize(2);
    }

    @Test
    @DisplayName("8. 可配置 maxScanLimit 突破旧 500 硬编码限制并支持单次查询 maxScanLimit 覆盖")
    void testConfigurableMaxScanLimitBeyondLegacy500() {
        try {
            assertThat(LoginHelper.getOnlineSessionMaxScanLimit()).isEqualTo(2000);
            for (int i = 1; i <= 520; i++) {
                LoginHelper.login(LoginUser.builder()
                        .userId("batch-" + i)
                        .tenantId(i <= 510 ? "tenant-bulk-1" : "tenant-bulk-target")
                        .userAccount("acct-" + i)
                        .userName("User " + i)
                        .build());
            }

            // 在默认 2000 上限下可检索到超过 500 索引位置的租户会话
            PageResult<OnlineUser> found = LoginHelper.searchOnlineUserPage(
                    1, 20, OnlineUserQuery.builder().tenantId("tenant-bulk-target").build());
            assertThat(found.getTotal()).isEqualTo(10);

            // 验证单次查询 maxScanLimit 生效及全局 Setter 生效
            PageResult<OnlineUser> cappedByQuery = LoginHelper.searchOnlineUserPage(
                    1, 20, OnlineUserQuery.builder().tenantId("tenant-bulk-1").maxScanLimit(5).build());
            assertThat(cappedByQuery.getTotal()).isLessThanOrEqualTo(5);

            LoginHelper.setOnlineSessionMaxScanLimit(10);
            assertThat(LoginHelper.getOnlineSessionMaxScanLimit()).isEqualTo(10);
            PageResult<OnlineUser> cannotExceedGlobalCeiling = LoginHelper.searchOnlineUserPage(
                    1, 20, OnlineUserQuery.builder().tenantId("tenant-bulk-1").maxScanLimit(999999).build());
            assertThat(cannotExceedGlobalCeiling.getTotal()).isLessThanOrEqualTo(10);
        } finally {
            LoginHelper.resetOnlineSessionConfig();
        }
    }

    @Test
    @DisplayName("9. 归属租户 Token 探测与跨租户踢出/注销 Fail-Fast 防越权守卫")
    void testTokenTenantOwnershipAndFailFastGuards() {
        LoginHelper.login("admin", LoginUser.builder()
                .userId("801").tenantId("tenant-a").userAccount("admin_a").userName("Admin A").build());
        String tokenA = LoginHelper.getTokenValue("admin");

        LoginHelper.login(LoginUser.builder()
                .userId("802").tenantId("tenant-b").userAccount("user_b").userName("User B").build());
        String tokenB = LoginHelper.getTokenValue();

        LoginHelper.login(LoginUser.builder()
                .userId("803").userAccount("no_tenant_user").userName("No Tenant").build());
        String noTenantToken = LoginHelper.getTokenValue();

        // 1. Token 归属查询
        OnlineUser infoA = LoginHelper.getOnlineUserByToken("admin", tokenA);
        assertThat(infoA).isNotNull();
        assertThat(infoA.getTenantId()).isEqualTo("tenant-a");
        assertThat(LoginHelper.getTokenTenantId("admin", tokenA)).isEqualTo("tenant-a");
        assertThat(LoginHelper.isTokenBelongsToTenant("admin", tokenA, "tenant-a")).isTrue();
        assertThat(LoginHelper.isTokenBelongsToTenant("admin", tokenA, "tenant-b")).isFalse();
        assertThat(LoginHelper.isTokenBelongsToTenant(tokenB, "tenant-b")).isTrue();

        // 2. 跨租户与无租户 Token 强退/注销必须立即抛出 403 BusinessException，且目标 Token 保持在线
        final String finalTokenA = tokenA;
        assertThatThrownBy(() -> LoginHelper.kickoutByTokenForTenant("admin", finalTokenA, "tenant-b"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);
        assertThat(LoginHelper.getOnlineUserByToken("admin", tokenA)).isNotNull();

        assertThatThrownBy(() -> LoginHelper.logoutByTokenValueForTenant(tokenB, "tenant-a"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);
        assertThat(LoginHelper.getOnlineUserByToken(tokenB)).isNotNull();

        assertThatThrownBy(() -> LoginHelper.kickoutByTokenForTenant(noTenantToken, "tenant-a"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 403);

        // 3. 归属租户匹配时成功踢出/注销并返回 true，再次操作幂等返回 false
        assertThat(LoginHelper.kickoutByTokenForTenant("admin", tokenA, "tenant-a")).isTrue();
        assertThat(LoginHelper.getOnlineUserByToken("admin", tokenA)).isNull();
        assertThat(LoginHelper.kickoutByTokenForTenant("admin", tokenA, "tenant-a")).isFalse();

        assertThat(LoginHelper.logoutByTokenValueForTenant(tokenB, "tenant-b")).isTrue();
        assertThat(LoginHelper.getOnlineUserByToken(tokenB)).isNull();
        assertThat(LoginHelper.logoutByTokenValueForTenant(tokenB, "tenant-b")).isFalse();
        assertThat(LoginHelper.logoutByTokenValueForTenant("non-existent-token", "tenant-b")).isFalse();
    }

    @Test
    @DisplayName("10. 极端分页参数防溢出与防回绕边界测试（#1180）")
    void testPaginationOffsetOverflowAndLongWraparound() {
        LoginHelper.login(LoginUser.builder()
                .userId("9001").tenantId("tenant-overflow").userAccount("overflow_1").userName("溢出测试1").build());
        LoginHelper.login(LoginUser.builder()
                .userId("9002").tenantId("tenant-overflow").userAccount("overflow_2").userName("溢出测试2").build());

        // 1. 无条件过滤分支：int 乘法溢出 (21474838 - 1) * 100 -> -2147483596
        PageResult<OnlineUser> intOverflowNoFilter = LoginHelper.searchOnlineUserPage(21474838, 100);
        assertThat(intOverflowNoFilter.getRecords()).isEmpty();
        assertThat(intOverflowNoFilter.getTotal()).isEqualTo(2L);
        assertThat(intOverflowNoFilter.getCurrent()).isEqualTo(21474838L);
        assertThat(intOverflowNoFilter.getSize()).isEqualTo(100L);
        assertThat(intOverflowNoFilter.getPages()).isEqualTo(1L);

        // 2. 条件过滤分支（OnlineUserQuery）：int 乘法溢出不抛 IndexOutOfBoundsException
        OnlineUserQuery query = OnlineUserQuery.builder().tenantId("tenant-overflow").build();
        PageResult<OnlineUser> intOverflowQuery = LoginHelper.searchOnlineUserPage(21474838, 100, query);
        assertThat(intOverflowQuery.getRecords()).isEmpty();
        assertThat(intOverflowQuery.getTotal()).isEqualTo(2L);
        assertThat(intOverflowQuery.getCurrent()).isEqualTo(21474838L);
        assertThat(intOverflowQuery.getSize()).isEqualTo(100L);
        assertThat(intOverflowQuery.getPages()).isEqualTo(1L);

        // 3. Integer.MAX_VALUE 极端组合
        PageResult<OnlineUser> maxIntPage = LoginHelper.searchOnlineUserPage(Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertThat(maxIntPage.getRecords()).isEmpty();
        assertThat(maxIntPage.getTotal()).isEqualTo(2L);
        assertThat(maxIntPage.getCurrent()).isEqualTo((long) Integer.MAX_VALUE);
        assertThat(maxIntPage.getSize()).isEqualTo(100L);
        assertThat(maxIntPage.getPages()).isEqualTo(1L);

        PageResult<OnlineUser> maxIntQuery = LoginHelper.searchOnlineUserPage(Integer.MAX_VALUE, Integer.MAX_VALUE, query);
        assertThat(maxIntQuery.getRecords()).isEmpty();
        assertThat(maxIntQuery.getTotal()).isEqualTo(2L);
        assertThat(maxIntQuery.getPages()).isEqualTo(1L);

        // 4. getOnlineUsers(long, long, keyword) 防止 (int) 强转回绕为第 1 页或负数
        PageResult<OnlineUser> wrapToOne = LoginHelper.getOnlineUsers(4294967297L, 10L, null);
        assertThat(wrapToOne.getRecords()).isEmpty();
        assertThat(wrapToOne.getTotal()).isEqualTo(2L);
        assertThat(wrapToOne.getCurrent()).isEqualTo(4294967297L);
        assertThat(wrapToOne.getSize()).isEqualTo(10L);
        assertThat(wrapToOne.getPages()).isEqualTo(1L);

        PageResult<OnlineUser> wrapNegative = LoginHelper.getOnlineUsers("login", (long) Integer.MAX_VALUE + 1L, 10L, "overflow");
        assertThat(wrapNegative.getRecords()).isEmpty();
        assertThat(wrapNegative.getTotal()).isEqualTo(2L);
        assertThat(wrapNegative.getCurrent()).isEqualTo((long) Integer.MAX_VALUE + 1L);
        assertThat(wrapNegative.getPages()).isEqualTo(1L);

        PageResult<OnlineUser> maxLong = LoginHelper.getOnlineUsers(Long.MAX_VALUE, Long.MAX_VALUE, null);
        assertThat(maxLong.getRecords()).isEmpty();
        assertThat(maxLong.getTotal()).isEqualTo(2L);
        assertThat(maxLong.getCurrent()).isEqualTo(Long.MAX_VALUE);
        assertThat(maxLong.getSize()).isEqualTo(100L);
        assertThat(maxLong.getPages()).isEqualTo(1L);
    }
}

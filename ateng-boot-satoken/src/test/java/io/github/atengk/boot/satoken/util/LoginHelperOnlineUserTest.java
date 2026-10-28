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
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.model.OnlineUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
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
}

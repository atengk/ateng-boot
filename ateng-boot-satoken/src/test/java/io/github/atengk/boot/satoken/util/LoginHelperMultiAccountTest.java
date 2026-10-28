package io.github.atengk.boot.satoken.util;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.context.model.SaCookie;
import cn.dev33.satoken.context.model.SaRequest;
import cn.dev33.satoken.context.model.SaResponse;
import cn.dev33.satoken.context.model.SaStorage;
import cn.dev33.satoken.context.model.SaTokenContextModelBox;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.satoken.interceptor.AtengSaTokenInterceptor;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.github.atengk.boot.satoken.provider.SaTokenCurrentTenantProvider;
import io.github.atengk.boot.satoken.provider.SaTokenCurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LoginHelper 多账号体系与 StpLogic 动态路由单元测试
 * 覆盖：
 * 1. 动态懒加载机制（getStpLogic 未注册自动实例化与缓存）
 * 2. 多账号共存（admin 与 member 在同一进程独立登录、Session/Token 隔离、互不顶替）
 * 3. 独立注销隔离（admin 注销不影响 member 活跃状态）
 * 4. primary-login-type 探测与 UserContextHolder / TenantContextHolder 映射
 * 5. 拦截器 AtengSaTokenInterceptor 对多账号 activeType 的识别与四维上下文同步
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("LoginHelper 多账号体系治理与 StpLogic 动态路由门面测试")
class LoginHelperMultiAccountTest {

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
        }).when(mockResponse).addCookie(org.mockito.ArgumentMatchers.any(SaCookie.class));

        org.mockito.Mockito.doAnswer(inv -> {
            String name = inv.getArgument(0);
            String value = inv.getArgument(1);
            int maxAge = inv.getArgument(4);
            if (name != null) {
                if (maxAge == 0) {
                    cookieMap.remove(name);
                } else {
                    cookieMap.put(name, value);
                }
            }
            return null;
        }).when(mockResponse).addCookie(anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());

        org.mockito.Mockito.doAnswer(inv -> {
            String name = inv.getArgument(0);
            if (name != null) {
                cookieMap.remove(name);
            }
            return null;
        }).when(mockResponse).deleteCookie(anyString());

        org.mockito.Mockito.doAnswer(inv -> {
            String name = inv.getArgument(0);
            if (name != null) {
                cookieMap.remove(name);
            }
            return null;
        }).when(mockResponse).deleteCookie(anyString(), anyString(), anyString());

        SaStorage storage = new SaStorage() {
            @Override
            public Object getSource() {
                return storageMap;
            }

            @Override
            public Object get(String key) {
                return storageMap.get(key);
            }

            @Override
            public SaStorage set(String key, Object value) {
                storageMap.put(key, value);
                return this;
            }

            @Override
            public SaStorage delete(String key) {
                storageMap.remove(key);
                return this;
            }
        };

        SaTokenContext context = new SaTokenContext() {
            @Override
            public void setContext(SaRequest req, SaResponse res, SaStorage sto) {
            }

            @Override
            public void clearContext() {
            }

            @Override
            public boolean isValid() {
                return true;
            }

            @Override
            public SaTokenContextModelBox getModelBox() {
                return new SaTokenContextModelBox(mockRequest, mockResponse, storage);
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
                return storage;
            }
        };

        SaManager.setSaTokenContext(context);
        LoginHelper.setPrimaryLoginType(StpUtil.TYPE);
        UserContextHolder.clear();
        TenantContextHolder.clear();
        UserContextHolder.setUserProvider(null);
        TenantContextHolder.setTenantProvider(null);
    }

    @AfterEach
    void tearDown() {
        try {
            LoginHelper.logout("admin");
        } catch (Exception ignored) {
        }
        try {
            LoginHelper.logout("member");
        } catch (Exception ignored) {
        }
        try {
            LoginHelper.logout();
        } catch (Exception ignored) {
        }

        SaManager.removeStpLogic("admin");
        SaManager.removeStpLogic("member");
        SaManager.removeStpLogic("custom_app");

        SaManager.setSaTokenContext(originalContext);
        LoginHelper.setPrimaryLoginType(StpUtil.TYPE);
        UserContextHolder.clear();
        TenantContextHolder.clear();
        UserContextHolder.setUserProvider(null);
        TenantContextHolder.setTenantProvider(null);
    }

    @Test
    @DisplayName("1. 动态懒加载机制：未注册的 loginType 自动实例化 StpLogic 并注册到 SaManager")
    void testDynamicLazyLoadingStpLogic() {
        assertThat(SaManager.stpLogicMap.containsKey("custom_app")).isFalse();

        StpLogic logic = LoginHelper.getStpLogic("custom_app");
        assertThat(logic).isNotNull();
        assertThat(logic.getLoginType()).isEqualTo("custom_app");
        assertThat(SaManager.stpLogicMap.containsKey("custom_app")).isTrue();

        // 再次获取返回同一实例
        StpLogic cachedLogic = LoginHelper.getStpLogic("custom_app");
        assertThat(cachedLogic).isSameAs(logic);

        // 默认类型或空字符串回退为 StpUtil.stpLogic
        assertThat(LoginHelper.getStpLogic(null)).isSameAs(StpUtil.stpLogic);
        assertThat(LoginHelper.getStpLogic("")).isSameAs(StpUtil.stpLogic);
        assertThat(LoginHelper.getStpLogic("   ")).isSameAs(StpUtil.stpLogic);
        assertThat(LoginHelper.getStpLogic(StpUtil.TYPE)).isSameAs(StpUtil.stpLogic);
    }

    @Test
    @DisplayName("2. 多账号共存与会话隔离：admin 与 member 同时登录，Token 独立、Session 隔离、权限正交")
    void testMultiAccountCoexistenceAndSessionIsolation() {
        LoginUser adminUser = LoginUser.builder()
                .userId("admin_1001")
                .tenantId("tenant_001")
                .userName("系统管理员")
                .roles(Set.of("admin"))
                .permissions(Set.of("system:user:query"))
                .build();

        LoginUser memberUser = LoginUser.builder()
                .userId("member_2002")
                .tenantId("tenant_002")
                .userName("普通会员")
                .roles(Set.of("member"))
                .permissions(Set.of("mall:order:create"))
                .build();

        // 1. 同时登录 admin 与 member 两个独立账号体系
        LoginHelper.login("admin", adminUser);
        LoginHelper.login("member", memberUser);

        // 2. 验证两端均处于活跃登录状态
        assertThat(LoginHelper.isLogin("admin")).isTrue();
        assertThat(LoginHelper.isLogin("member")).isTrue();

        // 3. 验证两端 Token 互相独立且非空
        String adminToken = LoginHelper.getTokenValue("admin");
        String memberToken = LoginHelper.getTokenValue("member");
        assertThat(adminToken).isNotBlank();
        assertThat(memberToken).isNotBlank();
        assertThat(adminToken).isNotEqualTo(memberToken);

        // 4. 验证用户主体信息完全隔离
        assertThat(LoginHelper.getUserId("admin")).isEqualTo("admin_1001");
        assertThat(LoginHelper.getUserId("member")).isEqualTo("member_2002");
        assertThat(LoginHelper.getTenantId("admin")).isEqualTo("tenant_001");
        assertThat(LoginHelper.getTenantId("member")).isEqualTo("tenant_002");
        assertThat(LoginHelper.getUserName("admin")).isEqualTo("系统管理员");
        assertThat(LoginHelper.getUserName("member")).isEqualTo("普通会员");

        // 5. 验证角色与权限正交隔离
        assertThat(LoginHelper.hasRole("admin", "admin")).isTrue();
        assertThat(LoginHelper.hasRole("admin", "member")).isFalse();
        assertThat(LoginHelper.hasRole("member", "member")).isTrue();
        assertThat(LoginHelper.hasRole("member", "admin")).isFalse();

        assertThat(LoginHelper.hasPermission("admin", "system:user:query")).isTrue();
        assertThat(LoginHelper.hasPermission("admin", "mall:order:create")).isFalse();
        assertThat(LoginHelper.hasPermission("member", "mall:order:create")).isTrue();
        assertThat(LoginHelper.hasPermission("member", "system:user:query")).isFalse();

        // 6. 验证 isSelf 与 checkSelf 支持 loginType
        assertThat(LoginHelper.isSelf("admin", "admin_1001")).isTrue();
        assertThat(LoginHelper.isSelf("admin", "member_2002")).isFalse();
        assertThat(LoginHelper.isSelf("member", "member_2002")).isTrue();
        assertThat(LoginHelper.isSelf("member", "admin_1001")).isFalse();

        assertThat(LoginHelper.isBelongsToTenant("admin", "tenant_001")).isTrue();
        assertThat(LoginHelper.isBelongsToTenant("admin", "tenant_002")).isFalse();
        assertThat(LoginHelper.isBelongsToTenant("member", "tenant_002")).isTrue();
        assertThat(LoginHelper.isBelongsToTenant("member", "tenant_001")).isFalse();
    }

    @Test
    @DisplayName("3. 独立注销与互不干扰：admin 注销下线后，member 仍保持正常活跃会话")
    void testIndependentLogoutIsolation() {
        LoginUser adminUser = LoginUser.builder().userId("admin_1").tenantId("tenant_1").build();
        LoginUser memberUser = LoginUser.builder().userId("member_2").tenantId("tenant_2").build();

        LoginHelper.login("admin", adminUser);
        LoginHelper.login("member", memberUser);

        assertThat(LoginHelper.isLogin("admin")).isTrue();
        assertThat(LoginHelper.isLogin("member")).isTrue();

        // 注销 admin
        LoginHelper.logout("admin");

        // admin 已注销，member 仍然处于登录态
        assertThat(LoginHelper.isLogin("admin")).isFalse();
        assertThat(LoginHelper.isLogin("member")).isTrue();
        assertThat(LoginHelper.getUserId("member")).isEqualTo("member_2");

        // 注销 member
        LoginHelper.logout("member");
        assertThat(LoginHelper.isLogin("member")).isFalse();
    }

    @Test
    @DisplayName("4. primary-login-type 探测与 SPI 提供者映射：自动识别配置的主账号体系")
    void testPrimaryLoginTypeResolutionInProviders() {
        SaTokenProperties properties = new SaTokenProperties();
        properties.setPrimaryLoginType("admin");

        SaTokenCurrentUserProvider userProvider = new SaTokenCurrentUserProvider(properties);
        SaTokenCurrentTenantProvider tenantProvider = new SaTokenCurrentTenantProvider(properties);

        UserContextHolder.setUserProvider(userProvider);
        TenantContextHolder.setTenantProvider(tenantProvider);

        LoginUser adminUser = LoginUser.builder().userId("admin_99").tenantId("tenant_admin").build();
        LoginUser memberUser = LoginUser.builder().userId("member_88").tenantId("tenant_member").build();

        // 场景 A: 仅 member 登录时，自动探测并映射 member
        LoginHelper.login("member", memberUser);
        assertThat(UserContextHolder.get()).isEqualTo("member_88");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant_member");

        // 场景 B: 同时登录 admin 与 member 时，优先映射 primaryLoginType (admin)
        LoginHelper.login("admin", adminUser);
        assertThat(UserContextHolder.get()).isEqualTo("admin_99");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant_admin");

        // 场景 C: admin 注销后，无缝降级回退至活跃的 member
        LoginHelper.logout("admin");
        assertThat(UserContextHolder.get()).isEqualTo("member_88");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant_member");
    }

    @Test
    @DisplayName("5. AtengSaTokenInterceptor 识别 primary-login-type 并同步四维上下文")
    void testInterceptorSyncsContextForPrimaryLoginType() throws Exception {
        SaTokenProperties properties = new SaTokenProperties();
        properties.setPrimaryLoginType("member");

        AtengSaTokenInterceptor interceptor = new AtengSaTokenInterceptor(properties);
        interceptor.isAnnotation(false);

        LoginUser memberUser = LoginUser.builder().userId("member_333").tenantId("tenant_333").build();
        LoginHelper.login("member", memberUser);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        boolean pass = interceptor.preHandle(request, response, new Object());
        assertThat(pass).isTrue();

        // 验证拦截器正确识别 member 账号并同步上下文
        assertThat(UserContextHolder.get()).isEqualTo("member_333");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant_333");

        // 验证 afterCompletion 严格清理现场
        interceptor.afterCompletion(request, response, new Object(), null);
        assertThat(UserContextHolder.get()).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }
}

package io.github.atengk.boot.satoken.cache;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.satoken.cluster.broadcaster.SaTokenClusterBroadcaster;
import io.github.atengk.boot.satoken.cluster.enums.SaTokenClusterEventType;
import io.github.atengk.boot.satoken.cluster.listener.SaTokenClusterSyncListener;
import io.github.atengk.boot.satoken.cluster.model.SaTokenClusterMessage;
import io.github.atengk.boot.satoken.constant.SaTokenConstant;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.github.atengk.boot.satoken.util.LoginHelper;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多租户维度角色权限缓存键隔离、定向失效与集群广播同步单元测试 (#1093)
 *
 * @author Ateng
 * @since 2026-11-19
 */
@DisplayName("CachedStpInterface & PermissionCacheService 租户维度角色权限隔离单元测试")
class TenantScopedRolePermissionCacheTest {

    private PermissionCacheService cacheService;
    private SaTokenProperties properties;
    private UserRolePermissionLoader loader;
    private CachedStpInterface cachedStpInterface;

    @BeforeEach
    void setUp() {
        TenantContextHolder.clear();
        TenantContextHolder.setTenantProvider(null);
        properties = new SaTokenProperties();
        properties.getPermissionCache().setTimeout(java.time.Duration.ofSeconds(300));
        properties.getPermissionCache().setEmptyTtl(java.time.Duration.ofSeconds(60));
        properties.getPermissionCache().setTenantScoped(true);
        cacheService = new PermissionCacheService();
        loader = mock(UserRolePermissionLoader.class);
        cachedStpInterface = new CachedStpInterface(loader, cacheService, properties);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        TenantContextHolder.setTenantProvider(null);
    }

    @Test
    @DisplayName("1. 开启 tenant-scoped=true：不同租户同名 roleCode 缓存键物理隔离，互不串味")
    void shouldIsolateSameRoleCodeAcrossTenantsWhenTenantScopedEnabled() {
        when(loader.loadPermissionsByRole("manager", "admin", "tenant-a"))
                .thenReturn(List.of("tenantA:order:approve"));
        when(loader.loadPermissionsByRole("manager", "admin", "tenant-b"))
                .thenReturn(List.of("tenantB:report:view"));

        // 租户 A 加载 manager 权限
        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class)) {
            helperMock.when(() -> LoginHelper.getTenantId("admin")).thenReturn("tenant-a");
            assertThat(cachedStpInterface.getPermissionsByRole("manager", "admin"))
                    .containsExactly("tenantA:order:approve");
        }

        // 租户 B 加载同名 manager 权限，必须命中独立键而不串读租户 A
        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class)) {
            helperMock.when(() -> LoginHelper.getTenantId("admin")).thenReturn("tenant-b");
            assertThat(cachedStpInterface.getPermissionsByRole("manager", "admin"))
                    .containsExactly("tenantB:report:view");
        }

        // 定向清理租户 A 角色缓存，仅失效 tenant-a，不影响 tenant-b
        String keyA = PermissionCacheService.buildRolePermsKey("manager", "admin", "tenant-a");
        String keyB = PermissionCacheService.buildRolePermsKey("manager", "admin", "tenant-b");
        assertThat(keyA).isEqualTo("ateng:satoken:cache:admin:role-perms:tenant-a:manager");
        assertThat(keyB).isEqualTo("ateng:satoken:cache:admin:role-perms:tenant-b:manager");
        assertThat(cacheService.get(keyA)).isNotNull();
        assertThat(cacheService.get(keyB)).isNotNull();

        cacheService.clearRoleCache("admin", "tenant-a", "manager");
        assertThat(cacheService.get(keyA)).isNull();
        assertThat(cacheService.get(keyB)).isNotNull();
    }

    @Test
    @DisplayName("2. 租户解析优先级：优先会话所属租户防超管切租户串缓存，会话无租户时回退 TenantContextHolder")
    void shouldPreferSessionTenantOverSwitchedTenantContextAndFallbackToContextHolder() {
        when(loader.loadPermissionsByRole("platform_ops", "admin", "0"))
                .thenReturn(List.of("platform:ops:all"));
        when(loader.loadPermissionsByRole("guest_role", "login", "tenant-ctx"))
                .thenReturn(List.of("guest:read"));

        // 2.1 超管会话所属租户为 "0"，但 TenantContextHolder 临时切换到了 "tenant-biz"：
        //     角色缓存键必须优先绑定会话所属租户 "0"，防止超管权限串入业务租户缓存
        TenantContextHolder.setTenantId("tenant-biz");
        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class)) {
            helperMock.when(() -> LoginHelper.getTenantId("admin")).thenReturn("0");
            assertThat(cachedStpInterface.getPermissionsByRole("platform_ops", "admin"))
                    .containsExactly("platform:ops:all");
        }
        assertThat(cacheService.get(PermissionCacheService.buildRolePermsKey("platform_ops", "admin", "0"))).isNotNull();
        assertThat(cacheService.get(PermissionCacheService.buildRolePermsKey("platform_ops", "admin", "tenant-biz"))).isNull();

        // 2.2 会话无租户时回退 TenantContextHolder
        TenantContextHolder.setTenantId("tenant-ctx");
        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class)) {
            helperMock.when(() -> LoginHelper.getTenantId("login")).thenReturn(null);
            assertThat(cachedStpInterface.getPermissionsByRole("guest_role", "login"))
                    .containsExactly("guest:read");
        }
        assertThat(cacheService.get(PermissionCacheService.buildRolePermsKey("guest_role", "login", "tenant-ctx"))).isNotNull();
    }

    @Test
    @DisplayName("3. 集群广播与异地监听透传 tenantId，精准失效对应租户本地角色缓存")
    void shouldPropagateTenantIdInClusterBroadcastAndEvictTargetTenantRoleCacheOnly() {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);

        SaTokenProperties syncProps = new SaTokenProperties();
        syncProps.getClusterSync().setEnabled(true);
        syncProps.getClusterSync().setChannel("ateng:satoken:cluster-sync");

        SaTokenClusterBroadcaster senderBroadcaster = new SaTokenClusterBroadcaster("node-a", redisProvider, syncProps);
        @SuppressWarnings("unchecked")
        ObjectProvider<SaTokenClusterBroadcaster> broadcasterProvider = mock(ObjectProvider.class);
        when(broadcasterProvider.getIfAvailable()).thenReturn(senderBroadcaster);
        org.mockito.Mockito.doAnswer(inv -> {
            java.util.function.Consumer<SaTokenClusterBroadcaster> c = inv.getArgument(0);
            c.accept(senderBroadcaster);
            return null;
        }).when(broadcasterProvider).ifAvailable(org.mockito.ArgumentMatchers.any());

        PermissionCacheService senderCacheService = new PermissionCacheService(null, null, broadcasterProvider);
        senderCacheService.refreshRolePermissions("admin", "tenant-a", "manager");

        // 验证发出的集群消息携带 loginType="admin", tenantId="tenant-a", targetKey="manager"
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(eq("ateng:satoken:cluster-sync"), payloadCaptor.capture());
        SaTokenClusterMessage sent = JsonUtil.parseObject(payloadCaptor.getValue(), SaTokenClusterMessage.class);
        assertThat(sent).isNotNull();
        assertThat(sent.getEventType()).isEqualTo(SaTokenClusterEventType.ROLE_PERMISSION_REFRESH);
        assertThat(sent.getLoginType()).isEqualTo("admin");
        assertThat(sent.getTenantId()).isEqualTo("tenant-a");
        assertThat(sent.getTargetKey()).isEqualTo("manager");

        // 异地节点（node-b）同时缓存了 tenant-a 与 tenant-b 的 manager 权限
        PermissionCacheService remoteCacheService = new PermissionCacheService();
        String keyA = PermissionCacheService.buildRolePermsKey("manager", "admin", "tenant-a");
        String keyB = PermissionCacheService.buildRolePermsKey("manager", "admin", "tenant-b");
        remoteCacheService.set(keyA, "[\"a:perm\"]", 300);
        remoteCacheService.set(keyB, "[\"b:perm\"]", 300);

        SaTokenClusterBroadcaster remoteBroadcaster = new SaTokenClusterBroadcaster("node-b", null, syncProps);
        SaTokenClusterSyncListener remoteListener = new SaTokenClusterSyncListener(remoteBroadcaster, remoteCacheService);
        remoteListener.onClusterMessage(payloadCaptor.getValue());

        // 异地节点精准驱逐 tenant-a，保留 tenant-b
        assertThat(remoteCacheService.get(keyA)).isNull();
        assertThat(remoteCacheService.get(keyB)).isEqualTo("[\"b:perm\"]");

        // 默认三参 loader 委托两参/单参向后兼容验证
        UserRolePermissionLoader legacyLoader = new UserRolePermissionLoader() {
            @Override
            public List<String> loadRolesByUserId(String userId) {
                return List.of();
            }

            @Override
            public List<String> loadPermissionsByRole(String roleCode) {
                return List.of("legacy:" + roleCode);
            }
        };
        assertThat(legacyLoader.loadPermissionsByRole("editor", "admin", "tenant-a"))
                .containsExactly("legacy:editor");
    }

    @Test
    @DisplayName("4. 默认 tenant-scoped=false 时保持原 role-perms 键格式 100% 向后兼容")
    void shouldPreserveLegacyKeyFormatWhenTenantScopedDisabled() {
        SaTokenProperties defaults = new SaTokenProperties();
        assertThat(defaults.getPermissionCache().isTenantScoped()).isFalse();

        TenantContextHolder.setTenantId("tenant-a");
        CachedStpInterface legacyStp = new CachedStpInterface(loader, cacheService, defaults);
        when(loader.loadPermissionsByRole("editor", "admin")).thenReturn(List.of("doc:edit"));

        assertThat(legacyStp.getPermissionsByRole("editor", "admin")).containsExactly("doc:edit");
        assertThat(cacheService.get(PermissionCacheService.buildRolePermsKey("editor", "admin"))).isNotNull();
        assertThat(cacheService.get(PermissionCacheService.buildRolePermsKey("editor", "admin", "tenant-a"))).isNull();
    }

    @Test
    @DisplayName("5. 超管切租户下触发复合 loginId 权限回源：全程锁定主租户 0 并隔离两租户同名用户缓存")
    void shouldPinOwnerTenantForSuperAdminSwitchedTenantAndIsolateSameUserIdAcrossTenants() {
        SaSession superSession = mock(SaSession.class);
        LoginUser superUser = LoginUser.builder().tenantId("0").userId("1").build();
        when(superSession.get(SaTokenConstant.LOGIN_USER_KEY)).thenReturn(superUser);

        SaSession tenantBSession = mock(SaSession.class);
        LoginUser bUser = LoginUser.builder().tenantId("tenant-b").userId("1").build();
        when(tenantBSession.get(SaTokenConstant.LOGIN_USER_KEY)).thenReturn(bUser);

        TenantContextHolder.setTenantId("tenant-biz");
        try (MockedStatic<StpUtil> stpMock = mockStatic(StpUtil.class)) {
            stpMock.when(() -> StpUtil.getSessionByLoginId("0_1", false)).thenReturn(superSession);
            stpMock.when(() -> StpUtil.getSessionByLoginId("tenant-b_1", false)).thenReturn(tenantBSession);

            when(loader.loadRolesByUserId("1", "login", "0")).thenAnswer(inv -> {
                assertThat(TenantContextHolder.getExplicit()).isEqualTo("0");
                return List.of("platform_admin");
            });
            when(loader.loadPermissionsByRole("platform_admin", "login", "0")).thenAnswer(inv -> {
                assertThat(TenantContextHolder.getExplicit()).isEqualTo("0");
                return List.of("platform:all");
            });
            when(loader.loadDirectPermissionsByUserId("1", "login", "0")).thenAnswer(inv -> {
                assertThat(TenantContextHolder.getExplicit()).isEqualTo("0");
                return List.of("platform:root");
            });

            when(loader.loadRolesByUserId("1", "login", "tenant-b")).thenReturn(List.of("biz_viewer"));
            when(loader.loadPermissionsByRole("biz_viewer", "login", "tenant-b")).thenReturn(List.of("biz:read"));
            when(loader.loadDirectPermissionsByUserId("1", "login", "tenant-b")).thenReturn(List.of());

            List<String> superPerms = cachedStpInterface.getPermissionList("0_1", "login");
            assertThat(superPerms).containsExactlyInAnyOrder("platform:all", "platform:root");
            assertThat(TenantContextHolder.getExplicit()).isEqualTo("tenant-biz");

            // 相同 userId="1" 在 tenant-b 下必须读取自己角色权限，严禁串用超管缓存
            List<String> bPerms = cachedStpInterface.getPermissionList("tenant-b_1", "login");
            assertThat(bPerms).containsExactly("biz:read");

            String keySuper = PermissionCacheService.buildUserRolesKey("1", "login", "0");
            String keyTenantB = PermissionCacheService.buildUserRolesKey("1", "login", "tenant-b");
            assertThat(cacheService.get(keySuper)).isEqualTo("[\"platform_admin\"]");
            assertThat(cacheService.get(keyTenantB)).isEqualTo("[\"biz_viewer\"]");
        }
    }
}

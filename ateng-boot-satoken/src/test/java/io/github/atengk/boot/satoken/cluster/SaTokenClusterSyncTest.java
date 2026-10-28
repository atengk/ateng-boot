package io.github.atengk.boot.satoken.cluster;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.satoken.cache.PermissionCacheService;
import io.github.atengk.boot.satoken.cluster.broadcaster.SaTokenClusterBroadcaster;
import io.github.atengk.boot.satoken.cluster.enums.SaTokenClusterEventType;
import io.github.atengk.boot.satoken.cluster.listener.SaTokenClusterSyncListener;
import io.github.atengk.boot.satoken.cluster.model.SaTokenClusterMessage;
import io.github.atengk.boot.satoken.event.*;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Sa-Token 分布式集群即时同步与广播作废单元测试
 * 覆盖消息序列化、Pub/Sub 广播发送、静默降级、自消费防卫过滤、异地消息精准失效与 Spring 安全事件联动
 *
 * @author Ateng
 * @since 2026-10-20
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SaTokenClusterSync 单元测试")
class SaTokenClusterSyncTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    @Mock
    private PermissionCacheService cacheService;

    private SaTokenProperties properties;
    private SaTokenClusterBroadcaster broadcaster;
    private SaTokenClusterSyncListener listener;
    private io.github.atengk.boot.satoken.cluster.listener.SaTokenClusterRedisMessageSubscriber subscriber;

    private final String localInstanceId = "node-alpha-1234";

    @BeforeEach
    void setUp() {
        properties = new SaTokenProperties();
        properties.getClusterSync().setEnabled(true);
        properties.getClusterSync().setChannel("ateng:satoken:cluster-sync");

        broadcaster = new SaTokenClusterBroadcaster(localInstanceId, redisTemplateProvider, properties);
        listener = new SaTokenClusterSyncListener(broadcaster, cacheService);
        subscriber = new io.github.atengk.boot.satoken.cluster.listener.SaTokenClusterRedisMessageSubscriber(listener);
    }

    @Test
    @DisplayName("SaTokenClusterMessage: 序列化与反序列化对称性验证")
    void testMessageSerialization() {
        SaTokenClusterMessage msg = SaTokenClusterMessage.of("node-1", SaTokenClusterEventType.USER_PERMISSION_REFRESH, "user_100");
        msg.getExtra().put("operator", "admin");

        String json = JsonUtil.toJsonString(msg);
        assertThat(json).isNotBlank();

        SaTokenClusterMessage deserialized = JsonUtil.parseObject(json, SaTokenClusterMessage.class);
        assertThat(deserialized).isNotNull();
        assertThat(deserialized.getInstanceId()).isEqualTo("node-1");
        assertThat(deserialized.getEventType()).isEqualTo(SaTokenClusterEventType.USER_PERMISSION_REFRESH);
        assertThat(deserialized.getTargetKey()).isEqualTo("user_100");
        assertThat(deserialized.getExtra()).containsEntry("operator", "admin");
    }

    @Test
    @DisplayName("broadcast: 正常发送集群广播消息")
    void testBroadcastSuccess() {
        when(redisTemplateProvider.getIfAvailable()).thenReturn(stringRedisTemplate);

        broadcaster.broadcast(SaTokenClusterEventType.USER_PERMISSION_REFRESH, "user_888", Map.of("reason", "auth-grant"));

        ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate).convertAndSend(channelCaptor.capture(), payloadCaptor.capture());

        assertThat(channelCaptor.getValue()).isEqualTo("ateng:satoken:cluster-sync");
        SaTokenClusterMessage sentMessage = JsonUtil.parseObject(payloadCaptor.getValue(), SaTokenClusterMessage.class);
        assertThat(sentMessage).isNotNull();
        assertThat(sentMessage.getInstanceId()).isEqualTo(localInstanceId);
        assertThat(sentMessage.getEventType()).isEqualTo(SaTokenClusterEventType.USER_PERMISSION_REFRESH);
        assertThat(sentMessage.getTargetKey()).isEqualTo("user_888");
        assertThat(sentMessage.getExtra()).containsEntry("reason", "auth-grant");
    }

    @Test
    @DisplayName("broadcast: 无 Redis 环境时自动静默降级不抛异常")
    void testBroadcastSilentDegradeWhenNoRedis() {
        when(redisTemplateProvider.getIfAvailable()).thenReturn(null);

        assertThatCode(() -> broadcaster.broadcast(SaTokenClusterEventType.LOGOUT, "user_999"))
                .doesNotThrowAnyException();

        verify(stringRedisTemplate, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    @DisplayName("broadcast: 配置禁用时跳过广播")
    void testBroadcastDisabled() {
        properties.getClusterSync().setEnabled(false);

        broadcaster.broadcast(SaTokenClusterEventType.ALL_CACHE_CLEAR, null);

        verify(stringRedisTemplate, never()).convertAndSend(anyString(), anyString());
        verify(redisTemplateProvider, never()).getIfAvailable();
    }

    @Test
    @DisplayName("broadcast: Redis 异常时内部隔离捕获，不阻断主链路")
    void testBroadcastExceptionIsolation() {
        when(redisTemplateProvider.getIfAvailable()).thenReturn(stringRedisTemplate);
        doThrow(new RuntimeException("Redis connection timeout"))
                .when(stringRedisTemplate).convertAndSend(anyString(), anyString());

        assertThatCode(() -> broadcaster.broadcast(SaTokenClusterEventType.USER_PERMISSION_REFRESH, "user_1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("onMessage: 本机发送的消息执行自消费丢弃 (Self-Consumption Guard)")
    void testSelfConsumptionGuard() {
        SaTokenClusterMessage selfMsg = SaTokenClusterMessage.of(localInstanceId, SaTokenClusterEventType.USER_PERMISSION_REFRESH, "user_123");
        String json = JsonUtil.toJsonString(selfMsg);
        Message message = new DefaultMessage("ateng:satoken:cluster-sync".getBytes(StandardCharsets.UTF_8), json.getBytes(StandardCharsets.UTF_8));

        subscriber.onMessage(message, null);

        // 验证由于 instanceId 相同，任何本地清理方法均未触发
        verify(cacheService, never()).clearLocalUserCache(anyString());
        verify(cacheService, never()).clearLocalRoleCache(anyString());
        verify(cacheService, never()).clearLocalAllCache();
    }

    @Test
    @DisplayName("onMessage: 异地节点消息精准触发本地用户权限缓存清理")
    void testRemoteUserPermissionRefresh() {
        SaTokenClusterMessage remoteMsg = SaTokenClusterMessage.of("remote-node-9999", SaTokenClusterEventType.USER_PERMISSION_REFRESH, "user_555");
        String json = JsonUtil.toJsonString(remoteMsg);
        Message message = new DefaultMessage("ateng:satoken:cluster-sync".getBytes(StandardCharsets.UTF_8), json.getBytes(StandardCharsets.UTF_8));

        subscriber.onMessage(message, null);

        verify(cacheService).clearLocalUserCache("user_555");
        verify(cacheService, never()).clearLocalRoleCache(anyString());
        verify(cacheService, never()).clearLocalAllCache();
    }

    @Test
    @DisplayName("onMessage: 异地节点消息精准触发本地角色权限缓存清理")
    void testRemoteRolePermissionRefresh() {
        SaTokenClusterMessage remoteMsg = SaTokenClusterMessage.of("remote-node-9999", SaTokenClusterEventType.ROLE_PERMISSION_REFRESH, "role_finance");
        String json = JsonUtil.toJsonString(remoteMsg);
        Message message = new DefaultMessage("ateng:satoken:cluster-sync".getBytes(StandardCharsets.UTF_8), json.getBytes(StandardCharsets.UTF_8));

        subscriber.onMessage(message, null);

        verify(cacheService).clearLocalRoleCache("role_finance");
        verify(cacheService, never()).clearLocalUserCache(anyString());
    }

    @Test
    @DisplayName("onMessage: 异地全量清空消息触发本地全部缓存清理")
    void testRemoteAllCacheClear() {
        SaTokenClusterMessage remoteMsg = SaTokenClusterMessage.of("remote-node-9999", SaTokenClusterEventType.ALL_CACHE_CLEAR, null);
        String json = JsonUtil.toJsonString(remoteMsg);
        Message message = new DefaultMessage("ateng:satoken:cluster-sync".getBytes(StandardCharsets.UTF_8), json.getBytes(StandardCharsets.UTF_8));

        subscriber.onMessage(message, null);

        verify(cacheService).clearLocalAllCache();
    }

    @Test
    @DisplayName("onMessage: 异地下线/踢人/封禁/解封消息触发目标用户本地缓存清理")
    void testRemoteLifecycleEvents() {
        String[] types = {
                SaTokenClusterEventType.LOGOUT.name(),
                SaTokenClusterEventType.KICKOUT.name(),
                SaTokenClusterEventType.REPLACED.name(),
                SaTokenClusterEventType.DISABLE.name(),
                SaTokenClusterEventType.UNTIE_DISABLE.name()
        };

        for (String type : types) {
            SaTokenClusterMessage msg = SaTokenClusterMessage.of("remote-node-9999", SaTokenClusterEventType.valueOf(type), "user_666");
            Message message = new DefaultMessage("ateng:satoken:cluster-sync".getBytes(StandardCharsets.UTF_8), JsonUtil.toJsonString(msg).getBytes(StandardCharsets.UTF_8));
            subscriber.onMessage(message, null);
        }

        verify(cacheService, times(5)).clearLocalUserCache("user_666");
    }

    @Test
    @DisplayName("Spring 安全生命周期事件触发集群广播联动")
    void testSpringSecurityEventsTriggerBroadcast() {
        when(redisTemplateProvider.getIfAvailable()).thenReturn(stringRedisTemplate);

        // 1. 注销事件
        listener.onLogout(new SaLogoutEvent(this, "login", "user_10", "token_a"));
        // 2. 踢人事件
        listener.onKickout(new SaKickoutEvent(this, "login", "user_20", "token_b"));
        // 3. 顶替事件
        listener.onReplaced(new SaReplacedEvent(this, "login", "user_30", "token_c"));
        // 4. 封禁事件
        listener.onDisable(new SaDisableEvent(this, "login", "user_40", "login_service", 1, 3600L));
        // 5. 解封事件
        listener.onUntieDisable(new SaUntieDisableEvent(this, "login", "user_50", "login_service"));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate, times(5)).convertAndSend(eq("ateng:satoken:cluster-sync"), payloadCaptor.capture());

        assertThat(payloadCaptor.getAllValues()).hasSize(5);
        assertThat(payloadCaptor.getAllValues().get(0)).contains("LOGOUT").contains("user_10");
        assertThat(payloadCaptor.getAllValues().get(1)).contains("KICKOUT").contains("user_20");
        assertThat(payloadCaptor.getAllValues().get(2)).contains("REPLACED").contains("user_30");
        assertThat(payloadCaptor.getAllValues().get(3)).contains("DISABLE").contains("user_40");
        assertThat(payloadCaptor.getAllValues().get(4)).contains("UNTIE_DISABLE").contains("user_50");
    }
}

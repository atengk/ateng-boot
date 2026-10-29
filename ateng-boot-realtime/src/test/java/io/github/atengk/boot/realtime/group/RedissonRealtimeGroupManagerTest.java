package io.github.atengk.boot.realtime.group;

import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Redisson 分布式双向 Set 群组管理器单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("RedissonRealtimeGroupManager 分布式群组治理测试")
class RedissonRealtimeGroupManagerTest {

    private RedissonClient redissonClient;
    private RealtimeProperties properties;
    private RedissonRealtimeGroupManager groupManager;

    @SuppressWarnings("unchecked")
    private RSet<String> mockGroupSet = mock(RSet.class);
    @SuppressWarnings("unchecked")
    private RSet<String> mockSessionGroupsSet = mock(RSet.class);

    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        properties = new RealtimeProperties();

        when(redissonClient.<String>getSet(startsWith("ateng:realtime:cluster:group:"), any(StringCodec.class)))
                .thenReturn(mockGroupSet);
        when(redissonClient.<String>getSet(startsWith("ateng:realtime:cluster:session:"), any(StringCodec.class)))
                .thenReturn(mockSessionGroupsSet);

        groupManager = new RedissonRealtimeGroupManager(redissonClient, properties);
    }

    @Test
    @DisplayName("加入群组: 双向维护本地 Map 与 Redis Set 索引")
    void testJoinGroup() {
        SessionKey key = SessionKey.of("user-01", "pc", "tab-1");
        String groupId = "room-vip";

        boolean added = groupManager.joinGroup(groupId, key);
        assertThat(added).isTrue();

        // 验证本地内存缓存
        assertThat(groupManager.getGroupMemberCount(groupId)).isEqualTo(1);
        assertThat(groupManager.getGroupSessions(groupId)).containsExactly(key);

        // 验证 Redis 双向索引
        verify(redissonClient).getSet(eq("ateng:realtime:cluster:group:room-vip"), any(StringCodec.class));
        verify(mockGroupSet).add(key.format());

        verify(redissonClient).getSet(eq("ateng:realtime:cluster:session:" + key.format() + ":groups"), any(StringCodec.class));
        verify(mockSessionGroupsSet).add("room-vip");

        // 重复加入返回 false，且 Redis add 依然幂等调用
        boolean duplicate = groupManager.joinGroup(groupId, key);
        assertThat(duplicate).isFalse();
    }

    @Test
    @DisplayName("退出群组: 同步清理本地缓存与 Redis 双向 Set 成员")
    void testLeaveGroup() {
        SessionKey key = SessionKey.of("user-01", "pc", "tab-1");
        String groupId = "room-vip";

        groupManager.joinGroup(groupId, key);

        boolean removed = groupManager.leaveGroup(groupId, key);
        assertThat(removed).isTrue();

        assertThat(groupManager.getGroupMemberCount(groupId)).isZero();
        verify(mockGroupSet).remove(key.format());
        verify(mockSessionGroupsSet).remove("room-vip");
    }

    @Test
    @DisplayName("排空全量群组: 会话关闭时级联清空名下所有关联的 Redis 正反向索引")
    void testLeaveAllGroups() {
        SessionKey key = SessionKey.of("user-01", "pc", "tab-1");
        groupManager.joinGroup("room-A", key);
        groupManager.joinGroup("room-B", key);

        when(mockSessionGroupsSet.readAll()).thenReturn(Set.of("room-A", "room-B"));

        Set<String> left = groupManager.leaveAllGroups(key);
        assertThat(left).containsExactlyInAnyOrder("room-A", "room-B");

        assertThat(groupManager.getSessionGroups(key)).isEmpty();
        verify(mockGroupSet, times(2)).remove(key.format());
        verify(mockSessionGroupsSet).delete();
    }

    @Test
    @DisplayName("全集群在线人数感知: getClusterGroupMemberCount 检索 Redis Set 大小")
    void testGetClusterGroupMemberCount() {
        when(mockGroupSet.size()).thenReturn(42);

        int count = groupManager.getClusterGroupMemberCount("room-global");
        assertThat(count).isEqualTo(42);

        // Redis 异常时平滑降级为本地单机统计
        when(mockGroupSet.size()).thenThrow(new RuntimeException("Redis connection timeout"));
        int fallbackCount = groupManager.getClusterGroupMemberCount("room-global");
        assertThat(fallbackCount).isZero();
    }

    @Test
    @DisplayName("全集群成员坐标感知: getClusterGroupMembers 检索并反序列化 SessionKey")
    void testGetClusterGroupMembers() {
        SessionKey k1 = SessionKey.of("u1", "pc", "c1");
        SessionKey k2 = SessionKey.of("u2", "mobile", "c2");

        when(mockGroupSet.readAll()).thenReturn(Set.of(k1.format(), k2.format()));

        Set<SessionKey> clusterMembers = groupManager.getClusterGroupMembers("room-cluster");
        assertThat(clusterMembers).containsExactlyInAnyOrder(k1, k2);

        // Redis 异常时平滑降级为本地集合
        when(mockGroupSet.readAll()).thenThrow(new RuntimeException("Redis error"));
        Set<SessionKey> fallback = groupManager.getClusterGroupMembers("room-cluster");
        assertThat(fallback).isEmpty();
    }

    @Test
    @DisplayName("房间解散: dismissGroup 移出本地成员、清除 Redis 正向 Set 并反向剔除")
    void testDismissGroup() {
        SessionKey localKey = SessionKey.of("local-user", "pc", "c1");
        groupManager.joinGroup("room-dismiss", localKey);

        SessionKey remoteKey = SessionKey.of("remote-user", "web", "c2");
        when(mockGroupSet.readAll()).thenReturn(Set.of(localKey.format(), remoteKey.format()));

        Set<SessionKey> localMembers = groupManager.dismissGroup("room-dismiss");
        assertThat(localMembers).containsExactly(localKey);
        assertThat(groupManager.getGroupMemberCount("room-dismiss")).isZero();

        // 验证 Redis 删除群组 Set 与反向索引剔除
        verify(mockGroupSet).delete();
        verify(mockSessionGroupsSet, atLeastOnce()).remove("room-dismiss");
    }

    @Test
    @DisplayName("会话生命周期联动: onSessionClosed 自动触发全量群组排空")
    void testOnSessionClosed() {
        SessionKey key = SessionKey.of("u-close", "c-close");
        groupManager.joinGroup("room-closed", key);

        WebSocketSession ws = mock(WebSocketSession.class);
        RealtimeSession session = RealtimeSession.ofWebSocket(key, ws);

        groupManager.onSessionClosed(session);

        assertThat(groupManager.getSessionGroups(key)).isEmpty();
        verify(mockSessionGroupsSet).delete();
    }

    @Test
    @DisplayName("健壮性与空入参防卫")
    void testDefensiveHandling() {
        assertThat(groupManager.joinGroup(null, null)).isFalse();
        assertThat(groupManager.joinGroup("   ", null)).isFalse();
        assertThat(groupManager.leaveGroup(null, null)).isFalse();
        assertThat(groupManager.leaveAllGroups(null)).isEmpty();
        assertThat(groupManager.getClusterGroupMemberCount(null)).isZero();
        assertThat(groupManager.getClusterGroupMembers(null)).isEmpty();
        assertThat(groupManager.dismissGroup(null)).isEmpty();
    }
}

package io.github.atengk.boot.realtime.group;

import io.github.atengk.boot.realtime.constant.RealtimeCloseStatus;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 实时分组与频道订阅管理单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("RealtimeGroupManager 分组订阅管理测试")
class RealtimeGroupManagerTest {

    @Test
    @DisplayName("加入与退出分组、查询群组成员与成员计数")
    void testJoinAndLeaveGroup() {
        RealtimeGroupManager manager = new RealtimeGroupManager();

        SessionKey k1 = SessionKey.of("user-1", "tab1");
        SessionKey k2 = SessionKey.of("user-2", "tab1");

        // 加入房间
        assertThat(manager.joinGroup("room-chat", k1)).isTrue();
        assertThat(manager.joinGroup("room-chat", k2)).isTrue();
        // 重复加入返回 false
        assertThat(manager.joinGroup("room-chat", k1)).isFalse();

        assertThat(manager.getGroupMemberCount("room-chat")).isEqualTo(2);
        assertThat(manager.getGroupSessions("room-chat")).containsExactlyInAnyOrder(k1, k2);

        // 会话退出房间
        assertThat(manager.leaveGroup("room-chat", k1)).isTrue();
        assertThat(manager.getGroupMemberCount("room-chat")).isEqualTo(1);
        assertThat(manager.getGroupSessions("room-chat")).containsExactly(k2);

        // 空群组自动排空
        assertThat(manager.leaveGroup("room-chat", k2)).isTrue();
        assertThat(manager.getGroupMemberCount("room-chat")).isEqualTo(0);
        assertThat(manager.getAllGroupIds()).isEmpty();
    }

    @Test
    @DisplayName("单个会话跨多个分组及全量退出")
    void testMultipleGroupsAndLeaveAll() {
        RealtimeGroupManager manager = new RealtimeGroupManager();

        SessionKey key = SessionKey.of("user-multigroup", "c1");

        manager.joinGroup("group-A", key);
        manager.joinGroup("group-B", key);
        manager.joinGroup("group-C", key);

        assertThat(manager.getSessionGroups(key)).containsExactlyInAnyOrder("group-A", "group-B", "group-C");
        assertThat(manager.getAllGroupIds()).containsExactlyInAnyOrder("group-A", "group-B", "group-C");

        // 一次性退出所有群组
        Set<String> leftGroups = manager.leaveAllGroups(key);
        assertThat(leftGroups).containsExactlyInAnyOrder("group-A", "group-B", "group-C");

        assertThat(manager.getSessionGroups(key)).isEmpty();
        assertThat(manager.getGroupMemberCount("group-A")).isEqualTo(0);
        assertThat(manager.getAllGroupIds()).isEmpty();
    }

    @Test
    @DisplayName("会话断开注销时自动排空所属全部群组")
    void testAutoLeaveOnSessionClosed() {
        RealtimeGroupManager manager = new RealtimeGroupManager();

        SessionKey key = SessionKey.of("user-disconnect", "c2");
        manager.joinGroup("room-101", key);
        manager.joinGroup("room-102", key);

        assertThat(manager.getGroupMemberCount("room-101")).isEqualTo(1);

        WebSocketSession ws = mock(WebSocketSession.class);
        RealtimeSession session = RealtimeSession.ofWebSocket(key, ws);

        // 模拟触发 onSessionClosed
        manager.onSessionClosed(session, RealtimeCloseStatus.NORMAL);

        assertThat(manager.getSessionGroups(key)).isEmpty();
        assertThat(manager.getGroupMemberCount("room-101")).isEqualTo(0);
        assertThat(manager.getGroupMemberCount("room-102")).isEqualTo(0);
    }

    @Test
    @DisplayName("解散群组: dismissGroup 移出全量成员并清理反向索引")
    void testDismissGroup() {
        RealtimeGroupManager manager = new RealtimeGroupManager();

        SessionKey k1 = SessionKey.of("u1", "c1");
        SessionKey k2 = SessionKey.of("u2", "c2");

        manager.joinGroup("room-dismiss", k1);
        manager.joinGroup("room-dismiss", k2);
        manager.joinGroup("room-keep", k1);

        assertThat(manager.getGroupMemberCount("room-dismiss")).isEqualTo(2);
        assertThat(manager.getSessionGroups(k1)).containsExactlyInAnyOrder("room-dismiss", "room-keep");

        // 解散 room-dismiss
        Set<SessionKey> ejected = manager.dismissGroup("room-dismiss");
        assertThat(ejected).containsExactlyInAnyOrder(k1, k2);

        // 验证 room-dismiss 已清空，k1 的 room-keep 仍保留
        assertThat(manager.getGroupMemberCount("room-dismiss")).isZero();
        assertThat(manager.getGroupSessions("room-dismiss")).isEmpty();
        assertThat(manager.getSessionGroups(k1)).containsExactly("room-keep");
        assertThat(manager.getSessionGroups(k2)).isEmpty();

        // 对不存在或空群组解散保底返回空集合
        assertThat(manager.dismissGroup("non-existing")).isEmpty();
        assertThat(manager.dismissGroup(null)).isEmpty();
        assertThat(manager.dismissGroup("   ")).isEmpty();
    }

    @Test
    @DisplayName("集群群组透传方法单机模式保底测试")
    void testClusterGroupMethodsStandalone() {
        RealtimeGroupManager manager = new RealtimeGroupManager();

        SessionKey k1 = SessionKey.of("u1", "c1");
        manager.joinGroup("room-cluster", k1);

        assertThat(manager.getClusterGroupMemberCount("room-cluster")).isEqualTo(1);
        assertThat(manager.getClusterGroupMembers("room-cluster")).containsExactly(k1);
    }
}

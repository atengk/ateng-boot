package io.github.atengk.boot.realtime.cluster;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.enums.ClusterBroadcastType;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集群分布式广播数据包契约载荷测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("集群分布式广播数据包测试")
class RealtimeClusterPacketTest {

    @Test
    @DisplayName("用户单播数据包构建与序列化自洽")
    void testUserPacketSerialization() {
        RealtimeMessage<String> message = RealtimeMessage.of("notice", "hello cluster");
        RealtimeClusterPacket packet = RealtimeClusterPacket.ofUser("node-1", "user-888", 999L, message);

        assertThat(packet.getSourceNodeId()).isEqualTo("node-1");
        assertThat(packet.getBroadcastType()).isEqualTo(ClusterBroadcastType.USER);
        assertThat(packet.getTargetKey()).isEqualTo("user-888");
        assertThat(packet.getTenantId()).isEqualTo(999L);
        assertThat(packet.getMessage()).isEqualTo(message);
        assertThat(packet.getTimestamp()).isPositive();

        // 序列化与反序列化验证
        String json = JsonUtil.toJsonString(packet);
        assertThat(json).contains("node-1").contains("user-888").contains("notice").contains("999");

        RealtimeClusterPacket deserialized = JsonUtil.parseObject(json, RealtimeClusterPacket.class);
        assertThat(deserialized).isNotNull();
        assertThat(deserialized.getSourceNodeId()).isEqualTo("node-1");
        assertThat(deserialized.getBroadcastType()).isEqualTo(ClusterBroadcastType.USER);
        assertThat(deserialized.getTargetKey()).isEqualTo("user-888");
        assertThat(deserialized.getTenantId()).isEqualTo(999L);
        assertThat(deserialized.getMessage().getEvent()).isEqualTo("notice");
    }

    @Test
    @DisplayName("房间群组与全租户数据包构建")
    void testGroupAndTenantPacket() {
        RealtimeMessage<Integer> message = RealtimeMessage.of("score", 100);
        RealtimeClusterPacket groupPacket = RealtimeClusterPacket.ofGroup("node-1", "room-101", message);
        assertThat(groupPacket.getBroadcastType()).isEqualTo(ClusterBroadcastType.GROUP);
        assertThat(groupPacket.getTargetKey()).isEqualTo("room-101");

        RealtimeClusterPacket tenantPacket = RealtimeClusterPacket.ofTenant("node-2", 99L, message);
        assertThat(tenantPacket.getBroadcastType()).isEqualTo(ClusterBroadcastType.TENANT);
        assertThat(tenantPacket.getTargetKey()).isEqualTo("99");

        RealtimeClusterPacket allPacket = RealtimeClusterPacket.ofAll("node-3", message);
        assertThat(allPacket.getBroadcastType()).isEqualTo(ClusterBroadcastType.ALL);
        assertThat(allPacket.getTargetKey()).isNull();
    }

    @Test
    @DisplayName("全网踢人下线信令数据包构建")
    void testKickoutPacket() {
        RealtimeClusterPacket kickoutPacket = RealtimeClusterPacket.ofKickout("node-1", "user-999", 4002, "安全封禁强制下线");
        assertThat(kickoutPacket.getBroadcastType()).isEqualTo(ClusterBroadcastType.KICKOUT);
        assertThat(kickoutPacket.getTargetKey()).isEqualTo("user-999");
        assertThat(kickoutPacket.getCloseStatusCode()).isEqualTo(4002);
        assertThat(kickoutPacket.getReason()).isEqualTo("安全封禁强制下线");
        assertThat(kickoutPacket.getMessage()).isNull();
    }
}

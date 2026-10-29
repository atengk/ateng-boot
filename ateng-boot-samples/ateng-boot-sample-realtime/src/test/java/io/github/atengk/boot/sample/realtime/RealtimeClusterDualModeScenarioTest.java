package io.github.atengk.boot.sample.realtime;

import io.github.atengk.boot.realtime.group.RealtimeGroupManager;
import io.github.atengk.boot.realtime.group.RedissonRealtimeGroupManager;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.spi.RealtimeOfflineMessageProvider;
import io.github.atengk.boot.realtime.spi.RedissonRealtimeOfflineMessageProvider;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场景 8：Redis 分布式群组治理、全域感知与有界离线消息双模自适应实测
 * <p>
 * 遵循 ADR-0028 与 ADR-0061 规范，实现单测试套件双模自适应架构：
 * 1. 默认/CI 模式 (!local profile)：运行于单机内存组件模式，100% 离线秒级通过；
 * 2. Local 真实模式 (local profile)：直连物理 Redis 服务，全真断言 RedissonRealtimeGroupManager
 *    与 RedissonRealtimeOfflineMessageProvider 分布式原子操作，并在 @AfterEach 中严格清理测试残留数据。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@DisplayName("场景 8: Redis 分布式群组治理与有界离线消息双模自适应实测")
class RealtimeClusterDualModeScenarioTest {

    private static final String TEST_GROUP_ID = "local-test-group-001";
    private static final String TEST_USER_ID = "local-test-user-001";

    @Autowired
    private Environment environment;

    @Autowired
    private RealtimeTemplate realtimeTemplate;

    @Autowired
    private RealtimeGroupManager groupManager;

    @Autowired(required = false)
    private RealtimeOfflineMessageProvider offlineMessageProvider;

    @Autowired(required = false)
    private RedissonClient redissonClient;

    private boolean isLocalProfile() {
        return Arrays.asList(environment.getActiveProfiles()).contains("local");
    }

    @BeforeEach
    @AfterEach
    void cleanUp() {
        if (realtimeTemplate != null) {
            try {
                realtimeTemplate.dismissGroup(TEST_GROUP_ID);
            } catch (Throwable ignored) {
            }
            try {
                realtimeTemplate.clearOfflineMessages(TEST_USER_ID);
            } catch (Throwable ignored) {
            }
        }
        if (isLocalProfile() && redissonClient != null) {
            try {
                redissonClient.getKeys().deleteByPattern("ateng:realtime:*local-test*");
            } catch (Throwable ignored) {
            }
        }
    }

    @Test
    @DisplayName("双模驱动装配与运行模式断言")
    void testDriverAssemblyMode() {
        if (isLocalProfile()) {
            log.info("[Local-Real-Test] 当前处于 local 模式，验证真实 Redisson 分布式驱动自动装配");
            assertThat(redissonClient).isNotNull();
            assertThat(groupManager).isInstanceOf(RedissonRealtimeGroupManager.class);
            assertThat(offlineMessageProvider).isInstanceOf(RedissonRealtimeOfflineMessageProvider.class);
        } else {
            log.info("[CI-Standalone-Test] 当前处于非 local 模式，验证单机保底组件平稳运行");
            assertThat(groupManager).isNotNull();
            assertThat(offlineMessageProvider).isNotNull();
        }
    }

    @Test
    @DisplayName("分布式群组成员加入、全域感知、统计与房间解散实测")
    void testClusterGroupLifecycleAndDismissal() {
        SessionKey sessionKey1 = SessionKey.of("user-A", "device-1", "sess-001");
        SessionKey sessionKey2 = SessionKey.of("user-B", "device-2", "sess-002");

        // 1. 加入群组
        boolean joined1 = groupManager.joinGroup(TEST_GROUP_ID, sessionKey1);
        boolean joined2 = groupManager.joinGroup(TEST_GROUP_ID, sessionKey2);
        assertThat(joined1).isTrue();
        assertThat(joined2).isTrue();

        // 2. 全域成员人数与成员感知
        int memberCount = realtimeTemplate.getClusterGroupMemberCount(TEST_GROUP_ID);
        assertThat(memberCount).isEqualTo(2);

        Set<SessionKey> members = realtimeTemplate.getClusterGroupMembers(TEST_GROUP_ID);
        assertThat(members).contains(sessionKey1, sessionKey2);

        // 若处于真实 Redis 模式，进一步直查 Redis 底层 Set 验证物理一致性
        if (isLocalProfile() && redissonClient != null && groupManager instanceof RedissonRealtimeGroupManager redissonGroupManager) {
            String redisGroupKey = redissonGroupManager.getGroupKey(TEST_GROUP_ID);
            RSet<String> rSet = redissonClient.getSet(redisGroupKey, StringCodec.INSTANCE);
            assertThat(rSet.isExists()).isTrue();
            assertThat(rSet.size()).isEqualTo(2);
            assertThat(rSet.contains(sessionKey1.format())).isTrue();
            assertThat(rSet.contains(sessionKey2.format())).isTrue();
        }

        // 3. 单个成员离开群组
        boolean left1 = groupManager.leaveGroup(TEST_GROUP_ID, sessionKey1);
        assertThat(left1).isTrue();
        assertThat(realtimeTemplate.getClusterGroupMemberCount(TEST_GROUP_ID)).isEqualTo(1);
        assertThat(realtimeTemplate.getClusterGroupMembers(TEST_GROUP_ID)).containsOnly(sessionKey2);

        // 4. 全域解散房间
        int dismissed = realtimeTemplate.dismissGroup(TEST_GROUP_ID);
        assertThat(dismissed).isGreaterThanOrEqualTo(0);
        assertThat(realtimeTemplate.getClusterGroupMemberCount(TEST_GROUP_ID)).isEqualTo(0);
        assertThat(realtimeTemplate.getClusterGroupMembers(TEST_GROUP_ID)).isEmpty();

        // 验证物理 Key 已彻底移除
        if (isLocalProfile() && redissonClient != null && groupManager instanceof RedissonRealtimeGroupManager redissonGroupManager) {
            String redisGroupKey = redissonGroupManager.getGroupKey(TEST_GROUP_ID);
            RSet<String> rSet = redissonClient.getSet(redisGroupKey, StringCodec.INSTANCE);
            assertThat(rSet.isExists()).isFalse();
        }
    }

    @Test
    @DisplayName("基于 Redis ZSet 的有界离线消息暂存与 SSE Last-Event-ID 断点重放实测")
    void testOfflineMessagesAndSseResumption() {
        // 1. 向无在线连接的测试用户发送 3 条持久化离线消息
        realtimeTemplate.sendToUser(TEST_USER_ID, RealtimeMessage.builder()
                .id("local-msg-001")
                .event("chat:msg")
                .data(Map.of("content", "message-1", "seq", 1))
                .persistent(true)
                .build());
        realtimeTemplate.sendToUser(TEST_USER_ID, RealtimeMessage.builder()
                .id("local-msg-002")
                .event("chat:msg")
                .data(Map.of("content", "message-2", "seq", 2))
                .persistent(true)
                .build());
        realtimeTemplate.sendToUser(TEST_USER_ID, RealtimeMessage.builder()
                .id("local-msg-003")
                .event("chat:msg")
                .data(Map.of("content", "message-3", "seq", 3))
                .persistent(true)
                .build());

        // 2. 检查暂存消息总数
        List<RealtimeMessage<?>> allMessages = realtimeTemplate.getOfflineMessages(TEST_USER_ID);
        assertThat(allMessages).hasSize(3);

        String firstId = allMessages.get(0).getId();
        assertThat(firstId).isNotBlank();

        // 3. 基于 Last-Event-ID 执行只读断点重放 (跳过第一条，返回后续 2 条)
        List<RealtimeMessage<?>> resumedMessages = realtimeTemplate.getOfflineMessagesSince(TEST_USER_ID, firstId);
        assertThat(resumedMessages).hasSize(2);

        // 验证重放是只读窥探，不影响原始消息存储
        assertThat(realtimeTemplate.getOfflineMessages(TEST_USER_ID)).hasSize(3);

        // 若处于真实 Redis 模式，强断言 Redis ZSet 物理结构
        if (isLocalProfile() && redissonClient != null) {
            String offlineKey = "ateng:realtime:offline:" + TEST_USER_ID;
            RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(offlineKey, StringCodec.INSTANCE);
            assertThat(zset.isExists()).isTrue();
            assertThat(zset.size()).isEqualTo(3);
        }

        // 4. 清理离线消息
        realtimeTemplate.clearOfflineMessages(TEST_USER_ID);
        assertThat(realtimeTemplate.getOfflineMessages(TEST_USER_ID)).isEmpty();

        if (isLocalProfile() && redissonClient != null) {
            String offlineKey = "ateng:realtime:offline:" + TEST_USER_ID;
            RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(offlineKey, StringCodec.INSTANCE);
            assertThat(zset.isExists()).isFalse();
        }
    }
}

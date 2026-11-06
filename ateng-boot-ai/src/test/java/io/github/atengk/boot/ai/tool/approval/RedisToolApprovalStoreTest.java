package io.github.atengk.boot.ai.tool.approval;

import io.github.atengk.boot.ai.properties.ToolApprovalProperties;
import io.github.atengk.boot.ai.tool.approval.store.RedisToolApprovalStore;
import io.github.atengk.boot.core.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分布式 Redis 高危工具工单存储实现单元测试 (RedisToolApprovalStoreTest)
 *
 * @author Ateng
 * @since 2026-11-06
 */
class RedisToolApprovalStoreTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ZSetOperations<String, String> zSetOperations;
    private RedisToolApprovalStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        zSetOperations = mock(ZSetOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        ToolApprovalProperties properties = new ToolApprovalProperties();
        properties.setKeyPrefix("ateng:test:ai:approval:");
        properties.setDefaultTimeout(Duration.ofMinutes(30));

        store = new RedisToolApprovalStore(properties, redisTemplate);
    }

    @Test
    @DisplayName("测试工单保存并推入 ZSet 活跃待审批索引")
    void testSave() {
        ToolApprovalTicket ticket = ToolApprovalTicket.builder()
                .ticketId("T_REDIS_01")
                .toolName("refund")
                .status(ToolApprovalStatus.PENDING)
                .build();

        store.save(ticket, Duration.ofMinutes(15));

        verify(valueOperations).set(eq("ateng:test:ai:approval:ticket:T_REDIS_01"), anyString(), eq(Duration.ofMinutes(15)));
        verify(zSetOperations).add(eq("ateng:test:ai:approval:pending"), eq("T_REDIS_01"), anyDouble());
    }

    @Test
    @DisplayName("测试工单查询时进行活跃滑动保活 TTL 续期")
    void testFindByIdWithSlidingTtl() {
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(20);
        ToolApprovalTicket ticket = ToolApprovalTicket.builder()
                .ticketId("T_SLIDE_01")
                .toolName("refund")
                .status(ToolApprovalStatus.PENDING)
                .expireTime(expireTime)
                .build();

        when(valueOperations.get("ateng:test:ai:approval:ticket:T_SLIDE_01"))
                .thenReturn(JsonUtil.toJsonString(ticket));

        Optional<ToolApprovalTicket> found = store.findById("T_SLIDE_01");
        assertThat(found).isPresent();
        assertThat(found.get().getTicketId()).isEqualTo("T_SLIDE_01");

        // 验证调用了 expire 进行滑动续期
        verify(redisTemplate).expire(eq("ateng:test:ai:approval:ticket:T_SLIDE_01"), any(Duration.class));
    }

    @Test
    @DisplayName("测试基于原子 Lua 脚本的 CAS 状态流转成功与失败分支")
    @SuppressWarnings("unchecked")
    void testUpdateStatusAtomicCas() {
        ToolApprovalTicket ticket = ToolApprovalTicket.builder()
                .ticketId("T_CAS_01")
                .toolName("refund")
                .status(ToolApprovalStatus.PENDING)
                .build();

        when(valueOperations.get("ateng:test:ai:approval:ticket:T_CAS_01"))
                .thenReturn(JsonUtil.toJsonString(ticket));

        // 1. 模拟 Lua 脚本 CAS 执行成功 (返回 1L)
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq("PENDING"), anyString(), eq("APPROVED"), anyString(), eq("T_CAS_01")))
                .thenReturn(1L);

        boolean success = store.updateStatus("T_CAS_01", ToolApprovalStatus.PENDING, ToolApprovalStatus.APPROVED,
                "同意执行", "{}", null);
        assertThat(success).isTrue();

        // 2. 模拟 Lua 脚本 CAS 失败 (并发竞争返回 0L)
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq("PENDING"), anyString(), eq("APPROVED"), anyString(), eq("T_CAS_01")))
                .thenReturn(0L);

        boolean failed = store.updateStatus("T_CAS_01", ToolApprovalStatus.PENDING, ToolApprovalStatus.APPROVED,
                "同意执行", "{}", null);
        assertThat(failed).isFalse();
    }

    @Test
    @DisplayName("测试批量 multiGet 查询待审批工单列表消除 N+1 网络往返")
    void testListPendingBatchMultiGet() {
        when(zSetOperations.rangeByScore(eq("ateng:test:ai:approval:pending"), anyDouble(), anyDouble()))
                .thenReturn(Set.of("T1", "T2"));

        ToolApprovalTicket t1 = ToolApprovalTicket.builder()
                .ticketId("T1")
                .status(ToolApprovalStatus.PENDING)
                .expireTime(LocalDateTime.now().plusMinutes(10))
                .build();
        ToolApprovalTicket t2 = ToolApprovalTicket.builder()
                .ticketId("T2")
                .status(ToolApprovalStatus.PENDING)
                .expireTime(LocalDateTime.now().plusMinutes(10))
                .build();

        when(valueOperations.multiGet(anyList()))
                .thenReturn(List.of(JsonUtil.toJsonString(t1), JsonUtil.toJsonString(t2)));

        List<ToolApprovalTicket> list = store.listPending();
        assertThat(list).hasSize(2);
        verify(valueOperations).multiGet(anyList());
    }
}

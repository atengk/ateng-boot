package io.github.atengk.boot.sms.loadbalancer;

import io.github.atengk.boot.sms.guard.SmsChannelCircuitBreaker;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsGuardProperties;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 短信渠道平滑加权轮询负载均衡器单元测试
 *
 * @author Ateng
 * @since 2026-11-07
 */
class SmsChannelLoadBalancerTest {

    private SmsChannelRegistry registry;
    private SmsChannelCircuitBreaker circuitBreaker;
    private SmsGuardProperties guardProps;

    @BeforeEach
    void setUp() {
        SmsProperties props = new SmsProperties();
        registry = new SmsChannelRegistry(props);
        guardProps = new SmsGuardProperties();
        guardProps.setCircuitBreakerFailureThreshold(2);
        circuitBreaker = new SmsChannelCircuitBreaker(guardProps);
    }

    @Test
    @DisplayName("测试：平滑加权轮询 (SWRR) 算法 3:1 权重精确分布与平滑选路序列")
    void testSmoothWeightedRoundRobin_DistributionAndSequence() {
        SmsChannel chA = SmsChannel.builder()
                .name("channel-a")
                .supplier(SmsSupplier.MOCK)
                .weight(3)
                .build();
        SmsChannel chB = SmsChannel.builder()
                .name("channel-b")
                .supplier(SmsSupplier.MOCK)
                .weight(1)
                .build();

        registry.register(chA);
        registry.register(chB);

        SmsChannelLoadBalancer loadBalancer = new SmsChannelLoadBalancer(registry, circuitBreaker);

        // 4 次选路应精确呈现 A, A, B, A 平滑交替
        List<String> sequence = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            SmsChannel selected = loadBalancer.selectChannel();
            assertNotNull(selected);
            sequence.add(selected.getName());
        }

        assertEquals(List.of("channel-a", "channel-a", "channel-b", "channel-a"), sequence);

        // 40 次选路统计分布：A 应为 30 次，B 应为 10 次
        loadBalancer.reset();
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            SmsChannel selected = loadBalancer.selectChannel();
            counts.put(selected.getName(), counts.getOrDefault(selected.getName(), 0) + 1);
        }

        assertEquals(30, counts.get("channel-a"));
        assertEquals(10, counts.get("channel-b"));
    }

    @Test
    @DisplayName("测试：自动过滤熔断开路 (OPEN) 态渠道，仅在健康渠道间加权分流")
    void testFilterOpenCircuitChannels() {
        SmsChannel chA = SmsChannel.builder()
                .name("channel-a")
                .supplier(SmsSupplier.MOCK)
                .weight(3)
                .build();
        SmsChannel chB = SmsChannel.builder()
                .name("channel-b")
                .supplier(SmsSupplier.MOCK)
                .weight(1)
                .build();

        registry.register(chA);
        registry.register(chB);

        SmsChannelLoadBalancer loadBalancer = new SmsChannelLoadBalancer(registry, circuitBreaker);

        // 使 channel-a 发生 2 次连续失败触发 OPEN
        circuitBreaker.recordFailure("channel-a");
        circuitBreaker.recordFailure("channel-a");
        assertTrue(circuitBreaker.isOpen("channel-a"));

        // 后续选路应自动跳过 channel-a，100% 路由至 channel-b
        for (int i = 0; i < 5; i++) {
            SmsChannel selected = loadBalancer.selectChannel();
            assertEquals("channel-b", selected.getName());
        }

        // channel-a 自愈恢复 CLOSED 后，重新参与加权分流
        circuitBreaker.reset("channel-a");
        assertFalse(circuitBreaker.isOpen("channel-a"));

        loadBalancer.reset();
        SmsChannel first = loadBalancer.selectChannel();
        assertEquals("channel-a", first.getName());
    }

    @Test
    @DisplayName("测试：所有渠道均处于熔断态时保底按权重选路")
    void testAllChannelsOpen_FallbackWeighted() {
        SmsChannel chA = SmsChannel.builder()
                .name("channel-a")
                .supplier(SmsSupplier.MOCK)
                .weight(2)
                .build();
        SmsChannel chB = SmsChannel.builder()
                .name("channel-b")
                .supplier(SmsSupplier.MOCK)
                .weight(1)
                .build();

        registry.register(chA);
        registry.register(chB);

        SmsChannelLoadBalancer loadBalancer = new SmsChannelLoadBalancer(registry, circuitBreaker);

        // 将全部渠道触发为 OPEN 态
        circuitBreaker.recordFailure("channel-a");
        circuitBreaker.recordFailure("channel-a");
        circuitBreaker.recordFailure("channel-b");
        circuitBreaker.recordFailure("channel-b");
        assertTrue(circuitBreaker.isOpen("channel-a"));
        assertTrue(circuitBreaker.isOpen("channel-b"));

        // 保底按权重选路，3 次中 A: 2 次, B: 1 次
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            SmsChannel selected = loadBalancer.selectChannel();
            assertNotNull(selected);
            counts.put(selected.getName(), counts.getOrDefault(selected.getName(), 0) + 1);
        }

        assertEquals(2, counts.get("channel-a"));
        assertEquals(1, counts.get("channel-b"));
    }

    @Test
    @DisplayName("测试：指定候选渠道子集列表选路")
    void testSelectChannelWithCandidateSubset() {
        registry.register(SmsChannel.builder().name("ch1").supplier(SmsSupplier.MOCK).weight(1).build());
        registry.register(SmsChannel.builder().name("ch2").supplier(SmsSupplier.MOCK).weight(1).build());
        registry.register(SmsChannel.builder().name("ch3").supplier(SmsSupplier.MOCK).weight(1).build());

        SmsChannelLoadBalancer loadBalancer = new SmsChannelLoadBalancer(registry, circuitBreaker);

        // 仅在 ch1 和 ch3 之间选路
        for (int i = 0; i < 6; i++) {
            SmsChannel selected = loadBalancer.selectChannel(List.of("ch1", "ch3"));
            assertTrue("ch1".equals(selected.getName()) || "ch3".equals(selected.getName()));
        }
    }

    @Test
    @DisplayName("测试：单渠道或空注册表边界")
    void testEdgeCases() {
        SmsChannelLoadBalancer emptyLb = new SmsChannelLoadBalancer(new SmsChannelRegistry(new SmsProperties()));
        assertNull(emptyLb.selectChannel());

        registry.register(SmsChannel.builder().name("only-one").supplier(SmsSupplier.MOCK).weight(5).build());
        SmsChannelLoadBalancer singleLb = new SmsChannelLoadBalancer(registry);
        assertEquals("only-one", singleLb.selectChannel().getName());
    }
}

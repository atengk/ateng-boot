package io.github.atengk.boot.job.local.lock;

import io.github.atengk.boot.job.local.config.AtengJobLocalAutoConfiguration;
import io.github.atengk.boot.job.local.properties.JobLocalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 原生 Redis 轻量集群互斥锁与原子防误删单元测试
 *
 * @author Ateng
 * @since 2026-11-01
 */
@DisplayName("RedisJobClusterLockProvider 轻量集群互斥锁与防误删测试")
class RedisJobClusterLockProviderTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private Map<String, String> simulatedRedisStore;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        simulatedRedisStore = new ConcurrentHashMap<>();
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // 模拟 SET key value NX PX timeout 命令
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer((Answer<Boolean>) invocation -> {
                    String key = invocation.getArgument(0);
                    String value = invocation.getArgument(1);
                    return simulatedRedisStore.putIfAbsent(key, value) == null;
                });

        // 模拟原子防误删 Lua 脚本执行
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenAnswer((Answer<Long>) invocation -> {
                    List<String> keys = invocation.getArgument(1);
                    Object[] args = invocation.getArguments();
                    String expectedOwner = args[2] != null ? args[2].toString() : null;
                    if (keys == null || keys.isEmpty() || expectedOwner == null) {
                        return 0L;
                    }
                    String key = keys.get(0);
                    String currentOwner = simulatedRedisStore.get(key);
                    if (expectedOwner.equals(currentOwner)) {
                        simulatedRedisStore.remove(key);
                        return 1L;
                    }
                    return 0L;
                });
    }

    @Test
    @DisplayName("测试锁成功获取与原子释放闭环")
    void testAcquireAndReleaseSuccess() {
        RedisJobClusterLockProvider lockProvider = new RedisJobClusterLockProvider(
                redisTemplate, "ateng:job:lock:", "node-A", Duration.ofMinutes(5));

        // 1. 成功加锁
        JobLockHandle handle = lockProvider.tryLock("orderSyncJob", Duration.ofMinutes(3));
        assertThat(handle).isNotNull();
        assertThat(handle.isAcquired()).isTrue();
        assertThat(handle.getLockKey()).isEqualTo("orderSyncJob");
        assertThat(handle.getLockOwner()).startsWith("node-A@");
        assertThat(handle.getLeaseExpireAt()).isAfter(Instant.now());

        // 验证 Redis 真实键与持有者
        String redisKey = lockProvider.buildRedisKey("orderSyncJob");
        assertThat(simulatedRedisStore.get(redisKey)).isEqualTo(handle.getLockOwner());

        // 2. 正常释放锁
        boolean released = lockProvider.unlock(handle);
        assertThat(released).isTrue();
        assertThat(simulatedRedisStore.containsKey(redisKey)).isFalse();
    }

    @Test
    @DisplayName("测试多节点并发竞争互斥：节点 A 持有锁期间，节点 B 抢锁失败")
    void testMutualExclusionBetweenNodes() {
        RedisJobClusterLockProvider nodeAProvider = new RedisJobClusterLockProvider(
                redisTemplate, "ateng:job:lock:", "node-A", Duration.ofMinutes(5));
        RedisJobClusterLockProvider nodeBProvider = new RedisJobClusterLockProvider(
                redisTemplate, "ateng:job:lock:", "node-B", Duration.ofMinutes(5));

        // 1. 节点 A 抢锁成功
        JobLockHandle handleA = nodeAProvider.tryLock("invoiceJob", Duration.ofMinutes(2));
        assertThat(handleA.isAcquired()).isTrue();

        // 2. 节点 B 同时抢同一任务锁 -> 互斥失败
        JobLockHandle handleB = nodeBProvider.tryLock("invoiceJob", Duration.ofMinutes(2));
        assertThat(handleB.isAcquired()).isFalse();

        // 3. 节点 A 释放锁后，节点 B 重新抢锁成功
        boolean releasedA = nodeAProvider.unlock(handleA);
        assertThat(releasedA).isTrue();

        JobLockHandle handleBRetry = nodeBProvider.tryLock("invoiceJob", Duration.ofMinutes(2));
        assertThat(handleBRetry.isAcquired()).isTrue();
        assertThat(handleBRetry.getLockOwner()).startsWith("node-B@");

        // 节点 B 释放
        nodeBProvider.unlock(handleBRetry);
    }

    @Test
    @DisplayName("测试原子防误删：持有者不匹配或租约过期转移时，严禁误删他人持有的锁")
    void testOwnerHygieneAntiAccidentalDeletion() {
        RedisJobClusterLockProvider nodeAProvider = new RedisJobClusterLockProvider(
                redisTemplate, "ateng:job:lock:", "node-A", Duration.ofMinutes(5));

        // 节点 A 加锁
        JobLockHandle handleA = nodeAProvider.tryLock("settleJob", Duration.ofMinutes(5));
        assertThat(handleA.isAcquired()).isTrue();

        String redisKey = nodeAProvider.buildRedisKey("settleJob");
        assertThat(simulatedRedisStore.get(redisKey)).isEqualTo(handleA.getLockOwner());

        // 构造一个伪造的/他人节点的锁句柄
        JobLockHandle fakeHandle = JobLockHandle.success("settleJob", "node-B@other-thread:fake-uuid", Instant.now().plusSeconds(300));

        // 节点尝试用 fakeHandle 释放锁 -> Lua 脚本原子校验失败返回 0，不删除节点 A 持有的锁
        boolean fakeReleased = nodeAProvider.unlock(fakeHandle);
        assertThat(fakeReleased).isFalse();
        assertThat(simulatedRedisStore.get(redisKey)).isEqualTo(handleA.getLockOwner());

        // 真实持有者节点 A 释放 -> 成功删除
        boolean realReleased = nodeAProvider.unlock(handleA);
        assertThat(realReleased).isTrue();
        assertThat(simulatedRedisStore.containsKey(redisKey)).isFalse();
    }

    @Test
    @DisplayName("测试释放不存在或已超时的锁返回 false 且无异常")
    void testUnlockNonExistentReturnsFalse() {
        RedisJobClusterLockProvider lockProvider = new RedisJobClusterLockProvider(
                redisTemplate, "ateng:job:lock:", "node-A", Duration.ofMinutes(5));

        JobLockHandle expiredHandle = JobLockHandle.success("nonExistentJob", "node-A@thread:uuid", Instant.now().minusSeconds(10));
        boolean released = lockProvider.unlock(expiredHandle);
        assertThat(released).isFalse();
    }

    @Test
    @DisplayName("测试前缀与默认租约时间配置生效")
    void testKeyPrefixAndDefaultLeaseConfiguration() {
        RedisJobClusterLockProvider lockProvider = new RedisJobClusterLockProvider(
                redisTemplate, "myapp:custom-job:lock:", "node-test", Duration.ofMinutes(10));

        assertThat(lockProvider.getKeyPrefix()).isEqualTo("myapp:custom-job:lock:");
        assertThat(lockProvider.getNodeId()).isEqualTo("node-test");
        assertThat(lockProvider.getDefaultLeaseTime()).isEqualTo(Duration.ofMinutes(10));

        JobLockHandle handle = lockProvider.tryLock("myJob", null);
        assertThat(handle.isAcquired()).isTrue();

        // 校验 Redis key 包含自定义前缀
        assertThat(simulatedRedisStore.containsKey("myapp:custom-job:lock:myJob")).isTrue();

        lockProvider.unlock(handle);
    }

    @Test
    @DisplayName("测试 Redis 异常时弹性防御：tryLock 与 unlock 安全拦截并返回保底结果")
    void testRedisExceptionResilience() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("Redis connection refused"));

        RedisJobClusterLockProvider lockProvider = new RedisJobClusterLockProvider(
                redisTemplate, "ateng:job:lock:", "node-A", Duration.ofMinutes(5));

        // Redis 崩溃时不抛出未捕获异常导致调度器中断，而是安全返回未获取句柄
        JobLockHandle handle = lockProvider.tryLock("failingRedisJob", Duration.ofMinutes(1));
        assertThat(handle.isAcquired()).isFalse();

        // unlock 遇到异常时安全返回 false
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RuntimeException("Redis network timeout"));

        JobLockHandle validHandle = JobLockHandle.success("failingRedisJob", "node-A@thread:uuid", Instant.now().plusSeconds(60));
        boolean unlocked = lockProvider.unlock(validHandle);
        assertThat(unlocked).isFalse();
    }

    @Test
    @DisplayName("测试自动装配：存在 StringRedisTemplate 时自动装配 RedisJobClusterLockProvider")
    void testAutoConfigurationWithRedisPresent() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengJobLocalAutoConfiguration.class))
                .withUserConfiguration(MockRedisConfig.class)
                .withPropertyValues("ateng.boot.job.local.cluster-lock.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(JobClusterLockProvider.class);
                    JobClusterLockProvider provider = context.getBean(JobClusterLockProvider.class);
                    assertThat(provider).isInstanceOf(RedisJobClusterLockProvider.class);
                });
    }

    @Test
    @DisplayName("测试自动装配：缺失 StringRedisTemplate 时自动降级为 LocalJobClusterLockProvider")
    void testAutoConfigurationFallbackToLocalWhenRedisAbsent() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengJobLocalAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(JobClusterLockProvider.class);
                    JobClusterLockProvider provider = context.getBean(JobClusterLockProvider.class);
                    assertThat(provider).isInstanceOf(LocalJobClusterLockProvider.class);
                });
    }

    @Configuration
    static class MockRedisConfig {
        @Bean
        public StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }
}

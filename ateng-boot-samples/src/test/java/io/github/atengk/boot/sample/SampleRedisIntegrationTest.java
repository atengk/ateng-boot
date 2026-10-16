package io.github.atengk.boot.sample;

import io.github.atengk.boot.redis.atomic.RedisAtomicUtil;
import io.github.atengk.boot.redis.lock.annotation.DistributedLock;
import io.github.atengk.boot.redis.lock.enums.LockMode;
import io.github.atengk.boot.redis.util.RedisUtil;
import io.github.atengk.boot.web.idempotent.store.IdempotentKeyStore;
import io.github.atengk.boot.web.idempotent.store.RedissonIdempotentKeyStore;
import io.github.atengk.boot.web.ratelimit.store.RateLimiterStore;
import io.github.atengk.boot.web.ratelimit.store.RedissonRateLimiterStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 样例工程 Redis 增强 Starter 与 Web 模块自适应端到端集成测试
 * 验证在真实 Spring Boot 容器环境下：
 * 1. @DistributedLock 声明式分布式锁切面拦截与 SpEL 动态求值
 * 2. RedisUtil 与 RedisAtomicUtil 门面自动装配与安全注入
 * 3. 驱动 ateng-boot-web 接口限流与防重复提交自动适配 Tier 1 (Redisson) 分布式存储引擎
 *
 * @author Ateng
 * @since 2026-10-16
 */
@SpringBootTest(properties = {
        "ateng.boot.redis.enabled=true",
        "ateng.boot.redis.delay-queue.enabled=false"
})
@Import({SampleRedisIntegrationTest.TestRedisConfig.class, SampleRedisIntegrationTest.SampleLockedService.class})
@DisplayName("样例工程 Redis 增强与 Web 自适应端到端集成测试")
class SampleRedisIntegrationTest {

    @TestConfiguration
    static class TestRedisConfig {

        @Bean
        @Primary
        public RLock sampleRLock() {
            RLock lock = mock(RLock.class);
            try {
                when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
                when(lock.isHeldByCurrentThread()).thenReturn(true);
            } catch (InterruptedException ignored) {
            }
            return lock;
        }

        @Bean
        @Primary
        public RedissonClient redissonClient(RLock sampleRLock) {
            RedissonClient client = mock(RedissonClient.class);
            when(client.getLock(any(String.class))).thenReturn(sampleRLock);
            return client;
        }

        @Bean
        @Primary
        public RedisConnectionFactory redisConnectionFactory() {
            return mock(RedisConnectionFactory.class);
        }
    }

    @Service
    static class SampleLockedService {

        @DistributedLock(key = "'order:' + #orderId", waitTime = 2000, leaseTime = 8000, mode = LockMode.REENTRANT)
        public String executeOrderBusiness(String orderId) {
            return "SUCCESS_ORDER_" + orderId;
        }
    }

    @Autowired
    private SampleLockedService sampleLockedService;

    @Autowired
    private RLock sampleRLock;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired(required = false)
    private RedisUtil redisUtil;

    @Autowired(required = false)
    private RedisAtomicUtil redisAtomicUtil;

    @Autowired(required = false)
    private RateLimiterStore rateLimiterStore;

    @Autowired(required = false)
    private IdempotentKeyStore idempotentKeyStore;

    @Test
    @DisplayName("端到端验证：@DistributedLock 声明式分布式锁切面拦截、SpEL 动态解析与加解锁协同")
    void shouldInterceptAndAcquireDistributedLockViaAspect() throws Exception {
        String result = sampleLockedService.executeOrderBusiness("ORD_9999");

        assertThat(result).isEqualTo("SUCCESS_ORDER_ORD_9999");
        verify(redissonClient).getLock("lock:order:ORD_9999");
        verify(sampleRLock).tryLock(eq(2000L), eq(8000L), eq(TimeUnit.SECONDS));
        verify(sampleRLock).unlock();
    }

    @Test
    @DisplayName("端到端验证：RedisUtil 与 RedisAtomicUtil 门面 Bean 成功注入 Spring 上下文")
    void shouldSuccessfullyInjectRedisUtilAndRedisAtomicUtil() {
        assertThat(redisUtil).isNotNull();
        assertThat(redisAtomicUtil).isNotNull();
    }

    @Test
    @DisplayName("端到端验证：Web 模块自适应检测到 RedissonClient 并升级为 Tier 1 分布式引擎")
    void shouldAdaptWebModuleToTier1RedissonStores() {
        // 验证 RateLimiterStore 自动晋升为 RedissonRateLimiterStore (Tier 1)
        assertThat(rateLimiterStore)
                .isNotNull()
                .isInstanceOf(RedissonRateLimiterStore.class);

        // 验证 IdempotentKeyStore 自动晋升为 RedissonIdempotentKeyStore (Tier 1)
        assertThat(idempotentKeyStore)
                .isNotNull()
                .isInstanceOf(RedissonIdempotentKeyStore.class);
    }
}

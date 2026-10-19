package io.github.atengk.boot.sample.redis;

import io.github.atengk.boot.redis.atomic.RedisAtomicUtil;
import io.github.atengk.boot.redis.lock.template.DistributedLockTemplate;
import io.github.atengk.boot.redis.lock.util.RedisLockUtil;
import io.github.atengk.boot.redis.util.RedisUtil;
import io.github.atengk.boot.sample.redis.model.OrderDelayPayload;
import io.github.atengk.boot.sample.redis.service.OrderDelayQueueService;
import io.github.atengk.boot.sample.redis.service.RedisSampleService;
import io.github.atengk.boot.web.idempotent.store.IdempotentKeyStore;
import io.github.atengk.boot.web.idempotent.store.RedissonIdempotentKeyStore;
import io.github.atengk.boot.web.ratelimit.store.RateLimiterStore;
import io.github.atengk.boot.web.ratelimit.store.RedissonRateLimiterStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RBucket;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RLock;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 样例工程 Redis 基础设施与分布式协同端到端集成测试
 * 验证在真实 Spring Boot 容器环境下：
 * 1. @DistributedLock 声明式分布式锁切面拦截、可重入性、看门狗自动续期与超时降级
 * 2. DistributedLockTemplate 与 RedisLockUtil 编程式锁与静态加锁闭包
 * 3. Redisson 双模延时队列：编程式投递与 @DelayedQueueListener 声明式消费
 * 4. RedisUtil 安全游标扫描（scanKeys）与 RedisAtomicUtil 日期流水号原子发号与滑动窗口
 * 5. Web 模块自适应检测 RedissonClient 并晋升为 Tier 1 分布式限流与防重幂等引擎
 *
 * @author Ateng
 * @since 2026-10-16
 */
@SpringBootTest(properties = {
        "ateng.boot.redis.enabled=true",
        "ateng.boot.redis.delay-queue.enabled=true"
})
@AutoConfigureMockMvc
@Import({SampleRedisIntegrationTest.TestRedisConfig.class})
@DisplayName("样例工程 Redis 基础设施与分布式协同端到端集成测试")
class SampleRedisIntegrationTest {

    @TestConfiguration
    static class TestRedisConfig {

        private final ReentrantLock jvmLock = new ReentrantLock();
        private final BlockingQueue<Object> internalDelayQueue = new LinkedBlockingQueue<>();

        @Bean
        @Primary
        public RLock sampleRLock() {
            RLock lock = mock(RLock.class);
            try {
                when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
                    long waitTime = inv.getArgument(0);
                    TimeUnit unit = inv.getArgument(2);
                    return jvmLock.tryLock(waitTime, unit);
                });
                when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
                    long waitTime = inv.getArgument(0);
                    TimeUnit unit = inv.getArgument(1);
                    return jvmLock.tryLock(waitTime, unit);
                });
                when(lock.tryLock()).thenAnswer(inv -> jvmLock.tryLock());
                when(lock.isHeldByCurrentThread()).thenAnswer(inv -> jvmLock.isHeldByCurrentThread());
                doAnswer(inv -> {
                    if (jvmLock.isHeldByCurrentThread()) {
                        jvmLock.unlock();
                    }
                    return null;
                }).when(lock).unlock();
            } catch (InterruptedException ignored) {
            }
            return lock;
        }

        @Bean
        @Primary
        @SuppressWarnings("unchecked")
        public RedissonClient redissonClient(RLock sampleRLock) {
            RedissonClient client = mock(RedissonClient.class);

            // 分布式锁模拟：对于包含 timeout/fallback 的 Key 模拟争锁超时失败
            when(client.getLock(anyString())).thenAnswer(inv -> {
                String lockKey = inv.getArgument(0);
                if (lockKey.contains("timeout") || lockKey.contains("fallback")) {
                    RLock timeoutLock = mock(RLock.class);
                    when(timeoutLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);
                    when(timeoutLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);
                    return timeoutLock;
                }
                return sampleRLock;
            });

            // 限流令牌桶模拟
            RRateLimiter rateLimiter = mock(RRateLimiter.class);
            when(rateLimiter.tryAcquire(anyLong())).thenReturn(true);
            when(client.getRateLimiter(anyString())).thenReturn(rateLimiter);

            // 幂等防重 Bucket 模拟
            RBucket<Object> bucket = mock(RBucket.class);
            when(bucket.setIfAbsent(any(), any())).thenReturn(true);
            when(client.getBucket(anyString())).thenReturn(bucket);

            // 延时队列模拟
            RBlockingQueue<Object> blockingQueue = mock(RBlockingQueue.class);
            try {
                when(blockingQueue.poll(anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
                    long timeout = inv.getArgument(0);
                    TimeUnit unit = inv.getArgument(1);
                    return internalDelayQueue.poll(timeout, unit);
                });
            } catch (InterruptedException ignored) {
            }
            when(blockingQueue.poll()).thenAnswer(inv -> internalDelayQueue.poll());
            when(blockingQueue.size()).thenAnswer(inv -> internalDelayQueue.size());

            RDelayedQueue<Object> delayedQueue = mock(RDelayedQueue.class);
            doAnswer(inv -> {
                Object msg = inv.getArgument(0);
                internalDelayQueue.offer(msg);
                return null;
            }).when(delayedQueue).offer(any(), anyLong(), any(TimeUnit.class));
            when(delayedQueue.size()).thenAnswer(inv -> internalDelayQueue.size());

            when(client.getBlockingQueue(anyString())).thenReturn(blockingQueue);
            when(client.getDelayedQueue(any())).thenReturn(delayedQueue);

            return client;
        }

        @Bean
        @Primary
        public RedisConnectionFactory redisConnectionFactory() {
            return mock(RedisConnectionFactory.class);
        }

        @Bean
        @Primary
        @SuppressWarnings("unchecked")
        public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
            RedisTemplate<String, Object> template = mock(RedisTemplate.class);
            ValueOperations<String, Object> valOps = mock(ValueOperations.class);
            ZSetOperations<String, Object> zSetOps = mock(ZSetOperations.class);

            when(template.getConnectionFactory()).thenReturn(connectionFactory);
            when(template.opsForValue()).thenReturn(valOps);
            when(template.opsForZSet()).thenReturn(zSetOps);

            // 安全游标模糊扫描模拟
            when(template.scan(any(ScanOptions.class))).thenAnswer(inv -> {
                Cursor<String> cursor = mock(Cursor.class);
                when(cursor.hasNext()).thenReturn(true, true, false);
                when(cursor.next()).thenReturn("sample:user:1001", "sample:user:1002");
                return cursor;
            });

            // RedisAtomicUtil Lua 脚本模拟（单入参与多入参滑动窗口）
            when(template.execute(
                    any(RedisScript.class),
                    any(org.springframework.data.redis.serializer.RedisSerializer.class),
                    any(),
                    any(List.class),
                    any()
            )).thenReturn(1L);

            when(template.execute(
                    any(RedisScript.class),
                    any(org.springframework.data.redis.serializer.RedisSerializer.class),
                    any(),
                    any(List.class),
                    any(), any(), any(), any(), any()
            )).thenReturn(1L);

            return template;
        }
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        org.mockito.Mockito.clearInvocations(sampleRLock, redissonClient);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RedisSampleService redisSampleService;

    @Autowired
    private OrderDelayQueueService orderDelayQueueService;

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

    @Autowired(required = false)
    private DistributedLockTemplate distributedLockTemplate;

    @Test
    @DisplayName("端到端验证：@DistributedLock 声明式分布式锁切面拦截、SpEL 动态解析与加解锁协同")
    void shouldInterceptAndAcquireDistributedLockViaAspect() throws Exception {
        String result = redisSampleService.executeOrderBusiness("ORD_9999");

        assertThat(result).isEqualTo("SUCCESS_ORDER_ORD_9999");
        verify(redissonClient).getLock("lock:order:ORD_9999");
        verify(sampleRLock).tryLock(eq(2000L), eq(8000L), eq(TimeUnit.SECONDS));
        verify(sampleRLock).unlock();
    }

    @Test
    @DisplayName("端到端验证：RedisSampleController 扣减库存接口受 @DistributedLock 保护且返回统一 Result 契约")
    void shouldExecuteDeductStockViaController() throws Exception {
        mockMvc.perform(post("/api/redis-sample/deduct-stock")
                        .param("productId", "1001")
                        .param("count", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("扣减库存成功: 5"));

        verify(redissonClient).getLock("lock:product:1001");
    }

    @Test
    @DisplayName("端到端验证：DistributedLockTemplate 与 RedisLockUtil 编程式分布式锁执行并安全释放")
    void shouldExecuteProgrammaticDistributedLockViaTemplateAndUtil() throws Throwable {
        assertThat(distributedLockTemplate).isNotNull();

        String res1 = distributedLockTemplate.execute("prog:order:1", () -> "TEMPLATE_OK");
        assertThat(res1).isEqualTo("TEMPLATE_OK");

        String res2 = RedisLockUtil.execute("prog:order:2", () -> "UTIL_OK");
        assertThat(res2).isEqualTo("UTIL_OK");

        verify(redissonClient).getLock("lock:prog:order:1");
        verify(redissonClient).getLock("lock:prog:order:2");
    }

    @Test
    @DisplayName("端到端验证：分布式锁同一线程可重入性（外层注解加锁，内层模板重入加锁）")
    void shouldExecuteReentrantLockAcrossMethods() {
        String result = redisSampleService.executeReentrantBusiness("ORD_REENTRANT_888");

        assertThat(result).isEqualTo("REENTRANT_SUCCESS_ORD_REENTRANT_888");
        verify(redissonClient, org.mockito.Mockito.times(2)).getLock("lock:reentrant:ORD_REENTRANT_888");
    }

    @Test
    @DisplayName("端到端验证：分布式锁争锁超时自动降级（fallback 兜底闭包顺利执行）")
    void shouldExecuteLockWithFallbackWhenTimeout() {
        String result = redisSampleService.executeWithFallback("fallback_res_1");

        assertThat(result).isEqualTo("FALLBACK_TRIGGERED_fallback_res_1");
    }

    @Test
    @DisplayName("端到端验证：分布式锁看门狗自动续期长事务执行（leaseTime = -1）")
    void shouldExecuteLockWithWatchdog() {
        String result = redisSampleService.executeWithWatchdog("TASK_LONG_RUNNING");

        assertThat(result).isEqualTo("WATCHDOG_SUCCESS_TASK_LONG_RUNNING");
        verify(redissonClient).getLock("lock:watchdog:TASK_LONG_RUNNING");
    }

    @Test
    @DisplayName("端到端验证：Redisson 双模延时队列投递与 @DelayedQueueListener 声明式消费")
    void shouldSendAndConsumeDelayedOrderTask() throws Exception {
        orderDelayQueueService.sendOrderCancelTask("ORD_DELAY_999", Duration.ofSeconds(1));

        // 等待后台消费者线程拉取并消费
        long deadline = System.currentTimeMillis() + 5000;
        List<OrderDelayPayload> consumed = orderDelayQueueService.getConsumedOrders();
        while (consumed.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        assertThat(consumed).isNotEmpty();
        assertThat(consumed.get(0).getOrderId()).isEqualTo("ORD_DELAY_999");
        assertThat(consumed.get(0).getReason()).isEqualTo("订单未支付超时自动取消");
    }

    @Test
    @DisplayName("端到端验证：RedisUtil 安全游标扫描（scanKeys）与非阻塞迭代")
    void shouldScanKeysUsingRedisUtil() {
        Set<String> keys = redisSampleService.scanKeys("sample:user:*", 100);

        assertThat(keys).containsExactlyInAnyOrder("sample:user:1001", "sample:user:1002");
    }

    @Test
    @DisplayName("端到端验证：RedisAtomicUtil 结构化按日自重置流水号发号")
    void shouldGenerateDailySeqUsingRedisAtomicUtil() {
        String seq = redisSampleService.generateDailyOrderNo("ORD", 6);

        assertThat(seq).isNotBlank().startsWith("ORD").endsWith("000001");
    }

    @Test
    @DisplayName("端到端验证：RedisAtomicUtil 基于 ZSet 毫秒滑动窗口频控校验")
    void shouldCheckSlidingWindowUsingRedisAtomicUtil() {
        boolean allowed = redisSampleService.checkSlidingWindow("sms:user:1001", 5, Duration.ofSeconds(60));

        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("端到端验证：RedisSampleController 接口限流 @RateLimiter 正常放行与统一契约")
    void shouldAccessLimitedApiViaMockMvc() throws Exception {
        mockMvc.perform(get("/api/redis-sample/limited-api")
                        .param("userId", "USER_1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("请求成功: userId=USER_1001"));
    }

    @Test
    @DisplayName("端到端验证：RedisSampleController 接口幂等 @Idempotent 正常放行与统一契约")
    void shouldAccessIdempotentApiViaMockMvc() throws Exception {
        mockMvc.perform(post("/api/redis-sample/idempotent")
                        .param("token", "TOKEN_IDEM_888"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("幂等提交成功: token=TOKEN_IDEM_888"));
    }

    @Test
    @DisplayName("端到端验证：RedisSampleController 延时订单接口投递")
    void shouldSendDelayOrderViaController() throws Exception {
        mockMvc.perform(post("/api/redis-sample/delay-order")
                        .param("orderId", "ORD_API_100")
                        .param("delaySeconds", "15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("延时取消订单任务投递成功: ORD_API_100"));
    }

    @Test
    @DisplayName("端到端验证：RedisSampleController 游标扫描与流水号接口")
    void shouldCallScanKeysAndDailySeqViaController() throws Exception {
        mockMvc.perform(get("/api/redis-sample/scan-keys")
                        .param("pattern", "sample:user:*")
                        .param("count", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(get("/api/redis-sample/daily-seq")
                        .param("prefix", "PAY")
                        .param("length", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isString());
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

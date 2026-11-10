package io.github.atengk.boot.sample.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.atomic.RedisAtomicUtil;
import io.github.atengk.boot.redis.bloom.RedisBloomFilter;
import io.github.atengk.boot.redis.cache.template.RedisCacheTemplate;
import io.github.atengk.boot.redis.cache.twolevel.TwoLevelCacheManager;
import io.github.atengk.boot.redis.delay.RedisDelayedQueue;
import io.github.atengk.boot.redis.delay.model.DeadLetterMessage;
import io.github.atengk.boot.redis.idempotent.RedisIdempotentTemplate;
import io.github.atengk.boot.redis.idempotent.RedisIdempotentUtil;
import io.github.atengk.boot.redis.lock.template.DistributedLockTemplate;
import io.github.atengk.boot.redis.lock.util.RedisLockUtil;
import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.boot.redis.pubsub.RedisMessagePublisher;
import io.github.atengk.boot.redis.script.RedisScriptExecutor;
import io.github.atengk.boot.redis.semaphore.RedisSemaphoreTemplate;
import io.github.atengk.boot.redis.semaphore.RedisSemaphoreUtil;
import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import io.github.atengk.boot.redis.stream.RedisStreamTemplate;
import io.github.atengk.boot.redis.util.RedisUtil;
import io.github.atengk.boot.sample.redis.config.SampleInMemoryRedisConfig;
import io.github.atengk.boot.sample.redis.model.BloomFilterMetricsVO;
import io.github.atengk.boot.sample.redis.model.NoticeBroadcastPayload;
import io.github.atengk.boot.sample.redis.model.OrderDelayPayload;
import io.github.atengk.boot.sample.redis.model.OrderStreamEvent;
import io.github.atengk.boot.sample.redis.model.ProductVO;
import io.github.atengk.boot.sample.redis.model.cache.*;
import io.github.atengk.boot.sample.redis.model.delay.*;
import io.github.atengk.boot.sample.redis.model.idempotent.*;
import io.github.atengk.boot.sample.redis.model.lock.*;
import io.github.atengk.boot.sample.redis.model.ops.*;
import io.github.atengk.boot.sample.redis.model.pubsub.*;
import io.github.atengk.boot.sample.redis.model.stream.*;
import io.github.atengk.boot.sample.redis.model.traffic.*;
import io.github.atengk.boot.sample.redis.service.CacheScenarioService;
import io.github.atengk.boot.sample.redis.service.IdempotentScenarioService;
import io.github.atengk.boot.sample.redis.service.NoticeBroadcastScenarioService;
import io.github.atengk.boot.sample.redis.service.OrderDelayScenarioService;
import io.github.atengk.boot.sample.redis.service.ScriptExecutionScenarioService;
import io.github.atengk.boot.sample.redis.service.SemaphoreThrottlingScenarioService;
import io.github.atengk.boot.sample.redis.service.StockDeductScenarioService;
import io.github.atengk.boot.sample.redis.service.StreamEventScenarioService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RBucket;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RLock;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 样例工程 Redis 基础设施与分布式协同全场景端到端实测
 * 验证在真实 Spring Boot 容器环境下：
 * 1. StockDeductScenarioService: 分布式锁库存并发扣减、防超卖、重入、看门狗续期与争锁降级
 * 2. OrderDelayScenarioService: Redisson 双模延时队列投递、监听消费与死信流转运维
 * 3. StreamEventScenarioService: Redis 5.0+ Stream 消息追加、消费组监听消费与队列裁剪
 * 4. IdempotentScenarioService: 非 Web 业务级分布式防重幂等切面与失败自动释放凭据
 * 5. SemaphoreThrottlingScenarioService: 分布式受管信号量并发度治理与超时降级
 * 6. ScriptExecutionScenarioService: 受管 Lua 脚本执行、CAS 原子替换与按日流水号发号
 * 7. NoticeBroadcastScenarioService: Pub/Sub 主题广播与精确/通配模式监听
 * 8. CacheScenarioService: 两级缓存、RedisCacheTemplate 防穿透/击穿/N+1 与布隆过滤器
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SpringBootTest(properties = {
        "ateng.boot.redis.enabled=true",
        "ateng.boot.redis.two-level.enabled=true",
        "ateng.boot.redis.delay-queue.enabled=true",
        "ateng.boot.redis.stream.enabled=true",
        "ateng.boot.web.shutdown.enabled=false"
})
@AutoConfigureMockMvc
@Slf4j
@DisplayName("样例工程 Redis 基础设施与分布式协同全场景端到端实测")
class SampleRedisIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StockDeductScenarioService stockDeductScenarioService;

    @Autowired
    private OrderDelayScenarioService orderDelayScenarioService;

    @Autowired
    private StreamEventScenarioService streamEventScenarioService;

    @Autowired
    private IdempotentScenarioService idempotentScenarioService;

    @Autowired
    private SemaphoreThrottlingScenarioService semaphoreThrottlingScenarioService;

    @Autowired
    private ScriptExecutionScenarioService scriptExecutionScenarioService;

    @Autowired
    private NoticeBroadcastScenarioService noticeBroadcastScenarioService;

    @Autowired
    private CacheScenarioService cacheScenarioService;

    @Autowired
    private DistributedLockTemplate distributedLockTemplate;

    @Autowired
    private RedisDelayedQueue redisDelayedQueue;

    @Autowired
    private RedisStreamTemplate redisStreamTemplate;

    @Autowired
    private RedisIdempotentTemplate redisIdempotentTemplate;

    @Autowired
    private RedisSemaphoreTemplate redisSemaphoreTemplate;

    @Autowired
    private RedisScriptExecutor redisScriptExecutor;

    @Autowired
    private RedisMessagePublisher redisMessagePublisher;

    @Autowired
    private RedisCacheTemplate redisCacheTemplate;

    @Autowired
    private TwoLevelCacheManager twoLevelCacheManager;

    @Autowired
    private RedisBloomFilter redisBloomFilter;

    @Autowired
    private RedisAtomicUtil redisAtomicUtil;

    @Autowired
    private RedisProperties redisProperties;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired(required = false)
    private RedissonClient redissonClient;

    @Autowired
    private Environment environment;

    @Autowired(required = false)
    private ThreadPoolTaskExecutor defaultTaskExecutor;

    private boolean isLocalProfileActive() {
        return Arrays.asList(environment.getActiveProfiles()).contains("local");
    }

    @BeforeEach
    void setUp() {
        SampleInMemoryRedisConfig.clearAll();
        stockDeductScenarioService.resetStock(1001L, 100);
        cacheScenarioService.resetDbQueryCount();
        idempotentScenarioService.resetCounts();
    }

    @AfterEach
    void tearDown() {
        SampleInMemoryRedisConfig.clearAll();
        // 在本地真实 Redis 模式下，确定性清理测试残留数据，保证测试间零污染与幂等自洽
        if (redisTemplate != null && redisTemplate.getConnectionFactory() != null) {
            try {
                String[] patterns = {
                        "lock:*", "reentrant:*", "resource:*", "watchdog:*",
                        "sample:*", "stream:*", "idemp:*", "sem:*",
                        "atomic:*", "rate:*", "product:*", "user-profile:*",
                        "user-blacklist*"
                };
                for (String p : patterns) {
                    Set<String> keys = redisTemplate.keys(p);
                    if (keys != null && !keys.isEmpty()) {
                        redisTemplate.delete(keys);
                    }
                }
            } catch (Exception ex) {
                log.warn("[SampleRedisIntegrationTest] 测试数据清理异常: {}", ex.getMessage());
            }
        }
    }

    // =========================================================================
    // 场景 1: StockDeductScenarioService (分布式锁库存并发扣减与防超卖)
    // =========================================================================

    @Test
    @DisplayName("场景1：分布式锁严格并发库存扣减与防超卖校验")
    void testStockDeductConcurrencyAndProtection() {
        // 初始库存 100，成功扣减 20
        int remaining = stockDeductScenarioService.deductStock(1001L, 20);
        assertThat(remaining).isEqualTo(80);
        assertThat(stockDeductScenarioService.getStock(1001L)).isEqualTo(80);

        // 再次扣减 80，库存变为 0
        int remainingZero = stockDeductScenarioService.deductStock(1001L, 80);
        assertThat(remainingZero).isEqualTo(0);

        // 库存不足再次扣减，抛出业务异常
        assertThatThrownBy(() -> stockDeductScenarioService.deductStock(1001L, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存不足");
    }

    @Test
    @DisplayName("场景1：分布式锁可重入自验（外层注解持锁，内层闭包同一线程重入）")
    void testDistributedLockReentrancy() {
        String result = stockDeductScenarioService.executeReentrantBusiness("ORD_REENT_1001");
        assertThat(result).isEqualTo("REENTRANT_SUCCESS_ORD_REENT_1001");
    }

    @Test
    @DisplayName("场景1：分布式锁争锁超时降级演示（执行 fallback 闭包防御并发风暴）")
    void testDistributedLockTimeoutFallback() {
        if (isLocalProfileActive() && redissonClient != null) {
            // 真实 Redis 环境下：启动受管异步任务预先持锁以触发超时竞争
            CountDownLatch lockAcquired = new CountDownLatch(1);
            CountDownLatch testFinished = new CountDownLatch(1);
            RLock lock = redissonClient.getLock("lock:resource:fallback_res_1");
            Runnable lockHolder = () -> {
                try {
                    if (lock.tryLock(100, 5000, TimeUnit.MILLISECONDS)) {
                        lockAcquired.countDown();
                        testFinished.await(5, TimeUnit.SECONDS);
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                    }
                } catch (Exception ex) {
                    log.warn("[SampleRedisIntegrationTest] 真实争锁辅助任务执行异常: {}", ex.getMessage());
                }
            };

            Future<?> taskFuture = defaultTaskExecutor != null
                    ? defaultTaskExecutor.submit(lockHolder)
                    : CompletableFuture.runAsync(lockHolder);

            try {
                boolean acquired = lockAcquired.await(2, TimeUnit.SECONDS);
                assertThat(acquired).isTrue();

                String result = stockDeductScenarioService.executeWithFallback("fallback_res_1");
                assertThat(result).isEqualTo("FALLBACK_TRIGGERED_fallback_res_1");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("测试等待持锁中断", e);
            } finally {
                testFinished.countDown();
                try {
                    taskFuture.get(2, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    log.debug("[SampleRedisIntegrationTest] 等待锁持有任务完成: {}", ex.getMessage());
                }
            }
        } else {
            // 离线 Mock 模式：TestRedisConfig 自动针对 fallback 键模拟争锁失败，秒级极速断言降级分支
            String result = stockDeductScenarioService.executeWithFallback("fallback_res_1");
            assertThat(result).isEqualTo("FALLBACK_TRIGGERED_fallback_res_1");
        }
    }

    @Test
    @DisplayName("场景1：看门狗自动续期长事务执行自验 (leaseTime = -1)")
    void testWatchdogLongRunningTask() {
        String result = stockDeductScenarioService.executeWithWatchdog("TASK_WATCH_99");
        assertThat(result).isEqualTo("WATCHDOG_SUCCESS_TASK_WATCH_99");
    }

    @Test
    @DisplayName("场景1：编程式批量多锁安全执行 (MultiLock)")
    void testMultiLockExecution() {
        String result = stockDeductScenarioService.executeMultiLock("ORD_MULTI_1", 1001L);
        assertThat(result).isEqualTo("MULTI_LOCK_SUCCESS_ORD_MULTI_1_1001");
    }

    @Test
    @DisplayName("场景1：RedisLockUtil 静态无注入调用门面验证")
    void testStaticRedisLockUtil() {
        String result = RedisLockUtil.execute("lock:static:test", () -> "STATIC_LOCK_OK");
        assertThat(result).isEqualTo("STATIC_LOCK_OK");
    }

    // =========================================================================
    // 场景 2: OrderDelayScenarioService (延时队列与死信运维)
    // =========================================================================

    @Test
    @DisplayName("场景2：编程式投递订单延时取消任务与 @DelayedQueueListener 声明式消费")
    void testOrderDelayQueuePublishAndConsume() throws Exception {
        orderDelayScenarioService.reset(1);

        orderDelayScenarioService.sendOrderCancelTask("ORD_DELAY_001", Duration.ofMillis(500));

        boolean consumed = orderDelayScenarioService.getLatch().await(5, TimeUnit.SECONDS);
        assertThat(consumed).isTrue();
        assertThat(orderDelayScenarioService.getConsumedOrders()).hasSize(1);
        assertThat(orderDelayScenarioService.getConsumedOrders().get(0).getOrderId()).isEqualTo("ORD_DELAY_001");
    }

    @Test
    @DisplayName("场景2：订单延时队列死信消息流转与拉取运维自验")
    void testOrderDelayDeadLetterTransferAndPoll() throws Exception {
        orderDelayScenarioService.sendPoisonOrder("POISON_ORD_DLQ_002");

        // 轮询等待重试耗尽并自动封送死信队列（3 次重试 * 200ms）
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && orderDelayScenarioService.getCancelOrderDeadLetterSize() == 0) {
            Thread.sleep(100);
        }

        int deadLetterSize = orderDelayScenarioService.getCancelOrderDeadLetterSize();
        assertThat(deadLetterSize).isGreaterThanOrEqualTo(1);

        DeadLetterMessage<OrderDelayPayload> deadLetter = orderDelayScenarioService.pollCancelOrderDeadLetter();
        assertThat(deadLetter).isNotNull();
        assertThat(deadLetter.payload().getOrderId()).isEqualTo("POISON_ORD_DLQ_002");
        assertThat(deadLetter.retryTimes()).isEqualTo(3);
        assertThat(deadLetter.errorMessage()).contains("毒丸订单触发模拟异常");
    }

    // =========================================================================
    // 场景 3: StreamEventScenarioService (Redis 5.0+ Stream 可靠消息流)
    // =========================================================================

    @Test
    @DisplayName("场景3：Stream 消息发送、消费组监听消费、长度查询与队列裁剪")
    void testStreamEventProduceAndMetrics() throws Exception {
        streamEventScenarioService.reset(1);

        OrderStreamEvent event = OrderStreamEvent.builder()
                .orderId("ORD_STREAM_101")
                .amount(new BigDecimal("199.00"))
                .eventType("CREATED")
                .timestamp(System.currentTimeMillis())
                .build();

        RecordId recordId = streamEventScenarioService.sendOrderEvent(event);
        assertThat(recordId).isNotNull();
        assertThat(recordId.getValue()).isNotBlank();

        // 验证 Stream 消费组监听容器正常接收到事件并执行消费
        boolean consumed = streamEventScenarioService.getLatch().await(5, TimeUnit.SECONDS);
        assertThat(consumed).isTrue();
        assertThat(streamEventScenarioService.getReceivedEvents()).isNotEmpty();
        assertThat(streamEventScenarioService.getReceivedEvents().get(0).getOrderId()).isEqualTo("ORD_STREAM_101");

        Long size = streamEventScenarioService.getStreamSize();
        assertThat(size).isNotNull().isGreaterThanOrEqualTo(1L);

        Long trimmed = streamEventScenarioService.trimStream(100);
        assertThat(trimmed).isNotNull().isGreaterThanOrEqualTo(0L);
    }

    // =========================================================================
    // 场景 4: IdempotentScenarioService (非 Web 业务防重幂等与重试自愈)
    // =========================================================================

    @Test
    @DisplayName("场景4：声明式 @RedisIdempotent 首次成功、重复拦截与业务失败释放凭据重试")
    void testIdempotentExecutionAndRetryOnFailure() {
        // 1. 首次执行成功，凭据被记录
        String result = idempotentScenarioService.processOrder("ORD_IDEMP_001", false);
        assertThat(result).isEqualTo("SUCCESS_ORD_IDEMP_001");
        assertThat(idempotentScenarioService.getSuccessProcessCount()).isEqualTo(1);

        // 2. 相同订单重复提交，被防重切面拦截抛出业务异常
        assertThatThrownBy(() -> idempotentScenarioService.processOrder("ORD_IDEMP_001", false))
                .isInstanceOf(BusinessException.class);
        assertThat(idempotentScenarioService.getSuccessProcessCount()).isEqualTo(1);

        // 3. 模拟业务失败 (deleteOnFailure = true)，凭据被主动删除
        assertThatThrownBy(() -> idempotentScenarioService.processOrder("ORD_IDEMP_FAIL_002", true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模拟业务处理失败");

        // 4. 由于凭据已被释放，允许重试执行成功
        String retryResult = idempotentScenarioService.processOrder("ORD_IDEMP_FAIL_002", false);
        assertThat(retryResult).isEqualTo("SUCCESS_ORD_IDEMP_FAIL_002");
    }

    @Test
    @DisplayName("场景4：编程式与静态门面防重幂等业务核销执行")
    void testProgrammaticAndStaticIdempotent() {
        String payResult = idempotentScenarioService.executeProgrammaticPayment(
                "PAY_20261027_001", new BigDecimal("88.88"));
        assertThat(payResult).isEqualTo("PAID_PAY_20261027_001");

        String refundResult = idempotentScenarioService.executeStaticRefund(
                "REF_20261027_002", new BigDecimal("20.00"));
        assertThat(refundResult).isEqualTo("REFUNDED_REF_20261027_002");
    }

    // =========================================================================
    // 场景 5: SemaphoreThrottlingScenarioService (分布式信号量并发度治理)
    // =========================================================================

    @Test
    @DisplayName("场景5：受管信号量并发度治理正常放行与超时优雅降级")
    void testSemaphoreExecutionAndTimeoutFallback() {
        // 正常获取许可放行
        String normalResult = semaphoreThrottlingScenarioService.callHeavyDownstream(
                "payment-gateway", 10, Duration.ofMillis(500));
        assertThat(normalResult).isEqualTo("SUCCESS_DOWNSTREAM_payment-gateway");

        // 模拟争锁超时触发降级：在真实 Redis 模式下预先耗尽 1 个配额
        RSemaphore sem = null;
        if (isLocalProfileActive() && redissonClient != null) {
            try {
                sem = redissonClient.getSemaphore("sem:heavy:timeout-gateway");
                sem.trySetPermits(1);
                sem.tryAcquire(1);
            } catch (Exception ex) {
                log.warn("[SampleRedisIntegrationTest] 信号量配额预占异常: {}", ex.getMessage());
            }
        }

        try {
            String fallbackResult = semaphoreThrottlingScenarioService.callHeavyDownstream(
                    "timeout-gateway", 1, Duration.ofMillis(100));
            assertThat(fallbackResult).isEqualTo("FALLBACK_DOWNSTREAM_timeout-gateway");
        } finally {
            if (sem != null) {
                try {
                    sem.release(1);
                } catch (Exception ex) {
                    log.warn("[SampleRedisIntegrationTest] 信号量释放异常: {}", ex.getMessage());
                }
            }
        }
    }

    @Test
    @DisplayName("场景5：静态工具门面执行与动态配额调配")
    void testSemaphoreStaticAndDynamicPermits() {
        String staticResult = semaphoreThrottlingScenarioService.callWithStaticUtil(
                "sms-service", 5, Duration.ofMillis(200));
        assertThat(staticResult).isEqualTo("STATIC_SUCCESS_sms-service");

        semaphoreThrottlingScenarioService.resetPermits("sms-service", 20);
        int available = semaphoreThrottlingScenarioService.getAvailablePermits("sms-service");
        assertThat(available).isGreaterThan(0);
    }

    // =========================================================================
    // 场景 6: ScriptExecutionScenarioService (受管 Lua 脚本原子执行与对称原语)
    // =========================================================================

    @Test
    @DisplayName("场景6：受管内联 Lua 脚本原子执行与 CAS / GetAndSet 对称原子原语")
    void testScriptExecutionAndAtomicPrimitives() {
        // 内联脚本执行
        Long scriptResult = scriptExecutionScenarioService.executeInlineScript(
                "return 1", Long.class, List.of("key1"));
        assertThat(scriptResult).isEqualTo(1L);

        // CAS 原子比较替换（支持 expect 为 null 初始化置换与状态递进置换）
        String casKey = "atomic:cas:order1001";
        boolean initCas = scriptExecutionScenarioService.compareAndSet(casKey, null, 10L);
        assertThat(initCas).isTrue();
        boolean casResult = scriptExecutionScenarioService.compareAndSet(casKey, 10L, 20L);
        assertThat(casResult).isTrue();

        // GetAndSet 对称原子更新
        Long prev = scriptExecutionScenarioService.getAndSet("atomic:status", 100L);
        assertThat(scriptExecutionScenarioService.getAndSet("atomic:status", 200L)).isEqualTo(100L);

        // 按日重置结构化原子流水号
        String seq = scriptExecutionScenarioService.generateDailyOrderNo("PAY", 6);
        assertThat(seq).isNotBlank();

        // ZSet 毫秒滑动窗口严格频控校验
        boolean allowed = scriptExecutionScenarioService.checkSlidingWindow(
                "rate:sliding:user100", 5, Duration.ofSeconds(1));
        assertThat(allowed).isTrue();

        // RedisUtil 安全游标扫描
        if (redisTemplate != null && redisTemplate.opsForValue() != null) {
            try {
                redisTemplate.opsForValue().set("sample:user:1001", "test1");
            } catch (Exception ex) {
                log.warn("[SampleRedisIntegrationTest] 写入测试键异常: {}", ex.getMessage());
            }
        }
        Set<String> matchedKeys = scriptExecutionScenarioService.scanKeys("sample:user:*", 100);
        assertThat(matchedKeys).isNotEmpty();
    }

    // =========================================================================
    // 场景 7: NoticeBroadcastScenarioService (Pub/Sub 主题广播与监听)
    // =========================================================================

    @Test
    @DisplayName("场景7：Pub/Sub 精确主题公告广播与通配模式主题事件监听")
    void testNoticeBroadcastAndPatternSubscription() throws Exception {
        noticeBroadcastScenarioService.reset(1, 1);
        TimeUnit.MILLISECONDS.sleep(50);

        // 1. 精确主题广播公告
        NoticeBroadcastPayload payload = new NoticeBroadcastPayload("NOT_001", "系统升级维护公告", "系统将于凌晨02:00维护升级", System.currentTimeMillis());
        Long clients = noticeBroadcastScenarioService.publishNotice(payload);
        assertThat(clients).isGreaterThanOrEqualTo(0L);

        boolean noticeReceived = noticeBroadcastScenarioService.getNoticeLatch().await(5, TimeUnit.SECONDS);
        assertThat(noticeReceived).isTrue();
        assertThat(noticeBroadcastScenarioService.getReceivedNotices()).hasSize(1);
        assertThat(noticeBroadcastScenarioService.getReceivedNotices().get(0).getNoticeId()).isEqualTo("NOT_001");

        // 2. 通配模式主题广播事件
        Long eventClients = noticeBroadcastScenarioService.publishSystemEvent("config_refresh", "CONFIG_V2");
        assertThat(eventClients).isGreaterThanOrEqualTo(0L);

        boolean eventReceived = noticeBroadcastScenarioService.getEventLatch().await(5, TimeUnit.SECONDS);
        assertThat(eventReceived).isTrue();
        assertThat(noticeBroadcastScenarioService.getReceivedSystemEvents()).hasSize(1);
        String receivedEvent = noticeBroadcastScenarioService.getReceivedSystemEvents().get(0);
        assertThat(receivedEvent).contains("config_refresh").contains("CONFIG_V2");
    }

    // =========================================================================
    // 场景 8: CacheScenarioService (两级缓存、防穿透/击穿/N+1 与布隆过滤器)
    // =========================================================================

    @Test
    @DisplayName("场景8：RedisCacheTemplate 单商品冷启动查库、缓存命中、空哨兵防穿透与主动驱逐")
    void testCacheTemplateGetHitPenetrationAndEvict() {
        // 1. 初次查询，回源查库 (DB count = 1)
        ProductVO product = cacheScenarioService.getProduct(1001L);
        assertThat(product).isNotNull();
        assertThat(product.getName()).isEqualTo("iPhone 16 Pro");
        assertThat(cacheScenarioService.getDbQueryCount()).isEqualTo(1);

        // 2. 第二次查询，命中缓存，不查库 (DB count 依然为 1)
        ProductVO cached = cacheScenarioService.getProduct(1001L);
        assertThat(cached).isNotNull();
        assertThat(cached.getName()).isEqualTo("iPhone 16 Pro");
        assertThat(cacheScenarioService.getDbQueryCount()).isEqualTo(1);

        // 3. 主动驱逐缓存
        cacheScenarioService.evictProduct(1001L);

        // 4. 再次查询，重新查库 (DB count 递增至 2)
        ProductVO reloaded = cacheScenarioService.getProduct(1001L);
        assertThat(reloaded).isNotNull();
        assertThat(cacheScenarioService.getDbQueryCount()).isEqualTo(2);

        // 5. 查询不存在的商品，写入短 TTL 空哨兵，不穿透
        ProductVO nullProduct = cacheScenarioService.getProduct(9999L);
        assertThat(nullProduct).isNull();
        assertThat(cacheScenarioService.getDbQueryCount()).isEqualTo(3);

        // 再次查询不存在的商品，命中空哨兵，直接返回 null，不触发 DB
        ProductVO nullCached = cacheScenarioService.getProduct(9999L);
        assertThat(nullCached).isNull();
        assertThat(cacheScenarioService.getDbQueryCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("场景8：RedisCacheTemplate 批量商品查询 MGET + 批量加载消除 N+1")
    void testCacheTemplateBatchGetAntiNPlusOne() {
        cacheScenarioService.resetDbQueryCount();

        // 批量查询 [1001, 1002, 9999]，仅触发 1 次批量 DB 查询
        Map<Long, ProductVO> products = cacheScenarioService.batchGetProducts(List.of(1001L, 1002L, 9999L));
        assertThat(products).containsKey(1001L);
        assertThat(products).containsKey(1002L);
        assertThat(products).doesNotContainKey(9999L);
        assertThat(cacheScenarioService.getDbQueryCount()).isEqualTo(1);

        // 再次批量查询相同列表，全部命中缓存与空哨兵，0 次 DB 查询
        Map<Long, ProductVO> cachedMap = cacheScenarioService.batchGetProducts(List.of(1001L, 1002L, 9999L));
        assertThat(cachedMap).containsKey(1001L);
        assertThat(cachedMap).containsKey(1002L);
        assertThat(cacheScenarioService.getDbQueryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("场景8：Spring Cache @Cacheable 两级缓存冷启动查库、二次极速命中与 @CacheEvict 淘汰")
    void testSpringCacheTwoLevelAnnotations() {
        cacheScenarioService.resetDbQueryCount();

        // 首次调用查库 (DB count = 1)
        String profile1 = cacheScenarioService.getUserProfile(2001L);
        assertThat(profile1).isEqualTo("Role_VIP_2001");
        assertThat(cacheScenarioService.getDbQueryCount()).isEqualTo(1);

        // 第二次调用直接命中 L1 内存 (DB count 依然为 1)
        String profile2 = cacheScenarioService.getUserProfile(2001L);
        assertThat(profile2).isEqualTo("Role_VIP_2001");
        assertThat(cacheScenarioService.getDbQueryCount()).isEqualTo(1);

        // 淘汰缓存
        cacheScenarioService.evictUserProfile(2001L);

        // 重新调用回源查库 (DB count 递增至 2)
        String profile3 = cacheScenarioService.getUserProfile(2001L);
        assertThat(profile3).isEqualTo("Role_VIP_2001");
        assertThat(cacheScenarioService.getDbQueryCount()).isEqualTo(2);

        // 全量清空
        cacheScenarioService.clearUserProfileCache();
        String profile4 = cacheScenarioService.getUserProfile(2001L);
        assertThat(profile4).isEqualTo("Role_VIP_2001");
        assertThat(cacheScenarioService.getDbQueryCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("场景8：分布式布隆过滤器添加、存在性判定与监控指标获取")
    void testBloomFilterOperations() {
        boolean added = cacheScenarioService.addBlacklist("user_black_101");
        assertThat(added).isTrue();

        boolean inBlacklist = cacheScenarioService.checkBlacklist("user_black_101");
        assertThat(inBlacklist).isTrue();

        boolean notInBlacklist = cacheScenarioService.checkBlacklist("user_clean_202");
        assertThat(notInBlacklist).isFalse();

        BloomFilterMetricsVO metrics = cacheScenarioService.getBlacklistMetrics();
        assertThat(metrics).isNotNull();
        assertThat(metrics.getFilterName()).isEqualTo("user-blacklist");
        assertThat(metrics.getExpectedInsertions()).isEqualTo(50_000L);
    }

    // =========================================================================
    // 底座原语与上下文装配验证
    // =========================================================================

    @Test
    @DisplayName("底座验证：全局 KeyPrefix 隔离与 RedisUtil.buildKey 辅助门面构建")
    void testKeyPrefixAndBuildKey() {
        String key = RedisUtil.buildKey("order", "20261027");
        assertThat(key).isEqualTo("order:20261027");

        RedisProperties customProps = new RedisProperties();
        customProps.setKeyPrefix("dev:");
        assertThat(customProps.buildKey("order:1001")).isEqualTo("dev:order:1001");
        assertThat(customProps.buildKey("dev:order:1001")).isEqualTo("dev:order:1001");
    }

    @Test
    @DisplayName("底座验证：Starter 核心依赖与双核管理器 100% 成功装配")
    void testInfrastructureBeansInjected() {
        assertThat(distributedLockTemplate).isNotNull();
        assertThat(redisDelayedQueue).isNotNull();
        assertThat(redisStreamTemplate).isNotNull();
        assertThat(redisIdempotentTemplate).isNotNull();
        assertThat(redisSemaphoreTemplate).isNotNull();
        assertThat(redisScriptExecutor).isNotNull();
        assertThat(redisMessagePublisher).isNotNull();
        assertThat(redisCacheTemplate).isNotNull();
        assertThat(twoLevelCacheManager).isNotNull();
        assertThat(redisBloomFilter).isNotNull();
        assertThat(redisAtomicUtil).isNotNull();
    }

    // =========================================================================
    // 控制器实测: RedisOpsAndScriptController (/sample/redis/ops/**)
    // =========================================================================

    @Test
    @DisplayName("控制器端点：/sample/redis/ops/cart 购物车 Hash 明细添加、查询与移除")
    void testRedisOpsCartEndpoints() throws Exception {
        CartItemRequest request = CartItemRequest.builder()
                .userId("user_1001")
                .skuId("SKU_IPHONE_16")
                .skuName("iPhone 16 Pro")
                .quantity(2)
                .price(new BigDecimal("7999.00"))
                .build();

        // 1. 添加商品到购物车
        mockMvc.perform(post("/sample/redis/ops/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value("user_1001"))
                .andExpect(jsonPath("$.data.items[0].skuId").value("SKU_IPHONE_16"))
                .andExpect(jsonPath("$.data.totalQuantity").value(2))
                .andExpect(jsonPath("$.data.totalPrice").value(15998.00));

        // 2. 校验参数防御：非法入参 Fail-Fast
        CartItemRequest invalidReq = CartItemRequest.builder()
                .userId("")
                .skuId("")
                .quantity(0)
                .price(BigDecimal.ZERO)
                .build();
        mockMvc.perform(post("/sample/redis/ops/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)));

        // 3. 查询购物车
        mockMvc.perform(get("/sample/redis/ops/cart/user_1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[0].skuName").value("iPhone 16 Pro"));

        // 4. 移除商品
        mockMvc.perform(delete("/sample/redis/ops/cart/user_1001/items/SKU_IPHONE_16"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("控制器端点：/sample/redis/ops/rank 热销榜 ZSet 累加与 TopN 查询")
    void testRedisOpsRankEndpoints() throws Exception {
        ProductScoreRequest req1 = ProductScoreRequest.builder()
                .productId("PROD_101")
                .productName("华为 Mate 70")
                .deltaScore(88.5)
                .build();
        ProductScoreRequest req2 = ProductScoreRequest.builder()
                .productId("PROD_102")
                .productName("小米 15 Ultra")
                .deltaScore(95.0)
                .build();

        mockMvc.perform(post("/sample/redis/ops/rank/score")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/sample/redis/ops/rank/score")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/sample/redis/ops/rank/top").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].productId").value("PROD_102"))
                .andExpect(jsonPath("$.data[0].score").value(95.0))
                .andExpect(jsonPath("$.data[1].productId").value("PROD_101"))
                .andExpect(jsonPath("$.data[1].score").value(88.5));
    }

    @Test
    @DisplayName("控制器端点：/sample/redis/ops/keys/scan 游标安全扫描巡检")
    void testRedisOpsScanKeysEndpoint() throws Exception {
        mockMvc.perform(get("/sample/redis/ops/keys/scan")
                        .param("pattern", "sample:*")
                        .param("count", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.pattern").value("sample:*"));
    }

    @Test
    @DisplayName("控制器端点：/sample/redis/ops/pipeline/warmup Pipeline 批量预热")
    void testRedisOpsPipelineWarmupEndpoint() throws Exception {
        PipelineWarmupRequest request = PipelineWarmupRequest.builder()
                .stockMap(Map.of(
                        "SKU_A", 100,
                        "SKU_B", 200,
                        "SKU_C", 300
                ))
                .build();

        mockMvc.perform(post("/sample/redis/ops/pipeline/warmup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalItems").value(3))
                .andExpect(jsonPath("$.data.success").value(true));
    }

    @Test
    @DisplayName("控制器端点：/sample/redis/ops/coupon/deduct Lua 优惠券原子核销")
    void testRedisOpsCouponDeductEndpoint() throws Exception {
        CouponDeductRequest request = CouponDeductRequest.builder()
                .couponId("COUPON_NEW_USER")
                .deductCount(1)
                .build();

        mockMvc.perform(post("/sample/redis/ops/coupon/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.couponId").value("COUPON_NEW_USER"))
                .andExpect(jsonPath("$.data.success").value(true));
    }

    @Test
    @DisplayName("控制器端点：/sample/redis/ops/seq/daily 跨天自重置流水号生成")
    void testRedisOpsDailySeqEndpoint() throws Exception {
        mockMvc.perform(post("/sample/redis/ops/seq/daily")
                        .param("prefix", "ORD")
                        .param("width", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.prefix").value("ORD"))
                .andExpect(jsonPath("$.data.fullOrderNo").isString());
    }

    @Test
    @DisplayName("控制器端点：/sample/redis/ops/watermark/cas 水位原子比较置换")
    void testRedisOpsWatermarkCasEndpoint() throws Exception {
        WatermarkCasRequest request = WatermarkCasRequest.builder()
                .key("sample:watermark:sync")
                .expect(0L)
                .update(100L)
                .build();

        mockMvc.perform(post("/sample/redis/ops/watermark/cas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.key").value("sample:watermark:sync"));
    }

    // =========================================================================
    // 控制器端点集成测试: RedisLockController
    // =========================================================================

    @Test
    @DisplayName("控制器端点：/sample/redis/lock/deduct-stock 秒杀减库存")
    void testRedisLockDeductStockEndpoint() throws Exception {
        StockDeductRequest request = StockDeductRequest.builder()
                .productId(1001L)
                .count(10)
                .build();

        mockMvc.perform(post("/sample/redis/lock/deduct-stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.productId").value(1001))
                .andExpect(jsonPath("$.data.success").value(true));
    }

    @Test
    @DisplayName("控制器端点：/sample/redis/lock/order-settle-reentrant 跨服务嵌套重入锁结算")
    void testRedisLockOrderSettleReentrantEndpoint() throws Exception {
        OrderSettleRequest request = OrderSettleRequest.builder()
                .orderId("ORD_SETTLE_2001")
                .productId(1001L)
                .quantity(5)
                .build();

        mockMvc.perform(post("/sample/redis/lock/order-settle-reentrant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value("ORD_SETTLE_2001"))
                .andExpect(jsonPath("$.data.status").value("SETTLED"));
    }

    @Test
    @DisplayName("控制器端点：/sample/redis/lock/promotion-rule 读写锁更新与查询")
    void testRedisLockPromotionRuleReadWriteEndpoint() throws Exception {
        PromotionRuleUpdateRequest updateRequest = PromotionRuleUpdateRequest.builder()
                .ruleId("PROMO_88")
                .ruleName("双十一八折券")
                .discountRate(new BigDecimal("0.80"))
                .build();

        mockMvc.perform(post("/sample/redis/lock/promotion-rule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.ruleId").value("PROMO_88"));

        mockMvc.perform(get("/sample/redis/lock/promotion-rule/PROMO_88"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.ruleId").value("PROMO_88"))
                .andExpect(jsonPath("$.data.ruleName").value("双十一八折券"));
    }

    @Test
    @DisplayName("控制器端点：/sample/redis/lock/fair-quota/acquire 公平锁抢占稀缺名额")
    void testRedisLockFairQuotaEndpoint() throws Exception {
        FairQuotaRequest request = FairQuotaRequest.builder()
                .activityId("ACT_FAIR_99")
                .userId("USER_FAIR_01")
                .build();

        mockMvc.perform(post("/sample/redis/lock/fair-quota/acquire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.acquired").value(true));
    }

    @Test
    @DisplayName("控制器端点：/sample/redis/lock/multi-sku/deduct 组合套餐联锁 MultiLock")
    void testRedisLockMultiSkuDeductEndpoint() throws Exception {
        MultiSkuDeductRequest request = MultiSkuDeductRequest.builder()
                .comboId("COMBO_PHONE_WATCH")
                .productIds(List.of(1001L, 1002L))
                .quantity(1)
                .build();

        mockMvc.perform(post("/sample/redis/lock/multi-sku/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.comboId").value("COMBO_PHONE_WATCH"))
                .andExpect(jsonPath("$.data.success").value(true));
    }

    @Test
    @DisplayName("控制器端点：/sample/redis/lock/try-acquire-fallback 争锁超时排队降级")
    void testRedisLockFallbackEndpoint() throws Exception {
        LockFallbackRequest request = LockFallbackRequest.builder()
                .resourceId("fallback_res_api")
                .build();

        mockMvc.perform(post("/sample/redis/lock/try-acquire-fallback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.resourceId").value("fallback_res_api"));
    }

    @Test
    @DisplayName("控制器端点：/sample/redis/lock/static-lock RedisLockUtil 静态加锁")
    void testRedisLockStaticEndpoint() throws Exception {
        StaticLockRequest request = StaticLockRequest.builder()
                .resourceId("sync:report:001")
                .build();

        mockMvc.perform(post("/sample/redis/lock/static-lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.resourceId").value("sync:report:001"))
                .andExpect(jsonPath("$.data.locked").value(true));
    }

    // =========================================================================
    // 控制器端点集成测试: RedisIdempotentController
    // =========================================================================

    @Test
    @DisplayName("控制器端点：/sample/redis/idempotent/payment/notify 异步支付通知防重入账")
    void testRedisIdempotentPaymentNotifyEndpoint() throws Exception {
        PaymentCallbackRequest request = PaymentCallbackRequest.builder()
                .paymentNo("PAY_NOTIFY_1001")
                .orderId("ORD_NOTIFY_1001")
                .amount(new BigDecimal("99.00"))
                .payStatus("SUCCESS")
                .build();

        mockMvc.perform(post("/sample/redis/idempotent/payment/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.paymentNo").value("PAY_NOTIFY_1001"))
                .andExpect(jsonPath("$.data.processed").value(true));
    }

    @Test
    @DisplayName("控制器端点：/sample/redis/idempotent/payment/simulate-failure-retry 失败自动自愈二次重试")
    void testRedisIdempotentFailureRetryEndpoint() throws Exception {
        PaymentFailureRetryRequest request = PaymentFailureRetryRequest.builder()
                .paymentNo("PAY_RETRY_1002")
                .amount(new BigDecimal("199.00"))
                .shouldFail(false)
                .build();

        mockMvc.perform(post("/sample/redis/idempotent/payment/simulate-failure-retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.paymentNo").value("PAY_RETRY_1002"))
                .andExpect(jsonPath("$.data.processed").value(true));
    }

    @Test
    @DisplayName("控制器端点：/sample/redis/idempotent/refund/template & static 编程式与静态退款防重")
    void testRedisIdempotentRefundEndpoints() throws Exception {
        RefundRequest tplRequest = RefundRequest.builder()
                .refundNo("REFUND_TPL_001")
                .refundAmount(new BigDecimal("50.00"))
                .reason("协商一致退款")
                .build();

        mockMvc.perform(post("/sample/redis/idempotent/refund/template")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tplRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.refundNo").value("REFUND_TPL_001"))
                .andExpect(jsonPath("$.data.success").value(true));

        RefundRequest staRequest = RefundRequest.builder()
                .refundNo("REFUND_STA_002")
                .refundAmount(new BigDecimal("80.00"))
                .reason("多付退款")
                .build();

        mockMvc.perform(post("/sample/redis/idempotent/refund/static")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(staRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.refundNo").value("REFUND_STA_002"))
                .andExpect(jsonPath("$.data.success").value(true));
    }

    // =========================================================================
    // 控制器端点集成测试: RedisTrafficController
    // =========================================================================

    @Test
    @DisplayName("控制器端点：/sample/redis/traffic/semaphore 征信并发槽位管控与配额调配")
    void testRedisTrafficSemaphoreEndpoints() throws Exception {
        CreditCheckRequest request = CreditCheckRequest.builder()
                .idCard("110101199003072345")
                .customerName("张三")
                .build();

        mockMvc.perform(post("/sample/redis/traffic/semaphore/credit-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.customerName").value("张三"))
                .andExpect(jsonPath("$.data.creditScore").value(750));

        mockMvc.perform(post("/sample/redis/traffic/semaphore/static-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.customerName").value("张三"))
                .andExpect(jsonPath("$.data.creditScore").value(780));

        SemaphoreResetRequest resetRequest = SemaphoreResetRequest.builder()
                .semaphoreName("sem:heavy:credit-eval")
                .newPermits(20)
                .build();

        mockMvc.perform(post("/sample/redis/traffic/semaphore/permits/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(get("/sample/redis/traffic/semaphore/permits/credit-eval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.currentPermits").isNumber());
    }

    @Test
    @DisplayName("控制器端点：/sample/redis/traffic/rate-limit 令牌桶限流与滑动窗口防刷")
    void testRedisTrafficRateLimitEndpoints() throws Exception {
        TokenBucketRateLimitRequest tbRequest = TokenBucketRateLimitRequest.builder()
                .apiKey("api:gateway:order")
                .rate(10L)
                .intervalSeconds(1L)
                .build();

        mockMvc.perform(post("/sample/redis/traffic/rate-limit/token-bucket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tbRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allowed").value(true));

        TokenBucketResetRequest tbReset = TokenBucketResetRequest.builder()
                .apiKey("api:gateway:order")
                .build();

        mockMvc.perform(post("/sample/redis/traffic/rate-limit/token-bucket/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tbReset)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        SmsRateLimitRequest smsRequest = SmsRateLimitRequest.builder()
                .phoneNumber("13800138000")
                .windowSeconds(60)
                .maxAttempts(5)
                .build();

        mockMvc.perform(post("/sample/redis/traffic/rate-limit/sms-sliding-window")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(smsRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allowed").value(true));
    }

    // =========================================================================
    // 控制器端点集成测试: RedisCacheController
    // =========================================================================

    @Test
    @DisplayName("控制器端点：/sample/redis/cache 单级字典、商品安全缓存、两级用户档案与布隆黑名单")
    void testRedisCacheEndpoints() throws Exception {
        // 1. 单级字典查询与驱逐
        mockMvc.perform(get("/sample/redis/cache/dict/GENDER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.dictCode").value("GENDER"));

        mockMvc.perform(delete("/sample/redis/cache/dict/GENDER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 2. 单商品与批量商品
        mockMvc.perform(get("/sample/redis/cache/product/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.productId").value(1001))
                .andExpect(jsonPath("$.data.name").value("iPhone 16 Pro"));

        mockMvc.perform(post("/sample/redis/cache/products/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(1001L, 1002L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data['1001'].name").value("iPhone 16 Pro"));

        mockMvc.perform(delete("/sample/redis/cache/product/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 3. 用户档案两级缓存
        mockMvc.perform(get("/sample/redis/cache/user-profile/3001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value(3001))
                .andExpect(jsonPath("$.data.membershipTier").value("VIP"));

        mockMvc.perform(delete("/sample/redis/cache/user-profile/3001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 4. 布隆黑名单
        mockMvc.perform(post("/sample/redis/cache/blacklist/risk_user_99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(get("/sample/redis/cache/blacklist/check/risk_user_99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(get("/sample/redis/cache/blacklist/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.filterName").value("user-blacklist"));
    }

    // =========================================================================
    // 控制器端点集成测试: RedisDelayQueueController
    // =========================================================================

    @Test
    @DisplayName("控制器端点：/sample/redis/delay-queue 订单延时投递、到期消费、毒丸重试死信与告警快照")
    void testRedisDelayQueueEndpoints() throws Exception {
        // 1. 投递未支付订单延时取消任务
        OrderDelaySendRequest sendReq = OrderDelaySendRequest.builder()
                .orderId("ORD_DELAY_CTRL_001")
                .delaySeconds(1L)
                .reason("超时未支付自动关单")
                .build();

        mockMvc.perform(post("/sample/redis/delay-queue/order/cancel/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sendReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value("ORD_DELAY_CTRL_001"))
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"));

        // 2. 查询已消费列表
        mockMvc.perform(get("/sample/redis/delay-queue/order/cancel/consumed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 3. 投递毒丸演练订单
        mockMvc.perform(post("/sample/redis/delay-queue/order/cancel/send-poison")
                        .param("orderId", "POISON_ORD_CTRL_002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsString("POISON_ORD_CTRL_002")));

        // 等待重试耗尽并自动封送死信队列（3 次重试 * 200ms）
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && orderDelayScenarioService.getCancelOrderDeadLetterSize() == 0) {
            Thread.sleep(100);
        }

        // 4. 查询死信积压统计
        mockMvc.perform(get("/sample/redis/delay-queue/dead-letter/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.deadLetterCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // 4.1 批量非破坏性检视死信（验证 /sample/redis/delay/dlq/peek 端点）
        mockMvc.perform(get("/sample/redis/delay/dlq/peek").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].payload.orderId").value("POISON_ORD_CTRL_002"))
                .andExpect(jsonPath("$.data[0].replayCount").value(0));

        // 4.2 受控重放死信回原延时队列（验证 /sample/redis/delay/dlq/replay 端点）
        mockMvc.perform(post("/sample/redis/delay/dlq/replay")
                        .param("count", "1")
                        .param("delaySeconds", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(1));

        // 等待重放后的毒丸再次消费重试耗尽回到死信队列（replayCount = 1）
        long replayDeadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < replayDeadline && orderDelayScenarioService.getCancelOrderDeadLetterSize() == 0) {
            Thread.sleep(100);
        }

        // 4.3 再次检视确认死信重放次数已自增为 1
        mockMvc.perform(get("/sample/redis/delay/dlq/peek").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].payload.orderId").value("POISON_ORD_CTRL_002"))
                .andExpect(jsonPath("$.data[0].replayCount").value(1));

        // 5. 拉取死信消息
        mockMvc.perform(get("/sample/redis/delay-queue/dead-letter/poll"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.payload.orderId").value("POISON_ORD_CTRL_002"))
                .andExpect(jsonPath("$.data.retryTimes").value(3));

        // 6. 查询 SPI 告警快照
        mockMvc.perform(get("/sample/redis/delay-queue/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 7. 清空死信队列
        mockMvc.perform(delete("/sample/redis/delay-queue/dead-letter/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    // =========================================================================
    // 控制器端点集成测试: RedisStreamController
    // =========================================================================

    @Test
    @DisplayName("控制器端点：/sample/redis/stream 模板与静态投递、PEL 待决探查、XCLAIM、XTRIM、毒丸死信与告警快照")
    void testRedisStreamEndpoints() throws Exception {
        // 1. 模板门面投递订单流事件
        OrderStreamSendRequest streamReq1 = OrderStreamSendRequest.builder()
                .orderId("ORD_STREAM_CTRL_001")
                .eventType("ORDER_CREATED")
                .amount(new BigDecimal("199.99"))
                .build();

        mockMvc.perform(post("/sample/redis/stream/events/template")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(streamReq1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value("ORD_STREAM_CTRL_001"))
                .andExpect(jsonPath("$.data.recordId").isNotEmpty());

        // 2. 静态门面投递订单流事件
        OrderStreamSendRequest streamReq2 = OrderStreamSendRequest.builder()
                .orderId("ORD_STREAM_CTRL_002")
                .eventType("ORDER_PAID")
                .amount(new BigDecimal("88.00"))
                .build();

        mockMvc.perform(post("/sample/redis/stream/events/static")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(streamReq2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value("ORD_STREAM_CTRL_002"))
                .andExpect(jsonPath("$.data.recordId").isNotEmpty());

        // 3. 查询已消费事件
        mockMvc.perform(get("/sample/redis/stream/consumed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 4. 查询待决消息概要 (XPENDING summary)
        mockMvc.perform(get("/sample/redis/stream/pending/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.stream").value("stream:order:events"));

        // 5. 查询待决消息明细 (XPENDING detail)
        mockMvc.perform(get("/sample/redis/stream/pending/messages")
                        .param("count", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 6. 认领孤儿待决消息 (XCLAIM)
        StreamClaimRequest claimReq = StreamClaimRequest.builder()
                .stream("stream:order:events")
                .group("group:order:processor")
                .newConsumer("consumer-standby-01")
                .minIdleSeconds(0L)
                .recordIds(List.of("1700000000000-0"))
                .build();

        mockMvc.perform(post("/sample/redis/stream/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(claimReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 7. 定长裁剪 Stream 长度 (XTRIM)
        StreamTrimRequest trimReq = StreamTrimRequest.builder()
                .stream("stream:order:events")
                .maxLen(100L)
                .build();

        mockMvc.perform(post("/sample/redis/stream/trim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(trimReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("0"));

        // 8. 投递毒丸 Stream 事件演练
        mockMvc.perform(post("/sample/redis/stream/events/poison")
                        .param("orderId", "POISON_STREAM_CTRL_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsString("POISON_STREAM_CTRL_001")));

        // 9. 查询死信流清单
        mockMvc.perform(get("/sample/redis/stream/dead-letter")
                        .param("count", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 10. 查询 Stream SPI 告警快照
        mockMvc.perform(get("/sample/redis/stream/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    // =========================================================================
    // 控制器端点集成测试: RedisPubSubController
    // =========================================================================

    @Test
    @DisplayName("控制器端点：/sample/redis/pubsub 精确主题全站公告广播、通配模式配置刷新与历史记录探查")
    void testRedisPubSubEndpoints() throws Exception {
        // 1. 广播全站停机维护公告
        NoticeBroadcastRequest noticeReq = NoticeBroadcastRequest.builder()
                .noticeId("NOT_CTRL_001")
                .title("全站停机维护公告")
                .content("全站将于今晚 00:00~02:00 进行停机升级")
                .level("CRITICAL")
                .build();

        mockMvc.perform(post("/sample/redis/pubsub/broadcast/notice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(noticeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.topic").value(NoticeBroadcastScenarioService.TOPIC_NOTICE_BROADCAST))
                .andExpect(jsonPath("$.data.success").value(true));

        // 2. 广播系统通配模式事件
        SystemEventBroadcastRequest eventReq = SystemEventBroadcastRequest.builder()
                .eventType("config_refresh")
                .eventData("{\"refreshKey\":\"ALL_CACHE\"}")
                .build();

        mockMvc.perform(post("/sample/redis/pubsub/broadcast/system-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(eventReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.topic").value("sample:system:config_refresh"))
                .andExpect(jsonPath("$.data.success").value(true));

        // 3. 查询已接收公告历史
        mockMvc.perform(get("/sample/redis/pubsub/history/notices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 4. 查询已接收系统事件历史
        mockMvc.perform(get("/sample/redis/pubsub/history/system-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }
}

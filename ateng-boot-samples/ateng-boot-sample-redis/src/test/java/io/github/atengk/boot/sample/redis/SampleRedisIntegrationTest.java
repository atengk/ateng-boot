package io.github.atengk.boot.sample.redis;

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
import io.github.atengk.boot.sample.redis.model.BloomFilterMetricsVO;
import io.github.atengk.boot.sample.redis.model.NoticeBroadcastPayload;
import io.github.atengk.boot.sample.redis.model.OrderDelayPayload;
import io.github.atengk.boot.sample.redis.model.OrderStreamEvent;
import io.github.atengk.boot.sample.redis.model.ProductVO;
import io.github.atengk.boot.sample.redis.service.CacheScenarioService;
import io.github.atengk.boot.sample.redis.service.IdempotentScenarioService;
import io.github.atengk.boot.sample.redis.service.NoticeBroadcastScenarioService;
import io.github.atengk.boot.sample.redis.service.OrderDelayScenarioService;
import io.github.atengk.boot.sample.redis.service.ScriptExecutionScenarioService;
import io.github.atengk.boot.sample.redis.service.SemaphoreThrottlingScenarioService;
import io.github.atengk.boot.sample.redis.service.StockDeductScenarioService;
import io.github.atengk.boot.sample.redis.service.StreamEventScenarioService;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        "ateng.boot.redis.stream.enabled=true"
})
@Import({SampleRedisIntegrationTest.TestRedisConfig.class})
@DisplayName("样例工程 Redis 基础设施与分布式协同全场景端到端实测")
class SampleRedisIntegrationTest {

    @TestConfiguration
    static class TestRedisConfig {

        private final ReentrantLock jvmLock = new ReentrantLock();
        private static final Map<String, BlockingQueue<Object>> internalQueues = new ConcurrentHashMap<>();
        private static final Map<String, Object> cacheStore = new ConcurrentHashMap<>();
        private final Map<MessageListener, Collection<? extends Topic>> internalListeners = new ConcurrentHashMap<>();

        private static BlockingQueue<Object> getOrCreateQueue(String name) {
            return internalQueues.computeIfAbsent(name, k -> new LinkedBlockingQueue<>());
        }

        public static void clearAll() {
            internalQueues.values().forEach(Collection::clear);
            cacheStore.clear();
        }

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
        public org.redisson.api.RBloomFilter<Object> sampleRBloomFilter() {
            org.redisson.api.RBloomFilter<Object> filter = mock(org.redisson.api.RBloomFilter.class);
            Set<Object> set = ConcurrentHashMap.newKeySet();
            when(filter.tryInit(anyLong(), anyDouble())).thenReturn(true);
            when(filter.add((Object) any())).thenAnswer(addInv -> set.add(addInv.getArgument(0)));
            when(filter.contains((Object) any())).thenAnswer(containsInv -> set.contains(containsInv.getArgument(0)));
            when(filter.count()).thenAnswer(countInv -> (long) set.size());
            when(filter.getExpectedInsertions()).thenReturn(50_000L);
            when(filter.getFalseProbability()).thenReturn(0.01);
            when(filter.isExists()).thenReturn(true);
            when(filter.delete()).thenAnswer(delInv -> {
                set.clear();
                return true;
            });
            return filter;
        }

        @Bean
        @Primary
        @SuppressWarnings("unchecked")
        public RedissonClient redissonClient(RLock sampleRLock, org.redisson.api.RBloomFilter<Object> sampleRBloomFilter) {
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

            // 批量 MultiLock 模拟
            when(client.getMultiLock(any(RLock[].class))).thenAnswer(inv -> {
                RLock multiLock = mock(RLock.class);
                when(multiLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
                when(multiLock.isHeldByCurrentThread()).thenReturn(true);
                return multiLock;
            });

            // 限流令牌桶模拟
            RRateLimiter rateLimiter = mock(RRateLimiter.class);
            when(rateLimiter.tryAcquire(anyLong())).thenReturn(true);
            when(client.getRateLimiter(anyString())).thenReturn(rateLimiter);

            // 幂等防重 Bucket 模拟
            when(client.getBucket(anyString())).thenAnswer(inv -> {
                String bucketKey = inv.getArgument(0);
                RBucket<Object> bucket = mock(RBucket.class);
                when(bucket.setIfAbsent(any(), any(Duration.class))).thenAnswer(setInv -> {
                    if (cacheStore.containsKey(bucketKey)) {
                        return false;
                    }
                    cacheStore.put(bucketKey, setInv.getArgument(0));
                    return true;
                });
                when(bucket.setIfAbsent(any())).thenAnswer(setInv -> {
                    if (cacheStore.containsKey(bucketKey)) {
                        return false;
                    }
                    cacheStore.put(bucketKey, setInv.getArgument(0));
                    return true;
                });
                when(bucket.delete()).thenAnswer(delInv -> cacheStore.remove(bucketKey) != null);
                when(bucket.get()).thenAnswer(getInv -> cacheStore.get(bucketKey));
                return bucket;
            });

            when(client.getBucket(anyString(), any(org.redisson.client.codec.Codec.class))).thenAnswer(inv -> client.getBucket(inv.getArgument(0, String.class)));

            // 受管信号量模拟
            when(client.getSemaphore(anyString())).thenAnswer(inv -> {
                String semName = inv.getArgument(0);
                RSemaphore sem = mock(RSemaphore.class);
                boolean shouldFail = semName.contains("timeout") || semName.contains("busy");
                when(sem.tryAcquire(anyInt(), any(Duration.class))).thenReturn(!shouldFail);
                when(sem.tryAcquire(anyInt(), anyLong(), any(TimeUnit.class))).thenReturn(!shouldFail);
                when(sem.tryAcquire(anyInt())).thenReturn(!shouldFail);
                when(sem.trySetPermits(anyInt())).thenReturn(true);
                when(sem.availablePermits()).thenReturn(10);
                when(sem.delete()).thenReturn(true);
                return sem;
            });

            // 延时队列与阻塞队列模拟（按队列名独立路由隔离）
            when(client.getBlockingQueue(anyString())).thenAnswer(inv -> {
                String queueName = inv.getArgument(0);
                BlockingQueue<Object> queue = getOrCreateQueue(queueName);
                RBlockingQueue<Object> mockQueue = mock(RBlockingQueue.class);
                when(mockQueue.getName()).thenReturn(queueName);
                try {
                    when(mockQueue.poll(anyLong(), any(TimeUnit.class))).thenAnswer(pollInv -> {
                        long timeout = pollInv.getArgument(0);
                        TimeUnit unit = pollInv.getArgument(1);
                        return queue.poll(timeout, unit);
                    });
                } catch (InterruptedException ignored) {
                }
                when(mockQueue.poll()).thenAnswer(pollInv -> queue.poll());
                when(mockQueue.peek()).thenAnswer(peekInv -> queue.peek());
                when(mockQueue.size()).thenAnswer(sizeInv -> queue.size());
                when(mockQueue.offer(any())).thenAnswer(offerInv -> queue.offer(offerInv.getArgument(0)));
                return mockQueue;
            });

            when(client.getDelayedQueue(any())).thenAnswer(inv -> {
                RBlockingQueue<?> destQueue = inv.getArgument(0);
                String destName = destQueue != null ? destQueue.getName() : null;
                BlockingQueue<Object> queue = (destName != null) ? getOrCreateQueue(destName) : getOrCreateQueue("default");
                RDelayedQueue<Object> delayedQueue = mock(RDelayedQueue.class);
                doAnswer(offerInv -> {
                    Object msg = offerInv.getArgument(0);
                    queue.offer(msg);
                    return null;
                }).when(delayedQueue).offer(any(), anyLong(), any(TimeUnit.class));
                when(delayedQueue.size()).thenAnswer(sizeInv -> queue.size());
                return delayedQueue;
            });

            // 分布式布隆过滤器模拟
            when(client.getBloomFilter(anyString())).thenReturn(sampleRBloomFilter);

            return client;
        }

        @Bean
        @Primary
        public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
            RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
            doAnswer(inv -> {
                MessageListener listener = inv.getArgument(0);
                Object topicArg = inv.getArgument(1);
                if (topicArg instanceof Topic topic) {
                    internalListeners.put(listener, List.of(topic));
                } else if (topicArg instanceof Collection<?> topics) {
                    internalListeners.put(listener, (Collection<? extends Topic>) topics);
                }
                return null;
            }).when(container).addMessageListener(any(MessageListener.class), any(Collection.class));

            doAnswer(inv -> {
                MessageListener listener = inv.getArgument(0);
                Topic topic = inv.getArgument(1);
                internalListeners.put(listener, List.of(topic));
                return null;
            }).when(container).addMessageListener(any(MessageListener.class), any(Topic.class));

            doAnswer(inv -> {
                MessageListener listener = inv.getArgument(0);
                internalListeners.remove(listener);
                return null;
            }).when(container).removeMessageListener(any(MessageListener.class), any(Collection.class));

            return container;
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
            StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);

            when(template.getConnectionFactory()).thenReturn(connectionFactory);
            when(template.getStringSerializer()).thenReturn(RedisSerializer.string());
            when(template.opsForValue()).thenReturn(valOps);
            when(template.opsForZSet()).thenReturn(zSetOps);
            when(template.opsForStream()).thenReturn(streamOps);

            // Stream 基础操作模拟
            when(streamOps.createGroup(anyString(), anyString())).thenReturn("OK");
            when(streamOps.createGroup(anyString(), any(ReadOffset.class), anyString())).thenReturn("OK");
            when(streamOps.acknowledge(anyString(), anyString(), any(RecordId.class))).thenReturn(1L);
            when(streamOps.delete(anyString(), any(RecordId[].class))).thenReturn(1L);
            when(streamOps.trim(anyString(), anyLong())).thenReturn(0L);
            when(streamOps.trim(anyString(), anyLong(), any(Boolean.class))).thenReturn(0L);
            when(streamOps.size(anyString())).thenReturn(5L);
            when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("1000-0"));
            when(streamOps.add(anyString(), any(Map.class))).thenReturn(RecordId.of("1000-0"));
            when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class))).thenReturn(Collections.emptyList());

            // 缓存存取与防穿透
            when(valOps.get(anyString())).thenAnswer(inv -> cacheStore.get(inv.getArgument(0)));
            doAnswer(inv -> {
                cacheStore.put(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(valOps).set(anyString(), any(), any());
            doAnswer(inv -> {
                cacheStore.put(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(valOps).set(anyString(), any());
            when(valOps.setIfAbsent(anyString(), any(), any(Duration.class))).thenAnswer(inv -> {
                String k = inv.getArgument(0);
                if (cacheStore.containsKey(k)) {
                    return false;
                }
                cacheStore.put(k, inv.getArgument(1));
                return true;
            });
            when(valOps.multiGet(any(Collection.class))).thenAnswer(inv -> {
                Collection<String> keys = inv.getArgument(0);
                List<Object> list = new java.util.ArrayList<>();
                for (String k : keys) {
                    list.add(cacheStore.get(k));
                }
                return list;
            });

            when(template.delete(anyString())).thenAnswer(inv -> cacheStore.remove(inv.getArgument(0)) != null);
            when(template.delete(any(Collection.class))).thenAnswer(inv -> {
                Collection<?> keys = inv.getArgument(0);
                long count = 0;
                for (Object k : keys) {
                    if (cacheStore.remove(k) != null) {
                        count++;
                    }
                }
                return count;
            });
            when(template.hasKey(anyString())).thenAnswer(inv -> cacheStore.containsKey(inv.getArgument(0)));
            when(template.executePipelined(any(SessionCallback.class))).thenAnswer(inv -> {
                SessionCallback<?> cb = inv.getArgument(0);
                RedisOperations ops = mock(RedisOperations.class);
                when(ops.opsForValue()).thenReturn(valOps);
                cb.execute(ops);
                return Collections.emptyList();
            });

            // Pub/Sub 广播发送模拟与内存分发
            when(template.convertAndSend(anyString(), any())).thenAnswer(inv -> {
                String ch = inv.getArgument(0);
                Object msg = inv.getArgument(1);
                byte[] bodyBytes;
                if (msg instanceof String str) {
                    bodyBytes = str.getBytes(StandardCharsets.UTF_8);
                } else if (msg instanceof byte[] bytes) {
                    bodyBytes = bytes;
                } else {
                    bodyBytes = RedisSerializerFactory.createSafeObjectMapper().writeValueAsBytes(msg);
                }
                byte[] chBytes = ch.getBytes(StandardCharsets.UTF_8);
                DefaultMessage redisMsg = new DefaultMessage(chBytes, bodyBytes);

                internalListeners.forEach((listener, topics) -> {
                    for (Topic topic : topics) {
                        if (topic instanceof ChannelTopic ct && ct.getTopic().equals(ch)) {
                            listener.onMessage(redisMsg, null);
                            break;
                        } else if (topic instanceof PatternTopic pt) {
                            String pattern = pt.getTopic();
                            if (matchesPattern(ch, pattern)) {
                                listener.onMessage(redisMsg, pattern.getBytes(StandardCharsets.UTF_8));
                                break;
                            }
                        }
                    }
                });
                return 1L;
            });

            // 安全游标模糊扫描模拟
            when(template.scan(any(ScanOptions.class))).thenAnswer(inv -> {
                ScanOptions options = inv.getArgument(0);
                List<String> matchedKeys = new java.util.ArrayList<>();
                if (options != null && options.getPattern() != null) {
                    String pattern = options.getPattern();
                    for (String k : cacheStore.keySet()) {
                        if (matchesPattern(k, pattern)) {
                            matchedKeys.add(k);
                        }
                    }
                }
                if (matchedKeys.isEmpty()) {
                    matchedKeys.add("sample:user:1001");
                    matchedKeys.add("sample:user:1002");
                }
                java.util.Iterator<String> it = matchedKeys.iterator();
                Cursor<String> cursor = mock(Cursor.class);
                when(cursor.hasNext()).thenAnswer(i -> it.hasNext());
                when(cursor.next()).thenAnswer(i -> it.next());
                return cursor;
            });

            // 支持基于 RedisCallback 的安全游标 scan 与批量 del
            when(template.execute(any(RedisCallback.class))).thenAnswer(inv -> {
                RedisCallback<?> callback = inv.getArgument(0);
                RedisConnection conn = mock(RedisConnection.class);
                RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
                RedisStreamCommands streamCommands = mock(RedisStreamCommands.class);
                when(conn.keyCommands()).thenReturn(keyCommands);
                when(conn.streamCommands()).thenReturn(streamCommands);

                List<byte[]> matchedKeys = new java.util.ArrayList<>();
                cacheStore.keySet().forEach(k -> matchedKeys.add(k.getBytes(StandardCharsets.UTF_8)));
                java.util.Iterator<byte[]> it = matchedKeys.iterator();

                Cursor<byte[]> cursor = mock(Cursor.class);
                when(cursor.hasNext()).thenAnswer(i -> it.hasNext());
                when(cursor.next()).thenAnswer(i -> it.next());
                when(keyCommands.scan(any(ScanOptions.class))).thenReturn(cursor);

                doAnswer(delInv -> {
                    Object[] args = delInv.getArguments();
                    for (Object arg : args) {
                        if (arg instanceof byte[] b) {
                            cacheStore.remove(new String(b, StandardCharsets.UTF_8));
                        } else if (arg instanceof byte[][] bArr) {
                            for (byte[] b : bArr) {
                                cacheStore.remove(new String(b, StandardCharsets.UTF_8));
                            }
                        }
                    }
                    return (long) args.length;
                }).when(keyCommands).del(any(byte[][].class));

                return callback.doInRedis(conn);
            });

            // RedisAtomicUtil & RedisScriptExecutor Lua 脚本模拟
            when(template.execute(
                    any(RedisScript.class),
                    any(RedisSerializer.class),
                    any(),
                    any(List.class),
                    any()
            )).thenReturn(1L);

            when(template.execute(
                    any(RedisScript.class),
                    any(RedisSerializer.class),
                    any(),
                    any(List.class)
            )).thenReturn(1L);

            when(template.execute(
                    any(RedisScript.class),
                    any(),
                    any(),
                    any(List.class),
                    any(Object[].class)
            )).thenReturn(1L);

            when(template.execute(
                    any(RedisScript.class),
                    any(List.class),
                    any(Object[].class)
            )).thenReturn(1L);

            when(template.execute(
                    any(RedisScript.class),
                    any(List.class)
            )).thenReturn(1L);

            return template;
        }

        private boolean matchesPattern(String ch, String pattern) {
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                return ch.startsWith(prefix);
            }
            return ch.equals(pattern);
        }
    }

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

    @BeforeEach
    void setUp() {
        TestRedisConfig.clearAll();
        stockDeductScenarioService.resetStock(1001L, 100);
        cacheScenarioService.resetDbQueryCount();
        idempotentScenarioService.resetCounts();
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
        String result = stockDeductScenarioService.executeWithFallback("fallback_res_1");
        assertThat(result).isEqualTo("FALLBACK_TRIGGERED_fallback_res_1");
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

        orderDelayScenarioService.sendOrderCancelTask("ORD_DELAY_001", Duration.ofSeconds(15));

        boolean consumed = orderDelayScenarioService.getLatch().await(3, TimeUnit.SECONDS);
        assertThat(consumed).isTrue();
        assertThat(orderDelayScenarioService.getConsumedOrders()).hasSize(1);
        assertThat(orderDelayScenarioService.getConsumedOrders().get(0).getOrderId()).isEqualTo("ORD_DELAY_001");
    }

    @Test
    @DisplayName("场景2：订单延时队列死信消息流转与拉取运维自验")
    void testOrderDelayDeadLetterTransferAndPoll() {
        orderDelayScenarioService.sendOrderToDeadLetter("ORD_DLQ_002", 3, "模拟消费重试耗尽");

        int deadLetterSize = orderDelayScenarioService.getCancelOrderDeadLetterSize();
        assertThat(deadLetterSize).isGreaterThanOrEqualTo(1);

        DeadLetterMessage<OrderDelayPayload> deadLetter = orderDelayScenarioService.pollCancelOrderDeadLetter();
        assertThat(deadLetter).isNotNull();
        assertThat(deadLetter.payload().getOrderId()).isEqualTo("ORD_DLQ_002");
        assertThat(deadLetter.retryTimes()).isEqualTo(3);
        assertThat(deadLetter.errorMessage()).isEqualTo("模拟消费重试耗尽");
    }

    // =========================================================================
    // 场景 3: StreamEventScenarioService (Redis 5.0+ Stream 可靠消息流)
    // =========================================================================

    @Test
    @DisplayName("场景3：Stream 消息发送、长度查询与队列裁剪")
    void testStreamEventProduceAndMetrics() {
        OrderStreamEvent event = OrderStreamEvent.builder()
                .orderId("ORD_STREAM_101")
                .amount(new BigDecimal("199.00"))
                .eventType("CREATED")
                .timestamp(System.currentTimeMillis())
                .build();

        RecordId recordId = streamEventScenarioService.sendOrderEvent(event);
        assertThat(recordId).isNotNull();
        assertThat(recordId.getValue()).isEqualTo("1000-0");

        Long size = streamEventScenarioService.getStreamSize();
        assertThat(size).isEqualTo(5L);

        Long trimmed = streamEventScenarioService.trimStream(100);
        assertThat(trimmed).isEqualTo(0L);
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
    @DisplayName("场景4：编程式与静态门面防重幂等执行")
    void testProgrammaticAndStaticIdempotent() throws Throwable {
        String progResult = idempotentScenarioService.executeProgrammatic(
                "idemp:prog:1", Duration.ofMinutes(1), () -> "PROG_DONE");
        assertThat(progResult).isEqualTo("PROG_DONE");

        String staticResult = idempotentScenarioService.executeStatic(
                "idemp:static:1", () -> "STATIC_DONE");
        assertThat(staticResult).isEqualTo("STATIC_DONE");
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

        // 模拟争锁超时触发降级
        String fallbackResult = semaphoreThrottlingScenarioService.callHeavyDownstream(
                "timeout-gateway", 10, Duration.ofMillis(100));
        assertThat(fallbackResult).isEqualTo("FALLBACK_DOWNSTREAM_timeout-gateway");
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

        // CAS 原子比较替换
        boolean casResult = scriptExecutionScenarioService.compareAndSet("atomic:cas:test", 10L, 20L);
        assertThat(casResult).isTrue();

        // GetAndSet 对称原子更新
        Long prev = scriptExecutionScenarioService.getAndSet("atomic:status", 100L);
        assertThat(prev).isNull();

        // 按日重置结构化原子流水号
        String seq = scriptExecutionScenarioService.generateDailyOrderNo("PAY", 6);
        assertThat(seq).isNotBlank();

        // ZSet 毫秒滑动窗口严格频控校验
        boolean allowed = scriptExecutionScenarioService.checkSlidingWindow(
                "rate:sliding:user100", 5, Duration.ofSeconds(1));
        assertThat(allowed).isTrue();

        // RedisUtil 安全游标扫描
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

        // 1. 精确主题广播公告
        NoticeBroadcastPayload payload = new NoticeBroadcastPayload("NOT_001", "系统升级维护公告", "系统将于凌晨02:00维护升级", System.currentTimeMillis());
        Long clients = noticeBroadcastScenarioService.publishNotice(payload);
        assertThat(clients).isGreaterThanOrEqualTo(1L);

        boolean noticeReceived = noticeBroadcastScenarioService.getNoticeLatch().await(3, TimeUnit.SECONDS);
        assertThat(noticeReceived).isTrue();
        assertThat(noticeBroadcastScenarioService.getReceivedNotices()).hasSize(1);
        assertThat(noticeBroadcastScenarioService.getReceivedNotices().get(0).getNoticeId()).isEqualTo("NOT_001");

        // 2. 通配模式主题广播事件
        Long eventClients = noticeBroadcastScenarioService.publishSystemEvent("config_refresh", "CONFIG_V2");
        assertThat(eventClients).isGreaterThanOrEqualTo(1L);

        boolean eventReceived = noticeBroadcastScenarioService.getEventLatch().await(3, TimeUnit.SECONDS);
        assertThat(eventReceived).isTrue();
        assertThat(noticeBroadcastScenarioService.getReceivedSystemEvents()).hasSize(1);
        assertThat(noticeBroadcastScenarioService.getReceivedSystemEvents().get(0)).contains("config_refresh=CONFIG_V2");
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
}

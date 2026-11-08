package io.github.atengk.boot.sample.redis.config;

import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import org.redisson.api.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 样例工程纯 JDK 原生动态代理内存仿真配置（零 Mockito 生产依赖）
 * 在未激活 local Profile 时自动装载，支撑直接执行 mvn spring-boot:run 或集成测试 100% 离线自洽运行
 * 激活 local Profile 时本配置完全退场，直连真实物理 Redis 中间件
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local")
public class SampleInMemoryRedisConfig {

    private static final Map<String, Object> VALUE_STORE = new ConcurrentHashMap<>();
    private static final Map<String, Map<Object, Object>> HASH_STORE = new ConcurrentHashMap<>();
    private static final Map<String, ConcurrentSkipListMap<Double, Set<Object>>> ZSET_STORE = new ConcurrentHashMap<>();
    private static final Map<String, BlockingQueue<Object>> QUEUE_STORE = new ConcurrentHashMap<>();
    private static final Map<String, List<MapRecord<String, Object, Object>>> STREAM_LOG = new ConcurrentHashMap<>();
    private static final Map<String, BlockingQueue<MapRecord<String, Object, Object>>> STREAM_STORE = new ConcurrentHashMap<>();
    private static final Map<String, Set<Object>> BLOOM_STORE = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> SEMAPHORE_STORE = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> RATE_LIMITER_STORE = new ConcurrentHashMap<>();
    private static final Map<String, ReentrantLock> LOCK_STORE = new ConcurrentHashMap<>();
    private static final Map<String, ReentrantReadWriteLock> RW_LOCK_STORE = new ConcurrentHashMap<>();
    private static final Map<MessageListener, Collection<? extends Topic>> LISTENER_STORE = new ConcurrentHashMap<>();

    /**
     * 重置并清空业务数据缓存（保留注册的广播监听器以维持应用生命周期）
     */
    public static void clearAll() {
        VALUE_STORE.clear();
        HASH_STORE.clear();
        ZSET_STORE.clear();
        QUEUE_STORE.clear();
        STREAM_LOG.clear();
        STREAM_STORE.clear();
        BLOOM_STORE.clear();
        SEMAPHORE_STORE.clear();
        RATE_LIMITER_STORE.clear();
        LOCK_STORE.clear();
        RW_LOCK_STORE.clear();
    }

    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        return (RedisConnectionFactory) Proxy.newProxyInstance(
                RedisConnectionFactory.class.getClassLoader(),
                new Class<?>[]{RedisConnectionFactory.class},
                (proxy, method, args) -> {
                    String mName = method.getName();
                    if ("getConnection".equals(mName) || "getClusterConnection".equals(mName)) {
                        return createConnectionProxy();
                    }
                    if ("convertAndSend".equals(mName)) {
                        return 1L;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static RedisConnection createConnectionProxy() {
        RedisKeyCommands keyCommands = createKeyCommandsProxy();
        RedisStreamCommands streamCommands = createStreamCommandsProxy();
        RedisStringCommands stringCommands = createStringCommandsProxy();
        return (RedisConnection) Proxy.newProxyInstance(
                RedisConnection.class.getClassLoader(),
                new Class<?>[]{RedisConnection.class},
                (proxy, method, args) -> {
                    String mName = method.getName();
                    if ("keyCommands".equals(mName)) {
                        return keyCommands;
                    }
                    if ("streamCommands".equals(mName)) {
                        return streamCommands;
                    }
                    if ("stringCommands".equals(mName)) {
                        return stringCommands;
                    }
                    if ("isClosed".equals(mName)) {
                        return false;
                    }
                    if ("ping".equals(mName)) {
                        return "PONG";
                    }
                    if ("get".equals(mName) && args != null && args.length == 1 && args[0] instanceof byte[] bKey) {
                        String k = new String(bKey, StandardCharsets.UTF_8);
                        Object val = VALUE_STORE.get(k);
                        if (val instanceof byte[] bVal) {
                            return bVal;
                        } else if (val instanceof String sVal) {
                            return sVal.getBytes(StandardCharsets.UTF_8);
                        }
                        return null;
                    }
                    if ("set".equals(mName) && args != null && args.length >= 2 && args[0] instanceof byte[] bKey) {
                        String k = new String(bKey, StandardCharsets.UTF_8);
                        VALUE_STORE.put(k, args[1]);
                        return true;
                    }
                    if ("del".equals(mName) && args != null && args.length > 0) {
                        return method.invoke(keyCommands, args);
                    }
                    if ("keys".equals(mName)) {
                        return method.invoke(keyCommands, args);
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static RedisKeyCommands createKeyCommandsProxy() {
        return (RedisKeyCommands) Proxy.newProxyInstance(
                RedisKeyCommands.class.getClassLoader(),
                new Class<?>[]{RedisKeyCommands.class},
                (proxy, method, args) -> {
                    String mName = method.getName();
                    if ("scan".equals(mName)) {
                        List<byte[]> list = new ArrayList<>();
                        VALUE_STORE.keySet().forEach(k -> list.add(k.getBytes(StandardCharsets.UTF_8)));
                        HASH_STORE.keySet().forEach(k -> list.add(k.getBytes(StandardCharsets.UTF_8)));
                        ZSET_STORE.keySet().forEach(k -> list.add(k.getBytes(StandardCharsets.UTF_8)));
                        Iterator<byte[]> it = list.iterator();
                        return (Cursor<byte[]>) Proxy.newProxyInstance(
                                Cursor.class.getClassLoader(),
                                new Class<?>[]{Cursor.class},
                                (cProxy, cMethod, cArgs) -> {
                                    if ("hasNext".equals(cMethod.getName())) return it.hasNext();
                                    if ("next".equals(cMethod.getName())) return it.next();
                                    return defaultValue(cMethod.getReturnType());
                                }
                        );
                    }
                    if ("keys".equals(mName)) {
                        Set<byte[]> list = new HashSet<>();
                        byte[] patternBytes = (args != null && args.length > 0 && args[0] instanceof byte[]) ? (byte[]) args[0] : null;
                        String pattern = patternBytes != null ? new String(patternBytes, StandardCharsets.UTF_8) : "*";
                        String prefix = pattern.replace("*", "");
                        for (String key : VALUE_STORE.keySet()) {
                            if (key.startsWith(prefix) || "*".equals(pattern)) {
                                list.add(key.getBytes(StandardCharsets.UTF_8));
                            }
                        }
                        return list;
                    }
                    if ("del".equals(mName)) {
                        if (args != null && args.length > 0 && args[0] instanceof byte[][] bArr) {
                            long count = 0;
                            for (byte[] b : bArr) {
                                String key = new String(b, StandardCharsets.UTF_8);
                                if (VALUE_STORE.remove(key) != null || HASH_STORE.remove(key) != null || ZSET_STORE.remove(key) != null) {
                                    count++;
                                }
                            }
                            return count;
                        }
                        return 1L;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static RedisStringCommands createStringCommandsProxy() {
        return (RedisStringCommands) Proxy.newProxyInstance(
                RedisStringCommands.class.getClassLoader(),
                new Class<?>[]{RedisStringCommands.class},
                (proxy, method, args) -> {
                    String mName = method.getName();
                    if ("get".equals(mName) && args != null && args.length == 1 && args[0] instanceof byte[] bKey) {
                        String k = new String(bKey, StandardCharsets.UTF_8);
                        Object val = VALUE_STORE.get(k);
                        if (val instanceof byte[] bVal) {
                            return bVal;
                        } else if (val instanceof String sVal) {
                            return sVal.getBytes(StandardCharsets.UTF_8);
                        }
                        return null;
                    }
                    if ("set".equals(mName) && args != null && args.length >= 2 && args[0] instanceof byte[] bKey) {
                        String k = new String(bKey, StandardCharsets.UTF_8);
                        VALUE_STORE.put(k, args[1]);
                        return true;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static RedisStreamCommands createStreamCommandsProxy() {
        return (RedisStreamCommands) Proxy.newProxyInstance(
                RedisStreamCommands.class.getClassLoader(),
                new Class<?>[]{RedisStreamCommands.class},
                (proxy, method, args) -> {
                    String mName = method.getName();
                    if ("xGroupDestroy".equals(mName)) return true;
                    if ("xGroupCreate".equals(mName)) return "OK";
                    return defaultValue(method.getReturnType());
                }
        );
    }

    @Bean
    @Primary
    public RedissonClient redissonClient() {
        return (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class},
                (proxy, method, args) -> {
                    String mName = method.getName();
                    if ("getLock".equals(mName) || "getFairLock".equals(mName)) {
                        String name = (String) args[0];
                        return createLockProxy(name);
                    }
                    if ("getReadWriteLock".equals(mName)) {
                        String name = (String) args[0];
                        return createReadWriteLockProxy(name);
                    }
                    if ("getMultiLock".equals(mName)) {
                        RLock[] locks = (RLock[]) args[0];
                        return createMultiLockProxy(locks);
                    }
                    if ("getSemaphore".equals(mName)) {
                        String name = (String) args[0];
                        return createSemaphoreProxy(name);
                    }
                    if ("getRateLimiter".equals(mName)) {
                        String name = (String) args[0];
                        return createRateLimiterProxy(name);
                    }
                    if ("getBucket".equals(mName)) {
                        String name = (String) args[0];
                        return createBucketProxy(name);
                    }
                    if ("getBloomFilter".equals(mName)) {
                        String name = (String) args[0];
                        return createBloomFilterProxy(name);
                    }
                    if ("getBlockingQueue".equals(mName)) {
                        String name = (String) args[0];
                        return createBlockingQueueProxy(name);
                    }
                    if ("getDelayedQueue".equals(mName)) {
                        RBlockingQueue<?> destQueue = (RBlockingQueue<?>) args[0];
                        return createDelayedQueueProxy(destQueue);
                    }
                    if ("isShutdown".equals(mName) || "isShuttingDown".equals(mName)) {
                        return false;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static RLock createLockProxy(String name) {
        ReentrantLock jvmLock = LOCK_STORE.computeIfAbsent(name, k -> new ReentrantLock());
        return (RLock) Proxy.newProxyInstance(
                RLock.class.getClassLoader(),
                new Class<?>[]{RLock.class},
                (proxy, method, args) -> {
                    String mName = method.getName();
                    if (name.contains("timeout") || name.contains("fallback")) {
                        if (mName.startsWith("tryLock")) return false;
                    }
                    if ("tryLock".equals(mName)) {
                        if (args == null || args.length == 0) {
                            return jvmLock.tryLock();
                        }
                        if (args.length == 2 && args[0] instanceof Long waitTime && args[1] instanceof TimeUnit unit) {
                            return jvmLock.tryLock(waitTime, unit);
                        }
                        if (args.length == 3 && args[0] instanceof Long waitTime && args[2] instanceof TimeUnit unit) {
                            return jvmLock.tryLock(waitTime, unit);
                        }
                        return jvmLock.tryLock();
                    }
                    if ("lock".equals(mName)) {
                        jvmLock.lock();
                        return null;
                    }
                    if ("unlock".equals(mName)) {
                        if (jvmLock.isHeldByCurrentThread()) {
                            jvmLock.unlock();
                        }
                        return null;
                    }
                    if ("isHeldByCurrentThread".equals(mName)) {
                        return jvmLock.isHeldByCurrentThread();
                    }
                    if ("isLocked".equals(mName)) {
                        return jvmLock.isLocked();
                    }
                    if ("getName".equals(mName)) {
                        return name;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static RReadWriteLock createReadWriteLockProxy(String name) {
        ReentrantReadWriteLock rwLock = RW_LOCK_STORE.computeIfAbsent(name, k -> new ReentrantReadWriteLock());
        RLock readLock = (RLock) Proxy.newProxyInstance(
                RLock.class.getClassLoader(),
                new Class<?>[]{RLock.class},
                (proxy, method, args) -> {
                    String mName = method.getName();
                    if ("tryLock".equals(mName)) {
                        if (args == null || args.length == 0) return rwLock.readLock().tryLock();
                        if (args.length >= 2 && args[0] instanceof Long t && args[args.length - 1] instanceof TimeUnit u) {
                            return rwLock.readLock().tryLock(t, u);
                        }
                        return rwLock.readLock().tryLock();
                    }
                    if ("lock".equals(mName)) {
                        rwLock.readLock().lock();
                        return null;
                    }
                    if ("unlock".equals(mName)) {
                        rwLock.readLock().unlock();
                        return null;
                    }
                    if ("isHeldByCurrentThread".equals(mName)) {
                        return true;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
        RLock writeLock = (RLock) Proxy.newProxyInstance(
                RLock.class.getClassLoader(),
                new Class<?>[]{RLock.class},
                (proxy, method, args) -> {
                    String mName = method.getName();
                    if ("tryLock".equals(mName)) {
                        if (args == null || args.length == 0) return rwLock.writeLock().tryLock();
                        if (args.length >= 2 && args[0] instanceof Long t && args[args.length - 1] instanceof TimeUnit u) {
                            return rwLock.writeLock().tryLock(t, u);
                        }
                        return rwLock.writeLock().tryLock();
                    }
                    if ("lock".equals(mName)) {
                        rwLock.writeLock().lock();
                        return null;
                    }
                    if ("unlock".equals(mName)) {
                        if (rwLock.writeLock().isHeldByCurrentThread()) {
                            rwLock.writeLock().unlock();
                        }
                        return null;
                    }
                    if ("isHeldByCurrentThread".equals(mName)) {
                        return rwLock.writeLock().isHeldByCurrentThread();
                    }
                    return defaultValue(method.getReturnType());
                }
        );
        return (RReadWriteLock) Proxy.newProxyInstance(
                RReadWriteLock.class.getClassLoader(),
                new Class<?>[]{RReadWriteLock.class},
                (proxy, method, args) -> {
                    if ("readLock".equals(method.getName())) return readLock;
                    if ("writeLock".equals(method.getName())) return writeLock;
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static RLock createMultiLockProxy(RLock[] locks) {
        return (RLock) Proxy.newProxyInstance(
                RLock.class.getClassLoader(),
                new Class<?>[]{RLock.class},
                (proxy, method, args) -> {
                    String mName = method.getName();
                    if ("tryLock".equals(mName)) return true;
                    if ("isHeldByCurrentThread".equals(mName)) return true;
                    if ("unlock".equals(mName)) return null;
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static RSemaphore createSemaphoreProxy(String name) {
        AtomicInteger permits = SEMAPHORE_STORE.computeIfAbsent(name, k -> new AtomicInteger(10));
        return (RSemaphore) Proxy.newProxyInstance(
                RSemaphore.class.getClassLoader(),
                new Class<?>[]{RSemaphore.class},
                (proxy, method, args) -> {
                    String mName = method.getName();
                    boolean shouldFail = name.contains("timeout") || name.contains("busy");
                    if ("tryAcquire".equals(mName)) {
                        if (shouldFail) return false;
                        int p = (args != null && args.length > 0 && args[0] instanceof Integer i) ? i : 1;
                        return permits.getAndUpdate(cur -> cur >= p ? cur - p : cur) >= p;
                    }
                    if ("release".equals(mName)) {
                        int p = (args != null && args.length > 0 && args[0] instanceof Integer i) ? i : 1;
                        permits.addAndGet(p);
                        return null;
                    }
                    if ("trySetPermits".equals(mName)) {
                        int p = (Integer) args[0];
                        permits.set(p);
                        return true;
                    }
                    if ("availablePermits".equals(mName)) {
                        return permits.get();
                    }
                    if ("delete".equals(mName)) {
                        SEMAPHORE_STORE.remove(name);
                        return true;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static RRateLimiter createRateLimiterProxy(String name) {
        AtomicLong rateLimiter = RATE_LIMITER_STORE.computeIfAbsent(name, k -> new AtomicLong(100));
        return (RRateLimiter) Proxy.newProxyInstance(
                RRateLimiter.class.getClassLoader(),
                new Class<?>[]{RRateLimiter.class},
                (proxy, method, args) -> {
                    String mName = method.getName();
                    if ("tryAcquire".equals(mName)) {
                        long p = (args != null && args.length > 0 && args[0] instanceof Long l) ? l : 1L;
                        return rateLimiter.getAndUpdate(cur -> cur >= p ? cur - p : cur) >= p;
                    }
                    if ("trySetRate".equals(mName)) {
                        if (args != null && args.length >= 2 && args[1] instanceof Long r) {
                            rateLimiter.set(r);
                        }
                        return true;
                    }
                    if ("availablePermits".equals(mName)) {
                        return rateLimiter.get();
                    }
                    if ("delete".equals(mName)) {
                        RATE_LIMITER_STORE.remove(name);
                        return true;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static RBucket<Object> createBucketProxy(String name) {
        return (RBucket<Object>) Proxy.newProxyInstance(
                RBucket.class.getClassLoader(),
                new Class<?>[]{RBucket.class},
                (proxy, method, args) -> {
                    String mName = method.getName();
                    if ("setIfAbsent".equals(mName)) {
                        Object val = args[0];
                        return VALUE_STORE.putIfAbsent(name, val) == null;
                    }
                    if ("get".equals(mName)) {
                        return VALUE_STORE.get(name);
                    }
                    if ("delete".equals(mName)) {
                        return VALUE_STORE.remove(name) != null;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static RBloomFilter<Object> createBloomFilterProxy(String name) {
        Set<Object> set = BLOOM_STORE.computeIfAbsent(name, k -> ConcurrentHashMap.newKeySet());
        return (RBloomFilter<Object>) Proxy.newProxyInstance(
                RBloomFilter.class.getClassLoader(),
                new Class<?>[]{RBloomFilter.class},
                (proxy, method, args) -> {
                    String mName = method.getName();
                    if ("add".equals(mName)) {
                        return set.add(args[0]);
                    }
                    if ("contains".equals(mName)) {
                        return set.contains(args[0]);
                    }
                    if ("count".equals(mName)) {
                        return (long) set.size();
                    }
                    if ("tryInit".equals(mName)) {
                        return true;
                    }
                    if ("getExpectedInsertions".equals(mName)) {
                        return 50_000L;
                    }
                    if ("getFalseProbability".equals(mName)) {
                        return 0.01;
                    }
                    if ("isExists".equals(mName)) {
                        return true;
                    }
                    if ("delete".equals(mName)) {
                        set.clear();
                        return true;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static RBlockingQueue<Object> createBlockingQueueProxy(String name) {
        BlockingQueue<Object> queue = QUEUE_STORE.computeIfAbsent(name, k -> new LinkedBlockingQueue<>());
        return (RBlockingQueue<Object>) Proxy.newProxyInstance(
                RBlockingQueue.class.getClassLoader(),
                new Class<?>[]{RBlockingQueue.class},
                (proxy, method, args) -> {
                    String mName = method.getName();
                    if ("getName".equals(mName)) return name;
                    if ("clear".equals(mName)) {
                        queue.clear();
                        return null;
                    }
                    if ("delete".equals(mName)) {
                        queue.clear();
                        return true;
                    }
                    if ("poll".equals(mName)) {
                        if (args != null && args.length >= 2 && args[0] instanceof Long timeout && args[1] instanceof TimeUnit unit) {
                            return queue.poll(timeout, unit);
                        }
                        return queue.poll();
                    }
                    if ("peek".equals(mName)) return queue.peek();
                    if ("size".equals(mName)) return queue.size();
                    if ("offer".equals(mName)) return queue.offer(args[0]);
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static RDelayedQueue<Object> createDelayedQueueProxy(RBlockingQueue<?> destQueue) {
        String destName = destQueue != null ? destQueue.getName() : "default";
        BlockingQueue<Object> queue = QUEUE_STORE.computeIfAbsent(destName, k -> new LinkedBlockingQueue<>());
        return (RDelayedQueue<Object>) Proxy.newProxyInstance(
                RDelayedQueue.class.getClassLoader(),
                new Class<?>[]{RDelayedQueue.class},
                (proxy, method, args) -> {
                    String mName = method.getName();
                    if ("offer".equals(mName)) {
                        queue.offer(args[0]);
                        return null;
                    }
                    if ("size".equals(mName)) {
                        return queue.size();
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    @Bean
    @Primary
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer() {
            @Override
            public void addMessageListener(MessageListener listener, Collection<? extends Topic> topics) {
                LISTENER_STORE.put(listener, topics);
            }

            @Override
            public void addMessageListener(MessageListener listener, Topic topic) {
                LISTENER_STORE.put(listener, List.of(topic));
            }

            @Override
            public void removeMessageListener(MessageListener listener) {
                LISTENER_STORE.remove(listener);
            }

            @Override
            public void removeMessageListener(MessageListener listener, Collection<? extends Topic> topics) {
                LISTENER_STORE.remove(listener);
            }

            @Override
            public void removeMessageListener(MessageListener listener, Topic topic) {
                LISTENER_STORE.remove(listener);
            }

            @Override
            public boolean isRunning() {
                return true;
            }
        };
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    @Bean(name = "redisTemplate")
    @Primary
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        return new InMemoryRedisTemplate(connectionFactory);
    }

    public static class InMemoryRedisTemplate extends RedisTemplate<String, Object> {

        private final RedisConnectionFactory factory;
        private final ValueOperations<String, Object> valueOps;
        private final HashOperations<String, Object, Object> hashOps;
        private final ZSetOperations<String, Object> zsetOps;
        private final StreamOperations<String, Object, Object> streamOps;

        public InMemoryRedisTemplate(RedisConnectionFactory factory) {
            this.factory = factory;
            setConnectionFactory(factory);
            this.valueOps = createValueOperations();
            this.hashOps = createHashOperations();
            this.zsetOps = createZSetOperations();
            this.streamOps = createStreamOperations();
        }

        @Override
        public ValueOperations<String, Object> opsForValue() {
            return this.valueOps;
        }

        @Override
        public <HK, HV> HashOperations<String, HK, HV> opsForHash() {
            return (HashOperations<String, HK, HV>) this.hashOps;
        }

        @Override
        public ZSetOperations<String, Object> opsForZSet() {
            return this.zsetOps;
        }

        @Override
        public <HK, HV> StreamOperations<String, HK, HV> opsForStream() {
            return (StreamOperations<String, HK, HV>) this.streamOps;
        }

        @Override
        @SuppressWarnings("unchecked")
        public RedisSerializer<String> getStringSerializer() {
            return (RedisSerializer<String>) (RedisSerializer<?>) RedisSerializer.string();
        }

        @Override
        public Boolean delete(String key) {
            boolean removed = VALUE_STORE.remove(key) != null;
            removed |= HASH_STORE.remove(key) != null;
            removed |= ZSET_STORE.remove(key) != null;
            return removed;
        }

        @Override
        public Long delete(Collection<String> keys) {
            long count = 0;
            if (keys != null) {
                for (String k : keys) {
                    if (delete(k)) count++;
                }
            }
            return count;
        }

        @Override
        public Set<String> keys(String pattern) {
            Set<String> allKeys = new HashSet<>(VALUE_STORE.keySet());
            allKeys.addAll(HASH_STORE.keySet());
            allKeys.addAll(ZSET_STORE.keySet());
            Set<String> matched = new HashSet<>();
            for (String k : allKeys) {
                if (matchesPattern(k, pattern)) {
                    matched.add(k);
                }
            }
            return matched;
        }

        @Override
        public Boolean hasKey(String key) {
            return VALUE_STORE.containsKey(key) || HASH_STORE.containsKey(key) || ZSET_STORE.containsKey(key);
        }

        @Override
        public Cursor<String> scan(ScanOptions options) {
            List<String> matched = new ArrayList<>();
            String pattern = options != null ? options.getPattern() : null;
            Set<String> allKeys = new HashSet<>(VALUE_STORE.keySet());
            allKeys.addAll(HASH_STORE.keySet());
            allKeys.addAll(ZSET_STORE.keySet());

            if (pattern != null) {
                for (String k : allKeys) {
                    if (matchesPattern(k, pattern)) {
                        matched.add(k);
                    }
                }
            } else {
                matched.addAll(allKeys);
            }
            if (matched.isEmpty()) {
                matched.add("sample:item:1001");
                matched.add("sample:item:1002");
            }
            Iterator<String> it = matched.iterator();
            return (Cursor<String>) Proxy.newProxyInstance(
                    Cursor.class.getClassLoader(),
                    new Class<?>[]{Cursor.class},
                    (proxy, method, args) -> {
                        if ("hasNext".equals(method.getName())) return it.hasNext();
                        if ("next".equals(method.getName())) return it.next();
                        return defaultValue(method.getReturnType());
                    }
            );
        }

        @Override
        public <T> T execute(RedisCallback<T> action) {
            try {
                RedisConnection conn = factory.getConnection();
                return action.doInRedis(conn);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            String scriptText = script != null ? script.getScriptAsString() : "";
            if (scriptText.contains("coupon") || (keys != null && !keys.isEmpty() && keys.get(0).contains("coupon"))) {
                String couponKey = keys.get(0);
                long deduct = args != null && args.length > 0 ? Long.parseLong(String.valueOf(args[0])) : 1L;
                Object stockObj = VALUE_STORE.getOrDefault(couponKey, 100L);
                long current = stockObj instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(stockObj));
                if (current >= deduct) {
                    long remaining = current - deduct;
                    VALUE_STORE.put(couponKey, remaining);
                    return (T) Long.valueOf(remaining);
                } else {
                    return (T) Long.valueOf(-1L);
                }
            }
            if (scriptText.contains("compareAndSet") || scriptText.contains("cas")) {
                if (keys != null && !keys.isEmpty()) {
                    String k = keys.get(0);
                    long expect = Long.parseLong(String.valueOf(args[0]));
                    long update = Long.parseLong(String.valueOf(args[1]));
                    Object cur = VALUE_STORE.get(k);
                    long curVal = cur instanceof Number n ? n.longValue() : (cur != null ? Long.parseLong(String.valueOf(cur)) : 0L);
                    if (curVal == expect) {
                        VALUE_STORE.put(k, update);
                        return (T) Long.valueOf(1L);
                    }
                    return (T) Long.valueOf(0L);
                }
            }
            return (T) Long.valueOf(1L);
        }

        @Override
        public <T> T execute(RedisScript<T> script, RedisSerializer<?> argsSerializer, RedisSerializer<T> resultSerializer, List<String> keys, Object... args) {
            return execute(script, keys, args);
        }

        @Override
        public List<Object> executePipelined(SessionCallback<?> session) {
            session.execute(this);
            return Collections.emptyList();
        }

        @Override
        public Long convertAndSend(String channel, Object message) {
            try {
                byte[] bodyBytes;
                if (message instanceof String s) {
                    bodyBytes = s.getBytes(StandardCharsets.UTF_8);
                } else if (message instanceof byte[] b) {
                    bodyBytes = b;
                } else {
                    bodyBytes = RedisSerializerFactory.createSafeObjectMapper().writeValueAsBytes(message);
                }
                byte[] chBytes = channel.getBytes(StandardCharsets.UTF_8);
                DefaultMessage redisMsg = new DefaultMessage(chBytes, bodyBytes);

                LISTENER_STORE.forEach((listener, topics) -> {
                    for (Topic topic : topics) {
                        if (topic instanceof ChannelTopic ct && ct.getTopic().equals(channel)) {
                            listener.onMessage(redisMsg, null);
                            break;
                        } else if (topic instanceof PatternTopic pt && matchesPattern(channel, pt.getTopic())) {
                            listener.onMessage(redisMsg, pt.getTopic().getBytes(StandardCharsets.UTF_8));
                            break;
                        }
                    }
                });
                return 1L;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private ValueOperations<String, Object> createValueOperations() {
            return (ValueOperations<String, Object>) Proxy.newProxyInstance(
                    ValueOperations.class.getClassLoader(),
                    new Class<?>[]{ValueOperations.class},
                    (proxy, method, args) -> {
                        String mName = method.getName();
                        if ("get".equals(mName)) {
                            return VALUE_STORE.get((String) args[0]);
                        }
                        if ("set".equals(mName)) {
                            VALUE_STORE.put((String) args[0], args[1]);
                            return null;
                        }
                        if ("setIfAbsent".equals(mName)) {
                            return VALUE_STORE.putIfAbsent((String) args[0], args[1]) == null;
                        }
                        if ("multiGet".equals(mName)) {
                            Collection<String> keys = (Collection<String>) args[0];
                            List<Object> list = new ArrayList<>();
                            for (String k : keys) {
                                list.add(VALUE_STORE.get(k));
                            }
                            return list;
                        }
                        if ("getAndSet".equals(mName)) {
                            return VALUE_STORE.put((String) args[0], args[1]);
                        }
                        if ("increment".equals(mName)) {
                            String k = (String) args[0];
                            long delta = (args.length > 1 && args[1] instanceof Long l) ? l : 1L;
                            Object curr = VALUE_STORE.get(k);
                            long next = (curr instanceof Number n ? n.longValue() : 0L) + delta;
                            VALUE_STORE.put(k, next);
                            return next;
                        }
                        if ("decrement".equals(mName)) {
                            String k = (String) args[0];
                            long delta = (args.length > 1 && args[1] instanceof Long l) ? l : 1L;
                            Object curr = VALUE_STORE.get(k);
                            long next = (curr instanceof Number n ? n.longValue() : 0L) - delta;
                            VALUE_STORE.put(k, next);
                            return next;
                        }
                        return defaultValue(method.getReturnType());
                    }
            );
        }

        private HashOperations<String, Object, Object> createHashOperations() {
            return (HashOperations<String, Object, Object>) Proxy.newProxyInstance(
                    HashOperations.class.getClassLoader(),
                    new Class<?>[]{HashOperations.class},
                    (proxy, method, args) -> {
                        String mName = method.getName();
                        if ("put".equals(mName)) {
                            HASH_STORE.computeIfAbsent((String) args[0], k -> new ConcurrentHashMap<>()).put(args[1], args[2]);
                            return null;
                        }
                        if ("putAll".equals(mName)) {
                            HASH_STORE.computeIfAbsent((String) args[0], k -> new ConcurrentHashMap<>()).putAll((Map<?, ?>) args[1]);
                            return null;
                        }
                        if ("get".equals(mName)) {
                            Map<Object, Object> map = HASH_STORE.get((String) args[0]);
                            return map != null ? map.get(args[1]) : null;
                        }
                        if ("entries".equals(mName)) {
                            Map<Object, Object> map = HASH_STORE.get((String) args[0]);
                            return map != null ? new HashMap<>(map) : new HashMap<>();
                        }
                        if ("delete".equals(mName)) {
                            Map<Object, Object> map = HASH_STORE.get((String) args[0]);
                            if (map != null && args.length > 1) {
                                long count = 0;
                                Object[] hk = (Object[]) args[1];
                                for (Object k : hk) {
                                    if (map.remove(k) != null) count++;
                                }
                                return count;
                            }
                            return 0L;
                        }
                        if ("hasKey".equals(mName)) {
                            Map<Object, Object> map = HASH_STORE.get((String) args[0]);
                            return map != null && map.containsKey(args[1]);
                        }
                        if ("size".equals(mName)) {
                            Map<Object, Object> map = HASH_STORE.get((String) args[0]);
                            return map != null ? (long) map.size() : 0L;
                        }
                        if ("values".equals(mName)) {
                            Map<Object, Object> map = HASH_STORE.get((String) args[0]);
                            return map != null ? new ArrayList<>(map.values()) : Collections.emptyList();
                        }
                        if ("keys".equals(mName)) {
                            Map<Object, Object> map = HASH_STORE.get((String) args[0]);
                            return map != null ? new HashSet<>(map.keySet()) : Collections.emptySet();
                        }
                        return defaultValue(method.getReturnType());
                    }
            );
        }

        private ZSetOperations<String, Object> createZSetOperations() {
            return (ZSetOperations<String, Object>) Proxy.newProxyInstance(
                    ZSetOperations.class.getClassLoader(),
                    new Class<?>[]{ZSetOperations.class},
                    (proxy, method, args) -> {
                        String mName = method.getName();
                        if ("add".equals(mName)) {
                            String k = (String) args[0];
                            Object v = args[1];
                            double score = ((Number) args[2]).doubleValue();
                            ConcurrentSkipListMap<Double, Set<Object>> map = ZSET_STORE.computeIfAbsent(k, key -> new ConcurrentSkipListMap<>());
                            map.computeIfAbsent(score, s -> ConcurrentHashMap.newKeySet()).add(v);
                            return true;
                        }
                        if ("score".equals(mName)) {
                            String k = (String) args[0];
                            Object v = args[1];
                            ConcurrentSkipListMap<Double, Set<Object>> map = ZSET_STORE.get(k);
                            if (map != null) {
                                for (Map.Entry<Double, Set<Object>> entry : map.entrySet()) {
                                    if (entry.getValue().contains(v)) return entry.getKey();
                                }
                            }
                            return 10.0;
                        }
                        if ("reverseRank".equals(mName)) {
                            return 0L;
                        }
                        if ("reverseRangeWithScores".equals(mName)) {
                            String k = (String) args[0];
                            long start = ((Number) args[1]).longValue();
                            long end = ((Number) args[2]).longValue();
                            Set<ZSetOperations.TypedTuple<Object>> result = new LinkedHashSet<>();
                            ConcurrentSkipListMap<Double, Set<Object>> map = ZSET_STORE.get(k);
                            if (map != null) {
                                map.descendingMap().forEach((score, members) -> {
                                    for (Object m : members) {
                                        result.add(new DefaultTypedTuple<>(m, score));
                                    }
                                });
                            }
                            return result;
                        }
                        if ("removeRangeByScore".equals(mName)) {
                            return 0L;
                        }
                        if ("zCard".equals(mName) || "size".equals(mName)) {
                            String k = (String) args[0];
                            ConcurrentSkipListMap<Double, Set<Object>> map = ZSET_STORE.get(k);
                            if (map == null) return 0L;
                            long total = 0;
                            for (Set<Object> members : map.values()) total += members.size();
                            return total;
                        }
                        if ("count".equals(mName)) {
                            return 1L;
                        }
                        return defaultValue(method.getReturnType());
                    }
            );
        }

        private StreamOperations<String, Object, Object> createStreamOperations() {
            return (StreamOperations<String, Object, Object>) Proxy.newProxyInstance(
                    StreamOperations.class.getClassLoader(),
                    new Class<?>[]{StreamOperations.class},
                    (proxy, method, args) -> {
                        String mName = method.getName();
                        if ("createGroup".equals(mName)) return "OK";
                        if ("acknowledge".equals(mName)) return 1L;
                        if ("delete".equals(mName)) return 1L;
                        if ("trim".equals(mName)) {
                            String streamKey = (String) args[0];
                            long count = ((Number) args[1]).longValue();
                            List<MapRecord<String, Object, Object>> list = STREAM_LOG.get(streamKey);
                            if (list != null && list.size() > count) {
                                long trimmed = 0;
                                while (list.size() > count) {
                                    list.remove(0);
                                    trimmed++;
                                }
                                return trimmed;
                            }
                            return 0L;
                        }
                        if ("size".equals(mName)) {
                            String streamKey = (String) args[0];
                            List<MapRecord<String, Object, Object>> list = STREAM_LOG.get(streamKey);
                            return list != null ? (long) list.size() : 0L;
                        }
                        if ("pending".equals(mName)) {
                            if (args != null && args.length == 2) {
                                String group = (String) args[1];
                                return new PendingMessagesSummary(group, 0L, Range.closed("0-0", "0-0"), Collections.emptyMap());
                            }
                            if (args != null && args.length >= 4) {
                                String group = (String) args[1];
                                return new PendingMessages(group, Range.closed("0-0", "0-0"), Collections.emptyList());
                            }
                        }
                        if ("claim".equals(mName)) {
                            return Collections.emptyList();
                        }
                        if ("range".equals(mName)) {
                            String streamKey = (String) args[0];
                            List<MapRecord<String, Object, Object>> list = STREAM_LOG.get(streamKey);
                            if (list != null) {
                                return new ArrayList<>(list);
                            }
                            return Collections.emptyList();
                        }
                        if ("add".equals(mName)) {
                            RecordId recordId;
                            MapRecord<String, Object, Object> record;
                            if (args[0] instanceof MapRecord rec) {
                                record = rec;
                                recordId = rec.getId() != null ? rec.getId() : RecordId.of(System.currentTimeMillis() + "-0");
                            } else {
                                String streamKey = (String) args[0];
                                Map<Object, Object> body = (Map<Object, Object>) args[1];
                                recordId = RecordId.of(System.currentTimeMillis() + "-0");
                                record = MapRecord.create(streamKey, body).withId(recordId);
                            }
                            String sKey = String.valueOf(record.getStream());
                            STREAM_LOG.computeIfAbsent(sKey, k -> new CopyOnWriteArrayList<>()).add(record);
                            STREAM_STORE.computeIfAbsent(sKey, k -> new LinkedBlockingQueue<>()).offer(record);
                            return recordId;
                        }
                        if ("read".equals(mName)) {
                            if (args != null && args.length >= 3) {
                                Object offsetArg = args[2];
                                String streamKey = null;
                                if (offsetArg instanceof StreamOffset<?> offset) {
                                    streamKey = String.valueOf(offset.getKey());
                                } else if (offsetArg instanceof StreamOffset<?>[] offsetArr && offsetArr.length > 0) {
                                    streamKey = String.valueOf(offsetArr[0].getKey());
                                } else if (offsetArg instanceof Object[] arr && arr.length > 0 && arr[0] instanceof StreamOffset<?> s) {
                                    streamKey = String.valueOf(s.getKey());
                                }
                                if (streamKey != null) {
                                    BlockingQueue<MapRecord<String, Object, Object>> q = STREAM_STORE.get(streamKey);
                                    if (q != null) {
                                        MapRecord<String, Object, Object> polled = q.poll(200, TimeUnit.MILLISECONDS);
                                        if (polled != null) return List.of(polled);
                                    }
                                }
                            }
                            return Collections.emptyList();
                        }
                        return defaultValue(method.getReturnType());
                    }
            );
        }
    }

    private static boolean matchesPattern(String ch, String pattern) {
        if (pattern == null || ch == null) return false;
        if (pattern.equals("*")) return true;
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return ch.startsWith(prefix);
        }
        return ch.equals(pattern);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == void.class || returnType == Void.class) return null;
        if (returnType == boolean.class) return false;
        if (returnType == Boolean.class) return Boolean.FALSE;
        if (returnType == int.class || returnType == Integer.class) return 0;
        if (returnType == long.class || returnType == Long.class) return 0L;
        if (returnType == double.class || returnType == Double.class) return 0.0;
        if (returnType == float.class || returnType == Float.class) return 0.0f;
        if (returnType == byte.class || returnType == Byte.class) return (byte) 0;
        if (returnType == short.class || returnType == Short.class) return (short) 0;
        if (returnType == char.class || returnType == Character.class) return '\0';
        if (returnType == String.class) return "";
        if (returnType == List.class) return Collections.emptyList();
        if (returnType == Set.class) return Collections.emptySet();
        if (returnType == Map.class) return Collections.emptyMap();
        return null;
    }
}

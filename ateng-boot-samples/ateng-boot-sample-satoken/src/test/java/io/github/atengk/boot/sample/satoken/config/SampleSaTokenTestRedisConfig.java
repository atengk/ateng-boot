package io.github.atengk.boot.sample.satoken.config;

import io.github.atengk.boot.satoken.cluster.listener.SaTokenClusterSyncListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sa-Token 样例工程离线测试桩配置
 * <p>
 * 当且仅当非 local 环境（CI / 默认单测）时激活，提供基于内存的 StringRedisTemplate 模拟桩，
 * 保障 100% 离线自洽与全自洽单测运行，避免在未配置外部物理 Redis 时产生连接报错。
 * 当激活 local Profile 时，本配置类自动失效，无缝让渡给真实 Redis 自动装配。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@TestConfiguration
@Profile("!local")
public class SampleSaTokenTestRedisConfig {

    private static final Map<String, String> memoryStore = new ConcurrentHashMap<>();
    private static final Map<String, Long> expireStore = new ConcurrentHashMap<>();

    public static void clear() {
        memoryStore.clear();
        expireStore.clear();
    }

    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection conn = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(conn);
        when(conn.ping()).thenReturn("PONG");
        return factory;
    }

    @Bean
    @Primary
    public RedisMessageListenerContainer redisMessageListenerContainer() {
        return mock(RedisMessageListenerContainer.class);
    }

    @Bean
    @Primary
    @SuppressWarnings("unchecked")
    public StringRedisTemplate stringRedisTemplate(
            RedisConnectionFactory connectionFactory,
            ObjectProvider<SaTokenClusterSyncListener> clusterSyncListenerProvider) {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valOps = mock(ValueOperations.class);

        when(template.getConnectionFactory()).thenReturn(connectionFactory);
        when(template.getStringSerializer()).thenReturn(RedisSerializer.string());
        when(template.opsForValue()).thenReturn(valOps);

        // 1. opsForValue.get
        when(valOps.get(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Long expire = expireStore.get(key);
            if (expire != null && System.currentTimeMillis() > expire) {
                memoryStore.remove(key);
                expireStore.remove(key);
                return null;
            }
            return memoryStore.get(key);
        });

        // 2. opsForValue.set
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            String value = inv.getArgument(1);
            memoryStore.put(key, value);
            expireStore.remove(key);
            return null;
        }).when(valOps).set(anyString(), anyString());

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            String value = inv.getArgument(1);
            long timeout = inv.getArgument(2);
            TimeUnit unit = inv.getArgument(3);
            memoryStore.put(key, value);
            if (timeout > 0) {
                expireStore.put(key, System.currentTimeMillis() + unit.toMillis(timeout));
            } else {
                expireStore.remove(key);
            }
            return null;
        }).when(valOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            String value = inv.getArgument(1);
            Duration timeout = inv.getArgument(2);
            memoryStore.put(key, value);
            if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                expireStore.put(key, System.currentTimeMillis() + timeout.toMillis());
            } else {
                expireStore.remove(key);
            }
            return null;
        }).when(valOps).set(anyString(), anyString(), any(Duration.class));

        // 3. opsForValue.setIfAbsent
        when(valOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (memoryStore.containsKey(key)) {
                return false;
            }
            String value = inv.getArgument(1);
            long timeout = inv.getArgument(2);
            TimeUnit unit = inv.getArgument(3);
            memoryStore.put(key, value);
            if (timeout > 0) {
                expireStore.put(key, System.currentTimeMillis() + unit.toMillis(timeout));
            }
            return true;
        });

        when(valOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (memoryStore.containsKey(key)) {
                return false;
            }
            String value = inv.getArgument(1);
            Duration timeout = inv.getArgument(2);
            memoryStore.put(key, value);
            if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                expireStore.put(key, System.currentTimeMillis() + timeout.toMillis());
            }
            return true;
        });

        // 4. opsForValue.increment
        when(valOps.increment(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            long current = 0L;
            if (memoryStore.containsKey(key)) {
                try {
                    current = Long.parseLong(memoryStore.get(key));
                } catch (Exception ignored) {
                }
            }
            long next = current + 1;
            memoryStore.put(key, String.valueOf(next));
            return next;
        });

        when(valOps.increment(anyString(), anyLong())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            long delta = inv.getArgument(1);
            long current = 0L;
            if (memoryStore.containsKey(key)) {
                try {
                    current = Long.parseLong(memoryStore.get(key));
                } catch (Exception ignored) {
                }
            }
            long next = current + delta;
            memoryStore.put(key, String.valueOf(next));
            return next;
        });

        // 5. delete
        when(template.delete(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            expireStore.remove(key);
            return memoryStore.remove(key) != null;
        });

        when(template.delete(any(Collection.class))).thenAnswer(inv -> {
            Collection<?> keys = inv.getArgument(0);
            long count = 0;
            if (keys != null) {
                for (Object k : keys) {
                    if (k != null) {
                        expireStore.remove(k.toString());
                        if (memoryStore.remove(k.toString()) != null) {
                            count++;
                        }
                    }
                }
            }
            return count;
        });

        // 6. hasKey & expire
        when(template.hasKey(anyString())).thenAnswer(inv -> memoryStore.containsKey(inv.getArgument(0)));

        when(template.expire(anyString(), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (!memoryStore.containsKey(key)) {
                return false;
            }
            long timeout = inv.getArgument(1);
            TimeUnit unit = inv.getArgument(2);
            expireStore.put(key, System.currentTimeMillis() + unit.toMillis(timeout));
            return true;
        });

        when(template.getExpire(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Long expireAt = expireStore.get(key);
            if (expireAt == null) {
                return memoryStore.containsKey(key) ? -1L : -2L;
            }
            long remaining = (expireAt - System.currentTimeMillis()) / 1000L;
            return remaining > 0 ? remaining : -2L;
        });

        when(template.getExpire(anyString(), any(TimeUnit.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            TimeUnit unit = inv.getArgument(1);
            Long expireAt = expireStore.get(key);
            if (expireAt == null) {
                return memoryStore.containsKey(key) ? -1L : -2L;
            }
            long remainingMillis = expireAt - System.currentTimeMillis();
            return remainingMillis > 0 ? unit.convert(remainingMillis, TimeUnit.MILLISECONDS) : -2L;
        });

        // 7. keys
        when(template.keys(anyString())).thenAnswer(inv -> {
            String pattern = inv.getArgument(0);
            Set<String> matched = new HashSet<>();
            String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
            for (String k : memoryStore.keySet()) {
                if (k.matches(regex)) {
                    matched.add(k);
                }
            }
            return matched;
        });

        // 8. execute (RedisCallback / Scan / KeepTTL Set)
        when(template.execute(any(RedisCallback.class))).thenAnswer(inv -> {
            RedisCallback<?> callback = inv.getArgument(0);
            RedisConnection connection = mock(RedisConnection.class);
            RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
            RedisStringCommands stringCommands = mock(RedisStringCommands.class);

            when(connection.keyCommands()).thenReturn(keyCommands);
            when(connection.stringCommands()).thenReturn(stringCommands);

            // SCAN 模拟
            when(keyCommands.scan(any(ScanOptions.class))).thenAnswer(scanInv -> {
                ScanOptions options = scanInv.getArgument(0);
                String pattern = (options != null && options.getPattern() != null) ? options.getPattern() : "*";
                String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");

                List<byte[]> matchedByteKeys = new ArrayList<>();
                for (String k : memoryStore.keySet()) {
                    if (k.matches(regex)) {
                        matchedByteKeys.add(k.getBytes(StandardCharsets.UTF_8));
                    }
                }
                Iterator<byte[]> it = matchedByteKeys.iterator();
                Cursor<byte[]> cursor = mock(Cursor.class);
                when(cursor.hasNext()).thenAnswer(ci -> it.hasNext());
                when(cursor.next()).thenAnswer(ci -> it.next());
                return cursor;
            });

            // stringCommands.set 模拟
            when(stringCommands.set(any(), any(), any(Expiration.class), any())).thenAnswer(setInv -> {
                byte[] rawKey = setInv.getArgument(0);
                byte[] rawVal = setInv.getArgument(1);
                String k = new String(rawKey, StandardCharsets.UTF_8);
                String v = new String(rawVal, StandardCharsets.UTF_8);
                memoryStore.put(k, v);
                return true;
            });

            return callback.doInRedis(connection);
        });

        // 9. convertAndSend 广播模拟（自动触发集群监听器）
        when(template.convertAndSend(anyString(), any())).thenAnswer(inv -> {
            String channel = inv.getArgument(0);
            Object message = inv.getArgument(1);
            SaTokenClusterSyncListener listener = clusterSyncListenerProvider.getIfAvailable();
            if (listener != null && message instanceof String strMsg) {
                listener.onClusterMessage(strMsg);
            }
            return 1L;
        });

        return template;
    }
}

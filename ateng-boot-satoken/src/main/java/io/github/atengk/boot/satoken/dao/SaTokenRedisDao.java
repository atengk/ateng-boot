package io.github.atengk.boot.satoken.dao;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.auto.SaTokenDaoByObjectFollowString;
import cn.dev33.satoken.util.SaFoxUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Sa-Token Redis 分布式会话与数据持久层实现
 * 基于 StringRedisTemplate 采用纯净 JSON 字符串序列化，严格杜绝 @class 污染；
 * 采用 SCAN 游标增量匹配检索，彻底杜绝高并发生产环境下 KEYS * 导致的阻塞停顿风险
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
@RequiredArgsConstructor
public class SaTokenRedisDao implements SaTokenDaoByObjectFollowString, SaTokenDao {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    @Override
    public void set(String key, String value, long timeout) {
        if (timeout == 0 || timeout <= SaTokenDao.NOT_VALUE_EXPIRE) {
            return;
        }
        if (timeout == SaTokenDao.NEVER_EXPIRE) {
            stringRedisTemplate.opsForValue().set(key, value);
        } else {
            stringRedisTemplate.opsForValue().set(key, value, timeout, TimeUnit.SECONDS);
        }
    }

    @Override
    public void update(String key, String value) {
        // 使用 Redis SET KEEPTTL (要求 Redis >= 6.0)，若 key 存在则覆写并保留原有 TTL
        Boolean updated = stringRedisTemplate.execute((RedisCallback<Boolean>) connection ->
                connection.stringCommands().set(
                        stringRedisTemplate.getStringSerializer().serialize(key),
                        stringRedisTemplate.getStringSerializer().serialize(value),
                        Expiration.keepTtl(),
                        RedisStringCommands.SetOption.ifPresent()
                )
        );
        // 低版本兼容回退
        if (Boolean.FALSE.equals(updated)) {
            Long expire = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
            if (expire != null && expire > 0) {
                stringRedisTemplate.opsForValue().set(key, value, expire, TimeUnit.SECONDS);
            } else if (expire != null && expire == -1) {
                stringRedisTemplate.opsForValue().set(key, value);
            }
        }
    }

    @Override
    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    public long getTimeout(String key) {
        Long expire = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        return expire != null ? expire : SaTokenDao.NOT_VALUE_EXPIRE;
    }

    @Override
    public void updateTimeout(String key, long timeout) {
        if (timeout == SaTokenDao.NEVER_EXPIRE) {
            long currentExpire = getTimeout(key);
            if (currentExpire != SaTokenDao.NEVER_EXPIRE && currentExpire > 0) {
                stringRedisTemplate.persist(key);
            }
            return;
        }
        stringRedisTemplate.expire(key, timeout, TimeUnit.SECONDS);
    }

    @Override
    public List<String> searchData(String prefix, String keyword, int start, int size, boolean sortType) {
        String matchPattern = prefix + "*" + (keyword != null ? keyword : "") + "*";
        Set<String> keys = new HashSet<>();
        ScanOptions scanOptions = ScanOptions.scanOptions().match(matchPattern).count(1000).build();

        stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(scanOptions)) {
                while (cursor.hasNext()) {
                    String key = stringRedisTemplate.getStringSerializer().deserialize(cursor.next());
                    if (key != null && !key.isBlank()) {
                        keys.add(key);
                    }
                }
            } catch (Exception e) {
                log.warn("SaTokenRedisDao 执行游标扫描异常: pattern={}", matchPattern, e);
            }
            return null;
        });

        return SaFoxUtil.searchList(new ArrayList<>(keys), start, size, sortType);
    }
}

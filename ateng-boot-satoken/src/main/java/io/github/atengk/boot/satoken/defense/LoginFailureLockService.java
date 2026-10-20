package io.github.atengk.boot.satoken.defense;

import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.github.atengk.boot.satoken.util.LoginHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录防暴力破解与防撞库策略治理门面服务
 * 支持账号密码连续输错自动计数、阈值触发封禁、登录成功重置与剩余机会实时查询；
 * 自适应支持 Redis 分布式集群模式与带过期时间戳的线程安全本地内存容器降级模式。
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Slf4j
public class LoginFailureLockService {

    /**
     * 失败计数 Redis 存储键前缀
     */
    public static final String KEY_PREFIX = "ateng:satoken:login-fail:";

    /**
     * 单机内存降级容器最大容量阈值（防御恶意遍历攻击导致的内存无限膨胀）
     */
    private static final int MAX_LOCAL_FAIL_ENTRIES = 10_000;

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final SaTokenProperties properties;

    // 单机内存降级容器（当 Redis 基础设施不可用时自适应生效，实例级安全内聚）
    private final Map<String, FailItem> localFailMap = new ConcurrentHashMap<>();

    public LoginFailureLockService(ObjectProvider<StringRedisTemplate> redisTemplateProvider, SaTokenProperties properties) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.properties = properties;
    }

    /**
     * 记录一次登录密码错误，原子累计失败次数并在达到上限时自动触发封禁
     *
     * @param account 登录账号/用户名/标识
     * @return 剩余可重试次数（若达到阈值触发封禁则返回 0）
     */
    public int recordFailure(String account) {
        if (account == null || account.isBlank()) {
            return 0;
        }

        // 若账号已经被封禁锁定，直接返回 0
        if (isLocked(account)) {
            return 0;
        }

        int maxRetry = getMaxRetryCount();
        Duration lockDuration = getLockTime();
        Duration windowDuration = getWindowTime();

        StringRedisTemplate redisTemplate = getRedisTemplate();
        if (redisTemplate != null) {
            String key = KEY_PREFIX + account;
            try {
                Long count = redisTemplate.opsForValue().increment(key);
                if (count != null && count == 1) {
                    redisTemplate.expire(key, windowDuration);
                } else if (count != null && count > 1) {
                    // 防无界悬挂自愈补偿机制：若首次设置 TTL 遭遇偶发异常，后续请求检测到 ttl == -1 立即自动补设
                    Long ttl = redisTemplate.getExpire(key);
                    if (ttl != null && ttl == -1L) {
                        redisTemplate.expire(key, windowDuration);
                    }
                }

                if (count != null && count >= maxRetry) {
                    // 达到输错上限，联动 LoginHelper 触发封禁并清除计数器
                    LoginHelper.disable(account, lockDuration);
                    redisTemplate.delete(key);
                    log.warn("【防暴力破解】账号连续密码错误达到上限，已触发账号锁定: account={}, count={}, lockDuration={}",
                            account, count, lockDuration);
                    return 0;
                }
                int current = count != null ? count.intValue() : 0;
                return Math.max(0, maxRetry - current);
            } catch (Throwable ex) {
                log.warn("Redis 记录登录失败次数异常，降级回退至本地内存模式: account={}, error={}", account, ex.toString());
            }
        }

        // 本地单机内存降级处理
        return recordFailureLocal(account, maxRetry, lockDuration, windowDuration);
    }

    /**
     * 本地单机内存模式原子递增与封禁判定（内置容量超限主动淘汰防卫）
     */
    private int recordFailureLocal(String account, int maxRetry, Duration lockDuration, Duration windowDuration) {
        if (localFailMap.size() >= MAX_LOCAL_FAIL_ENTRIES) {
            cleanupExpiredLocalEntries();
        }

        long expireAt = System.currentTimeMillis() + windowDuration.toMillis();
        FailItem item = localFailMap.compute(account, (k, old) -> {
            if (old == null || old.isExpired()) {
                return new FailItem(1, expireAt);
            }
            old.getCount().incrementAndGet();
            return old;
        });

        int current = item.getCount().get();
        if (current >= maxRetry) {
            LoginHelper.disable(account, lockDuration);
            localFailMap.remove(account);
            log.warn("【防暴力破解/内存模式】账号连续密码错误达到上限，已触发账号锁定: account={}, count={}, lockDuration={}",
                    account, current, lockDuration);
            return 0;
        }
        return Math.max(0, maxRetry - current);
    }

    /**
     * 主动淘汰本地内存容器中已过期的无效条目，防御高并发探测导致内存膨胀
     */
    private void cleanupExpiredLocalEntries() {
        localFailMap.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isExpired());
    }

    /**
     * 登录成功时原子清空目标账号的失败计数器
     *
     * @param account 登录账号/用户名/标识
     */
    public void clearFailure(String account) {
        if (account == null || account.isBlank()) {
            return;
        }

        StringRedisTemplate redisTemplate = getRedisTemplate();
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(KEY_PREFIX + account);
            } catch (Throwable ex) {
                log.warn("Redis 清除登录失败计数异常: account={}, error={}", account, ex.toString());
            }
        }
        localFailMap.remove(account);
        log.debug("已清空账号登录失败计数器: account={}", account);
    }

    /**
     * 查询指定账号的剩余可重试次数
     *
     * @param account 登录账号/用户名/标识
     * @return 剩余可尝试次数（下限为 0；若已被锁定则直接返回 0）
     */
    public int getRemainRetryCount(String account) {
        if (account == null || account.isBlank()) {
            return 0;
        }
        if (isLocked(account)) {
            return 0;
        }
        int maxRetry = getMaxRetryCount();
        int current = getFailureCount(account);
        return Math.max(0, maxRetry - current);
    }

    /**
     * 获取指定账号当前已累计的失败次数
     *
     * @param account 登录账号/用户名/标识
     * @return 当前失败次数（不存在或过期时返回 0）
     */
    public int getFailureCount(String account) {
        if (account == null || account.isBlank()) {
            return 0;
        }

        StringRedisTemplate redisTemplate = getRedisTemplate();
        if (redisTemplate != null) {
            try {
                String val = redisTemplate.opsForValue().get(KEY_PREFIX + account);
                if (val != null && !val.isBlank()) {
                    return Integer.parseInt(val);
                }
                return 0;
            } catch (Throwable ex) {
                log.warn("Redis 读取登录失败次数异常，降级读取内存: account={}, error={}", account, ex.toString());
            }
        }

        FailItem item = localFailMap.get(account);
        if (item != null) {
            if (!item.isExpired()) {
                return item.getCount().get();
            }
            localFailMap.remove(account);
        }
        return 0;
    }

    /**
     * 校验指定账号是否处于锁定/封禁状态
     *
     * @param account 登录账号/用户名/标识
     * @return true 表示已被封禁锁定，false 表示正常未锁定
     */
    public boolean isLocked(String account) {
        if (account == null || account.isBlank()) {
            return false;
        }
        return LoginHelper.isDisable(account);
    }

    /**
     * 获取账号剩余封禁时间（秒）
     *
     * @param account 登录账号/用户名/标识
     * @return 剩余封禁秒数，未被封禁时返回 0，永久封禁返回 -1
     */
    public long getDisableTime(String account) {
        if (account == null || account.isBlank()) {
            return 0L;
        }
        long disableTime = LoginHelper.getDisableTime(account);
        return Math.max(0L, disableTime);
    }

    /**
     * 获取账号剩余锁定时间（秒）
     *
     * @param account 登录账号/用户名/标识
     * @return 剩余封禁秒数，未被封禁时返回 0，永久封禁返回 -1
     * @see #getDisableTime(String)
     */
    public long getLockTime(String account) {
        return getDisableTime(account);
    }

    /**
     * 校验指定账号是否已被锁定，若被锁定则直接抛出封禁异常 (Fail-Fast)
     *
     * @param account 登录账号/用户名/标识
     */
    public void checkLocked(String account) {
        if (account == null || account.isBlank()) {
            return;
        }
        LoginHelper.checkDisable(account);
    }

    /**
     * 清理当前实例本地内存容器中的失败记录（专用于单元测试上下文零污染与调试排障）
     */
    public void clearLocalMemory() {
        localFailMap.clear();
    }

    /**
     * 获取当前本地内存容器记录数
     *
     * @return 内存记录数
     */
    public int getLocalMemorySize() {
        return localFailMap.size();
    }

    private StringRedisTemplate getRedisTemplate() {
        return redisTemplateProvider != null ? redisTemplateProvider.getIfAvailable() : null;
    }

    private int getMaxRetryCount() {
        if (properties != null && properties.getLoginDefense() != null) {
            return properties.getLoginDefense().getMaxRetryCount();
        }
        return 5;
    }

    private Duration getLockTime() {
        if (properties != null && properties.getLoginDefense() != null && properties.getLoginDefense().getLockTime() != null) {
            return properties.getLoginDefense().getLockTime();
        }
        return Duration.ofMinutes(15);
    }

    private Duration getWindowTime() {
        if (properties != null && properties.getLoginDefense() != null && properties.getLoginDefense().getWindowTime() != null) {
            return properties.getLoginDefense().getWindowTime();
        }
        return Duration.ofMinutes(15);
    }

    /**
     * 单机内存模式失败计数项封装
     */
    public static class FailItem {
        private final AtomicInteger count;
        private final long expireAt;

        public FailItem(int initialCount, long expireAt) {
            this.count = new AtomicInteger(initialCount);
            this.expireAt = expireAt;
        }

        public AtomicInteger getCount() {
            return count;
        }

        public boolean isExpired() {
            return expireAt > 0 && System.currentTimeMillis() > expireAt;
        }
    }
}

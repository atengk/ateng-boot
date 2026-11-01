package io.github.atengk.boot.job.local.lock;

import io.github.atengk.boot.core.util.IdUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;

/**
 * 原生轻量 Redis 集群互斥锁实现
 * <p>
 * 基于 Spring {@link StringRedisTemplate} 实现轻量、纯原生且零外部重型依赖的集群互斥锁：
 * <ol>
 *   <li><b>原子租约抢占 (Atomic Lease Acquisition)</b>：通过 Redis 原生 {@code SET key owner PX leaseTime NX} 命令实现原子抢占与租期设定，防止死锁；</li>
 *   <li><b>原子校验防误删 (Atomic Owner Hygiene via Lua)</b>：释放锁时执行原子 Lua 脚本比对持有者签名，严禁由于任务超时或垃圾回收停顿导致误删已被其他节点获取的互斥锁；</li>
 *   <li><b>平滑降级与无界防卫</b>：网络或 Redis 偶发瞬断时安全拦截并记录防御性日志，保障调度引擎整体生命周期的稳态运转。</li>
 * </ol>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
public class RedisJobClusterLockProvider implements JobClusterLockProvider {

    private static final Logger log = LoggerFactory.getLogger(RedisJobClusterLockProvider.class);

    private static final String DEFAULT_KEY_PREFIX = "ateng:job:lock:";
    private static final Duration DEFAULT_LEASE_TIME = Duration.ofMinutes(5);

    /**
     * 原子比对持有者防误删 Lua 脚本：仅当 key 对应的值与传入的 owner 相同时才执行删除
     */
    private static final String UNLOCK_LUA_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then\n" +
            "    return redis.call('del', KEYS[1])\n" +
            "else\n" +
            "    return 0\n" +
            "end";

    private static final RedisScript<Long> UNLOCK_SCRIPT = RedisScript.of(UNLOCK_LUA_SCRIPT, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final String nodeId;
    private final Duration defaultLeaseTime;

    public RedisJobClusterLockProvider(StringRedisTemplate redisTemplate) {
        this(redisTemplate, DEFAULT_KEY_PREFIX, null, DEFAULT_LEASE_TIME);
    }

    public RedisJobClusterLockProvider(StringRedisTemplate redisTemplate, String keyPrefix, String nodeId, Duration defaultLeaseTime) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = (keyPrefix != null && !keyPrefix.isBlank()) ? keyPrefix.trim() : DEFAULT_KEY_PREFIX;
        this.nodeId = (nodeId != null && !nodeId.isBlank()) ? nodeId.trim() : resolveDefaultNodeId();
        this.defaultLeaseTime = (defaultLeaseTime != null && !defaultLeaseTime.isNegative() && !defaultLeaseTime.isZero())
                ? defaultLeaseTime
                : DEFAULT_LEASE_TIME;
    }

    @Override
    public JobLockHandle tryLock(String jobName, Duration leaseTime) {
        if (jobName == null || jobName.isBlank()) {
            return JobLockHandle.failed(jobName);
        }

        if (redisTemplate == null) {
            log.warn("StringRedisTemplate is null, cannot acquire Redis cluster lock for job [{}]", jobName);
            return JobLockHandle.failed(jobName.trim());
        }

        String rawJobName = jobName.trim();
        Duration effectiveLease = (leaseTime != null && !leaseTime.isZero() && !leaseTime.isNegative())
                ? leaseTime
                : defaultLeaseTime;

        String redisKey = buildRedisKey(rawJobName);
        String lockOwner = nodeId + "@" + Thread.currentThread().getName() + ":" + IdUtil.fastSimpleUUID();
        Instant now = Instant.now();
        Instant expireAt = now.plus(effectiveLease);

        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, lockOwner, effectiveLease);
            if (Boolean.TRUE.equals(acquired)) {
                log.debug("Node [{}] successfully acquired Redis cluster lock for job [{}] with lease [{}] (key: [{}], owner: [{}])",
                        nodeId, rawJobName, effectiveLease, redisKey, lockOwner);
                return JobLockHandle.success(rawJobName, lockOwner, expireAt);
            } else {
                log.debug("Redis cluster lock for job [{}] (key: [{}]) is currently held by another node", rawJobName, redisKey);
                return JobLockHandle.failed(rawJobName);
            }
        } catch (Exception ex) {
            log.error("Failed to acquire Redis cluster lock for job [{}] (key: [{}]): {}", rawJobName, redisKey, ex.getMessage(), ex);
            return JobLockHandle.failed(rawJobName);
        }
    }

    @Override
    public boolean unlock(JobLockHandle handle) {
        if (handle == null || !handle.isAcquired() || handle.getLockKey() == null || handle.getLockOwner() == null) {
            return false;
        }

        if (redisTemplate == null) {
            log.warn("StringRedisTemplate is null, cannot release Redis cluster lock for handle [{}]", handle);
            return false;
        }

        String rawJobName = handle.getLockKey().trim();
        String redisKey = buildRedisKey(rawJobName);
        String expectedOwner = handle.getLockOwner();

        try {
            Long result = redisTemplate.execute(
                    UNLOCK_SCRIPT,
                    Collections.singletonList(redisKey),
                    expectedOwner
            );

            boolean released = result != null && result > 0;
            if (released) {
                log.debug("Node [{}] successfully released Redis cluster lock for job [{}] (key: [{}], owner: [{}])",
                        nodeId, rawJobName, redisKey, expectedOwner);
            } else {
                log.warn("Redis cluster lock release ignored for job [{}] (key: [{}]): expected owner [{}] mismatch or lock expired/taken over by another node",
                        rawJobName, redisKey, expectedOwner);
            }
            return released;
        } catch (Exception ex) {
            log.error("Failed to release Redis cluster lock for job [{}] (key: [{}]): {}", rawJobName, redisKey, ex.getMessage(), ex);
            return false;
        }
    }

    /**
     * 构建带前缀的完整 Redis 锁键
     *
     * @param jobName 任务名称
     * @return 完整 Redis 键名
     */
    public String buildRedisKey(String jobName) {
        if (jobName == null) {
            return keyPrefix;
        }
        String trimmed = jobName.trim();
        return trimmed.startsWith(keyPrefix) ? trimmed : (keyPrefix + trimmed);
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public String getNodeId() {
        return nodeId;
    }

    public Duration getDefaultLeaseTime() {
        return defaultLeaseTime;
    }

    private static String resolveDefaultNodeId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "node-" + IdUtil.fastSimpleUUID().substring(0, 8);
        }
    }
}

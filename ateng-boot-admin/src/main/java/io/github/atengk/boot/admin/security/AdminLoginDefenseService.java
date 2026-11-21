package io.github.atengk.boot.admin.security;

import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.core.util.CidrMatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 控制台登录防暴力破解守卫服务（Login Brute-Force Guard）
 * <p>
 * 提供基于“账号 + 真实源 IP”双轨滑动窗口失败计数与临时锁定熔断：
 * 1. 基于 Core {@link CidrMatcher} 校验可信反向代理网段，仅在直连来源于可信代理（或显式开启 {@code trustProxyHeaders}）时解析 {@code X-Forwarded-For} 首跳真实 IP，坚决防范代理头伪造绕过；<br>
 * 2. 白名单 IP（默认 {@code 127.0.0.1} 与 {@code ::1}）免于爆破计数与锁定；<br>
 * 3. 未注入 Redis 时使用 {@link ReentrantLock} 保护的有界 LRU 内存表（防海量源 IP 攻击 OOM）；注入 {@link StringRedisTemplate} 后优先以 Redis 作为权威集群状态，遇到 Redis 故障时自动 Fail-Open 降级本机计算；<br>
 * 4. 内置 {@link Clock} 确定性时间接缝，支持无延时单测验证滑窗过期与锁自动解除。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class AdminLoginDefenseService {

    private static final String REDIS_FAIL_PREFIX = "login:fail:";
    private static final String REDIS_LOCK_PREFIX = "login:lock:";
    private static final int DEFAULT_MAX_TRACKED_ENTRIES = 10000;

    private final AtengAdminServerProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, FailureBucket> localBuckets;

    public AdminLoginDefenseService(AtengAdminServerProperties properties) {
        this(properties, null, Clock.systemUTC());
    }

    public AdminLoginDefenseService(AtengAdminServerProperties properties, StringRedisTemplate redisTemplate) {
        this(properties, redisTemplate, Clock.systemUTC());
    }

    public AdminLoginDefenseService(AtengAdminServerProperties properties,
                                    StringRedisTemplate redisTemplate,
                                    Clock clock) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        int maxCapacity = resolveMaxTrackedEntries();
        this.localBuckets = new LinkedHashMap<>(Math.min(maxCapacity, 256), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, FailureBucket> eldest) {
                return size() > maxCapacity;
            }
        };
    }

    /**
     * 解析客户端来源 IP：当防爆破服务可用时优先采用可信代理校验解析，否则安全回退至请求直连地址
     *
     * @param service 登录防爆破服务（可为 null）
     * @param request HTTP 请求（可为 null）
     * @return 客户端源 IP 字符串
     */
    public static String resolveClientIpOrRemote(AdminLoginDefenseService service, HttpServletRequest request) {
        if (service != null && request != null) {
            return service.resolveClientIp(request);
        }
        if (request == null || !StringUtils.hasText(request.getRemoteAddr())) {
            return "";
        }
        return stripAndNormalizeIp(request.getRemoteAddr());
    }

    /**
     * 解析经过反代防伪造校验的客户端真实 IP
     *
     * @param request HTTP 请求
     * @return 清洗端口后的真实 IP（不可用时返回空字符串）
     */
    public String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String remoteAddr = stripAndNormalizeIp(request.getRemoteAddr());
        AtengAdminServerProperties.LoginDefenseProperties cfg = defenseConfig();
        boolean trustProxy = cfg != null && (cfg.isTrustProxyHeaders()
                || (StringUtils.hasText(remoteAddr) && CidrMatcher.matchesAny(remoteAddr, cfg.getTrustedProxies())));
        if (!trustProxy) {
            return remoteAddr;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            int commaIdx = xff.indexOf(',');
            String firstHop = commaIdx >= 0 ? xff.substring(0, commaIdx) : xff;
            String normalizedHop = stripAndNormalizeIp(firstHop);
            if (StringUtils.hasText(normalizedHop)) {
                return normalizedHop;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            String normalizedReal = stripAndNormalizeIp(realIp);
            if (StringUtils.hasText(normalizedReal)) {
                return normalizedReal;
            }
        }
        return remoteAddr;
    }

    /**
     * 检查当前登录请求（账号与来源 IP 双轨）是否允许放行
     *
     * @param username 登录用户名
     * @param request  HTTP 请求
     * @return 防爆破决策结果
     */
    public LoginDefenseDecision checkAllowed(String username, HttpServletRequest request) {
        String clientIp = resolveClientIp(request);
        if (!isDefenseEnabled() || isWhitelistedIp(clientIp)) {
            return LoginDefenseDecision.allowed(maxFailures(), clientIp);
        }
        Instant now = clock.instant();
        String userKey = buildUserKey(username);
        String ipKey = buildIpKey(clientIp);
        long lockSeconds = Math.max(
                getRemainingLockSeconds(userKey, now),
                getRemainingLockSeconds(ipKey, now)
        );
        if (lockSeconds > 0) {
            return LoginDefenseDecision.locked(lockSeconds, clientIp);
        }
        int maxFailures = maxFailures();
        int currentFailures = Math.max(getFailureCount(userKey, now), getFailureCount(ipKey, now));
        return LoginDefenseDecision.allowed(Math.max(0, maxFailures - currentFailures), clientIp);
    }

    /**
     * 仅检查当前来源 IP 是否处于锁定状态（供登录页 GET 渲染锁定提醒）
     *
     * @param request HTTP 请求
     * @return 防爆破决策结果
     */
    public LoginDefenseDecision checkIpAllowed(HttpServletRequest request) {
        String clientIp = resolveClientIp(request);
        if (!isDefenseEnabled() || isWhitelistedIp(clientIp)) {
            return LoginDefenseDecision.allowed(maxFailures(), clientIp);
        }
        long lockSeconds = getRemainingLockSeconds(buildIpKey(clientIp), clock.instant());
        if (lockSeconds > 0) {
            return LoginDefenseDecision.locked(lockSeconds, clientIp);
        }
        return LoginDefenseDecision.allowed(maxFailures(), clientIp);
    }

    /**
     * 记录一次认证失败并依据阈值触发账号与 IP 临时锁定
     *
     * @param username 登录用户名
     * @param request  HTTP 请求
     * @return 记录后的最新决策（含剩余尝试次数或已触发锁定时长）
     */
    public LoginDefenseDecision recordFailure(String username, HttpServletRequest request) {
        String clientIp = resolveClientIp(request);
        if (!isDefenseEnabled() || isWhitelistedIp(clientIp)) {
            return LoginDefenseDecision.allowed(maxFailures(), clientIp);
        }
        Instant now = clock.instant();
        int maxFailures = maxFailures();
        Duration failureWindow = failureWindow();
        Duration lockDuration = lockDuration();

        int userFails = incrementFailure(buildUserKey(username), now, maxFailures, failureWindow, lockDuration);
        int ipFails = incrementFailure(buildIpKey(clientIp), now, maxFailures, failureWindow, lockDuration);
        int maxRecorded = Math.max(userFails, ipFails);

        long lockSeconds = Math.max(
                getRemainingLockSeconds(buildUserKey(username), now),
                getRemainingLockSeconds(buildIpKey(clientIp), now)
        );
        if (lockSeconds > 0 || maxRecorded >= maxFailures) {
            long effectiveLockSeconds = lockSeconds > 0 ? lockSeconds : Math.max(1L, lockDuration.getSeconds());
            return LoginDefenseDecision.locked(effectiveLockSeconds, clientIp);
        }
        return LoginDefenseDecision.allowed(Math.max(0, maxFailures - maxRecorded), clientIp);
    }

    /**
     * 登录成功后清除对应账号与来源 IP 的失败计数与锁定状态
     *
     * @param username 登录用户名
     * @param request  HTTP 请求
     */
    public void clearFailure(String username, HttpServletRequest request) {
        unlock(username, resolveClientIp(request));
    }

    /**
     * 判断指定用户名或客户端 IP 当前是否处于锁定状态
     *
     * @param username 用户名（可空）
     * @param clientIp 客户端 IP（可空）
     * @return true 若任一维度处于有效锁定中
     */
    public boolean isLocked(String username, String clientIp) {
        if (!isDefenseEnabled() || isWhitelistedIp(clientIp)) {
            return false;
        }
        Instant now = clock.instant();
        return getRemainingLockSeconds(buildUserKey(username), now) > 0
                || getRemainingLockSeconds(buildIpKey(clientIp), now) > 0;
    }

    /**
     * 人工解锁指定账号与 IP 的爆破计数和锁定标记
     *
     * @param username 用户名（可空）
     * @param clientIp 客户端 IP（可空）
     */
    public void unlock(String username, String clientIp) {
        String userKey = buildUserKey(username);
        String ipKey = buildIpKey(clientIp);
        lock.lock();
        try {
            if (userKey != null) {
                localBuckets.remove(userKey);
            }
            if (ipKey != null) {
                localBuckets.remove(ipKey);
            }
        } finally {
            lock.unlock();
        }
        deleteRedisKey(userKey);
        deleteRedisKey(ipKey);
    }

    /**
     * 清空本机全部防爆破计数（供测试沙箱重置使用）
     */
    public void clearAllLocal() {
        lock.lock();
        try {
            localBuckets.clear();
        } finally {
            lock.unlock();
        }
    }

    private long getRemainingLockSeconds(String trackKey, Instant now) {
        if (trackKey == null) {
            return 0L;
        }
        if (redisTemplate != null) {
            try {
                String lockRedisKey = redisLockKey(trackKey);
                String untilStr = redisTemplate.opsForValue().get(lockRedisKey);
                if (!StringUtils.hasText(untilStr)) {
                    return 0L;
                }
                Instant lockedUntil = Instant.parse(untilStr);
                if (lockedUntil.isAfter(now)) {
                    return Math.max(1L, Duration.between(now, lockedUntil).getSeconds());
                }
                redisTemplate.delete(lockRedisKey);
                return 0L;
            } catch (Exception ex) {
                log.warn("[Login Defense] Redis 查询锁定状态失败，降级使用本机状态（Fail-Open）: key=[{}], err=[{}]",
                        trackKey, ex.getMessage());
            }
        }
        lock.lock();
        try {
            FailureBucket bucket = localBuckets.get(trackKey);
            if (bucket == null || bucket.lockedUntil == null) {
                return 0L;
            }
            if (!bucket.lockedUntil.isAfter(now)) {
                bucket.lockedUntil = null;
                return 0L;
            }
            return Math.max(1L, Duration.between(now, bucket.lockedUntil).getSeconds());
        } finally {
            lock.unlock();
        }
    }

    private int getFailureCount(String trackKey, Instant now) {
        if (trackKey == null) {
            return 0;
        }
        if (redisTemplate != null) {
            try {
                String countStr = redisTemplate.opsForValue().get(redisFailKey(trackKey));
                return StringUtils.hasText(countStr) ? Integer.parseInt(countStr) : 0;
            } catch (Exception ex) {
                log.warn("[Login Defense] Redis 查询失败计数异常，降级本机计数（Fail-Open）: key=[{}], err=[{}]",
                        trackKey, ex.getMessage());
            }
        }
        lock.lock();
        try {
            FailureBucket bucket = localBuckets.get(trackKey);
            if (bucket == null) {
                return 0;
            }
            if (bucket.windowExpiresAt != null && !bucket.windowExpiresAt.isAfter(now)) {
                localBuckets.remove(trackKey);
                return 0;
            }
            return bucket.failures;
        } finally {
            lock.unlock();
        }
    }

    private int incrementFailure(String trackKey,
                                 Instant now,
                                 int maxFailures,
                                 Duration failureWindow,
                                 Duration lockDuration) {
        if (trackKey == null) {
            return 0;
        }
        if (redisTemplate != null) {
            try {
                ValueOperations<String, String> ops = redisTemplate.opsForValue();
                String failKey = redisFailKey(trackKey);
                Long countVal = ops.increment(failKey);
                if (countVal != null && countVal == 1L) {
                    redisTemplate.expire(failKey, failureWindow);
                }
                int redisCount = countVal != null ? countVal.intValue() : 1;
                if (redisCount >= maxFailures) {
                    Instant lockedUntil = now.plus(lockDuration);
                    ops.set(redisLockKey(trackKey), lockedUntil.toString(), lockDuration);
                }
                return redisCount;
            } catch (Exception ex) {
                log.warn("[Login Defense] Redis 递增失败计数异常，降级本机计数（Fail-Open）: key=[{}], err=[{}]",
                        trackKey, ex.getMessage());
            }
        }
        return incrementLocalFailure(trackKey, now, maxFailures, failureWindow, lockDuration);
    }

    private int incrementLocalFailure(String trackKey,
                                      Instant now,
                                      int maxFailures,
                                      Duration failureWindow,
                                      Duration lockDuration) {
        lock.lock();
        try {
            pruneExpiredEntriesIfNeeded(now);
            FailureBucket bucket = localBuckets.get(trackKey);
            if (bucket == null || (bucket.windowExpiresAt != null && !bucket.windowExpiresAt.isAfter(now)
                    && (bucket.lockedUntil == null || !bucket.lockedUntil.isAfter(now)))) {
                bucket = new FailureBucket(0, now.plus(failureWindow), null);
                localBuckets.put(trackKey, bucket);
            }
            bucket.failures++;
            bucket.windowExpiresAt = now.plus(failureWindow);
            if (bucket.failures >= maxFailures) {
                bucket.lockedUntil = now.plus(lockDuration);
            }
            return bucket.failures;
        } finally {
            lock.unlock();
        }
    }

    private void pruneExpiredEntriesIfNeeded(Instant now) {
        int maxCapacity = resolveMaxTrackedEntries();
        if (localBuckets.size() < maxCapacity) {
            return;
        }
        Iterator<Map.Entry<String, FailureBucket>> iterator = localBuckets.entrySet().iterator();
        while (iterator.hasNext()) {
            FailureBucket b = iterator.next().getValue();
            boolean windowExpired = b.windowExpiresAt == null || !b.windowExpiresAt.isAfter(now);
            boolean lockExpired = b.lockedUntil == null || !b.lockedUntil.isAfter(now);
            if (windowExpired && lockExpired) {
                iterator.remove();
            }
        }
    }

    private void deleteRedisKey(String trackKey) {
        if (trackKey == null || redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(List.of(redisFailKey(trackKey), redisLockKey(trackKey)));
        } catch (Exception ex) {
            log.warn("[Login Defense] Redis 清理登录防御键异常（Fail-Open）: key=[{}], err=[{}]",
                    trackKey, ex.getMessage());
        }
    }

    private boolean isDefenseEnabled() {
        AtengAdminServerProperties.LoginDefenseProperties cfg = defenseConfig();
        return cfg == null || cfg.isEnabled();
    }

    private boolean isWhitelistedIp(String clientIp) {
        if (!StringUtils.hasText(clientIp)) {
            return false;
        }
        AtengAdminServerProperties.LoginDefenseProperties cfg = defenseConfig();
        List<String> whitelist = cfg != null ? cfg.getIpWhitelist() : List.of("127.0.0.1", "::1");
        return whitelist != null && !whitelist.isEmpty() && CidrMatcher.matchesAny(clientIp, whitelist);
    }

    private int maxFailures() {
        AtengAdminServerProperties.LoginDefenseProperties cfg = defenseConfig();
        int val = cfg != null ? cfg.getMaxFailures() : 5;
        return val > 0 ? val : 5;
    }

    private Duration failureWindow() {
        AtengAdminServerProperties.LoginDefenseProperties cfg = defenseConfig();
        Duration val = cfg != null ? cfg.getFailureWindow() : null;
        return (val != null && !val.isNegative() && !val.isZero()) ? val : Duration.ofMinutes(15);
    }

    private Duration lockDuration() {
        AtengAdminServerProperties.LoginDefenseProperties cfg = defenseConfig();
        Duration val = cfg != null ? cfg.getLockDuration() : null;
        return (val != null && !val.isNegative() && !val.isZero()) ? val : Duration.ofMinutes(15);
    }

    private int resolveMaxTrackedEntries() {
        AtengAdminServerProperties.LoginDefenseProperties cfg = defenseConfig();
        int val = cfg != null ? cfg.getMaxTrackedEntries() : DEFAULT_MAX_TRACKED_ENTRIES;
        return val > 0 ? val : DEFAULT_MAX_TRACKED_ENTRIES;
    }

    private AtengAdminServerProperties.LoginDefenseProperties defenseConfig() {
        return properties != null ? properties.getLoginDefense() : null;
    }

    private String redisKeyPrefix() {
        if (properties != null && properties.getCluster() != null && StringUtils.hasText(properties.getCluster().getKeyPrefix())) {
            return properties.getCluster().getKeyPrefix().trim();
        }
        return "ateng:admin:";
    }

    private String redisFailKey(String trackKey) {
        return redisKeyPrefix() + REDIS_FAIL_PREFIX + trackKey;
    }

    private String redisLockKey(String trackKey) {
        return redisKeyPrefix() + REDIS_LOCK_PREFIX + trackKey;
    }

    private static String buildUserKey(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        return "user:" + username.trim().toLowerCase(Locale.ROOT);
    }

    private static String buildIpKey(String clientIp) {
        if (!StringUtils.hasText(clientIp)) {
            return null;
        }
        return "ip:" + clientIp.trim();
    }

    private static String stripAndNormalizeIp(String rawIp) {
        if (!StringUtils.hasText(rawIp)) {
            return "";
        }
        String stripped = CidrMatcher.stripPort(rawIp.trim());
        return stripped != null ? stripped.trim() : "";
    }

    private static final class FailureBucket {
        private int failures;
        private Instant windowExpiresAt;
        private Instant lockedUntil;

        private FailureBucket(int failures, Instant windowExpiresAt, Instant lockedUntil) {
            this.failures = failures;
            this.windowExpiresAt = windowExpiresAt;
            this.lockedUntil = lockedUntil;
        }
    }

    /**
     * 登录防爆破评估决策结果
     *
     * @author Ateng
     * @since 2026-11-21
     */
    @Getter
    public static final class LoginDefenseDecision {

        private final boolean allowed;
        private final boolean locked;
        private final int remainingAttempts;
        private final long remainingLockSeconds;
        private final String clientIp;

        private LoginDefenseDecision(boolean allowed,
                                     boolean locked,
                                     int remainingAttempts,
                                     long remainingLockSeconds,
                                     String clientIp) {
            this.allowed = allowed;
            this.locked = locked;
            this.remainingAttempts = remainingAttempts;
            this.remainingLockSeconds = remainingLockSeconds;
            this.clientIp = clientIp;
        }

        public static LoginDefenseDecision allowed(int remainingAttempts, String clientIp) {
            return new LoginDefenseDecision(true, false, Math.max(0, remainingAttempts), 0L, clientIp);
        }

        public static LoginDefenseDecision locked(long remainingLockSeconds, String clientIp) {
            return new LoginDefenseDecision(false, true, 0, Math.max(1L, remainingLockSeconds), clientIp);
        }
    }
}

package io.github.atengk.boot.satoken.defense;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.context.model.SaRequest;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.util.ValidatorUtil;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.github.atengk.boot.satoken.util.LoginHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录防暴力破解与防撞库双轨策略治理门面服务
 * 支持“账号连续输错惩罚 + 客户端 IP 自动嗅探与频率熔断”双轨防御；
 * 提供失败原子累计、成功对称清零、剩余机会实时查询与 Fail-Fast 快速失败前置防卫；
 * 自适应支持 Redis 分布式集群模式与带过期时间戳的线程安全本地内存容器降级模式。
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Slf4j
public class LoginFailureLockService {

    /**
     * 账号失败计数 Redis 存储键前缀
     */
    public static final String KEY_PREFIX = "ateng:satoken:login-fail:";

    /**
     * 客户端 IP 失败计数 Redis 存储键前缀
     */
    public static final String KEY_IP_PREFIX = "ateng:satoken:login-fail-ip:";

    /**
     * 客户端 IP 熔断锁定 Redis 存储键前缀
     */
    public static final String KEY_IP_LOCK_PREFIX = "ateng:satoken:login-lock-ip:";

    /**
     * 常用反向代理客户端 IP 候选请求头
     */
    private static final String[] IP_HEADER_CANDIDATES = {
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_CLIENT_IP",
            "HTTP_X_FORWARDED_FOR"
    };

    /**
     * 本地 IPv4 环回地址
     */
    public static final String LOCAL_IPV4 = "127.0.0.1";

    /**
     * 本地 IPv6 完整环回地址
     */
    public static final String LOCAL_IPV6 = "0:0:0:0:0:0:0:1";

    /**
     * 本地 IPv6 缩写环回地址
     */
    public static final String LOCAL_IPV6_SHORT = "::1";

    /**
     * 过滤标识 unknown
     */
    public static final String UNKNOWN = "unknown";

    /**
     * 单机内存降级容器最大容量阈值（防御恶意遍历攻击导致的内存无限膨胀）
     */
    private static final int MAX_LOCAL_FAIL_ENTRIES = 10_000;

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final SaTokenProperties properties;

    // 单机内存降级容器（当 Redis 基础设施不可用时自适应生效，实例级安全内聚）
    private final Map<String, FailItem> localFailMap = new ConcurrentHashMap<>();
    private final Map<String, FailItem> localIpFailMap = new ConcurrentHashMap<>();
    private final Map<String, Long> localIpLockMap = new ConcurrentHashMap<>();

    public LoginFailureLockService(ObjectProvider<StringRedisTemplate> redisTemplateProvider, SaTokenProperties properties) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.properties = properties;
    }

    // ==========================================
    // 1. 客户端 IP 自动嗅探与识别处理
    // ==========================================

    /**
     * 从当前 Sa-Token HTTP 请求上下文中透明自动嗅探客户端真实 IP
     *
     * @return 客户端真实 IP；若上下文无效或提取失败则返回空字符串
     */
    public String sniffClientIp() {
        try {
            if (SaManager.getSaTokenContext() != null && SaManager.getSaTokenContext().isValid()) {
                SaRequest request = SaHolder.getRequest();
                if (request != null) {
                    String ip = extractClientIp(request);
                    if (isValidIp(ip)) {
                        return ip;
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("未能从 SaTokenContext 嗅探客户端 IP: {}", ex.getMessage());
        }

        // 弹性防御：当 SaTokenContext 不可用（如局部测试或未过 Filter）时，自适应回退至 Spring Web RequestContextHolder
        try {
            org.springframework.web.context.request.RequestAttributes attrs =
                    org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes servletAttrs) {
                jakarta.servlet.http.HttpServletRequest servletRequest = servletAttrs.getRequest();
                if (servletRequest != null) {
                    for (String header : IP_HEADER_CANDIDATES) {
                        String value = servletRequest.getHeader(header);
                        if (value == null || value.isBlank()) {
                            continue;
                        }
                        if (value.contains(",")) {
                            String[] parts = value.split(",");
                            for (String part : parts) {
                                String candidate = part.trim();
                                if (isValidIp(candidate)) {
                                    return normalizeIp(candidate);
                                }
                            }
                        } else {
                            String candidate = value.trim();
                            if (isValidIp(candidate)) {
                                return normalizeIp(candidate);
                            }
                        }
                    }
                    String remoteAddr = servletRequest.getRemoteAddr();
                    if (isValidIp(remoteAddr)) {
                        return normalizeIp(remoteAddr);
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("未能从 RequestContextHolder 嗅探客户端 IP: {}", ex.getMessage());
        }

        return "";
    }

    /**
     * 从指定 SaRequest 中提取客户端真实 IP 地址（解析反向代理头与底层 Servlet 连接地址）
     *
     * @param request SaRequest 对象
     * @return 客户端真实 IP 地址；若无效则返回空字符串
     */
    public String extractClientIp(SaRequest request) {
        if (request == null) {
            return "";
        }
        for (String header : IP_HEADER_CANDIDATES) {
            String value = request.getHeader(header);
            if (value == null || value.isBlank()) {
                continue;
            }
            if (value.contains(",")) {
                String[] parts = value.split(",");
                for (String part : parts) {
                    String candidate = part.trim();
                    if (isValidIp(candidate)) {
                        return normalizeIp(candidate);
                    }
                }
            } else {
                String candidate = value.trim();
                if (isValidIp(candidate)) {
                    return normalizeIp(candidate);
                }
            }
        }

        try {
            Object source = request.getSource();
            if (source instanceof jakarta.servlet.http.HttpServletRequest servletRequest) {
                String remoteAddr = servletRequest.getRemoteAddr();
                if (isValidIp(remoteAddr)) {
                    return normalizeIp(remoteAddr);
                }
            }
        } catch (Exception ignored) {
        }

        return "";
    }

    /**
     * 解析并确定客户端 IP（优先使用显式入参，入参为空时自动嗅探上下文）
     *
     * @param clientIp 显式指定的客户端 IP（可为 null）
     * @return 规范化后的客户端真实 IP；无有效 IP 时返回空字符串
     */
    public String resolveClientIp(String clientIp) {
        if (clientIp != null && !clientIp.isBlank()) {
            return normalizeIp(clientIp);
        }
        return sniffClientIp();
    }

    /**
     * 规范化 IP 地址（剥离 IPv6 scope 标识，并将 IPv6 本地环回转换为标准 IPv4 环回）
     *
     * @param ip 原始 IP 地址
     * @return 规范化后的 IP 地址
     */
    public String normalizeIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "";
        }
        String trimmed = ip.trim();
        int scopeIndex = trimmed.indexOf('%');
        if (scopeIndex >= 0) {
            trimmed = trimmed.substring(0, scopeIndex);
        }
        if (LOCAL_IPV6.equals(trimmed) || LOCAL_IPV6_SHORT.equals(trimmed) || (LOCAL_IPV6 + "%0").equals(trimmed)) {
            return LOCAL_IPV4;
        }
        return trimmed;
    }

    /**
     * 校验 IP 字符串是否合法有效（排除 null、空白字符与 unknown）
     *
     * @param ip 待校验 IP 字符串
     * @return true 表示为合法有效 IP
     */
    public boolean isValidIp(String ip) {
        if (ip == null || ip.isBlank() || UNKNOWN.equalsIgnoreCase(ip.trim())) {
            return false;
        }
        String trimmed = ip.trim();
        if (LOCAL_IPV4.equals(trimmed) || LOCAL_IPV6.equals(trimmed) || LOCAL_IPV6_SHORT.equals(trimmed)) {
            return true;
        }
        return ValidatorUtil.isIp(trimmed);
    }

    /**
     * 判定客户端 IP 是否命中白名单（白名单 IP 豁免 IP 维度连续输错熔断）
     *
     * @param clientIp 待判定的客户端 IP
     * @return true 表示命中白名单豁免，false 表示未命中
     */
    public boolean isIpWhiteListed(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return false;
        }
        if (properties == null || properties.getLoginDefense() == null) {
            return false;
        }
        List<String> whiteList = properties.getLoginDefense().getIpWhiteList();
        if (whiteList == null || whiteList.isEmpty()) {
            return false;
        }
        String normalizedTarget = normalizeIp(clientIp);
        for (String rule : whiteList) {
            if (rule == null || rule.isBlank()) {
                continue;
            }
            if (matchIpRule(normalizedTarget, rule.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchIpRule(String targetIp, String rule) {
        if (targetIp.equalsIgnoreCase(rule)) {
            return true;
        }
        if (normalizeIp(rule).equalsIgnoreCase(targetIp)) {
            return true;
        }
        if (rule.contains("/")) {
            return matchCidr(targetIp, rule);
        }
        return false;
    }

    private boolean matchCidr(String targetIp, String cidr) {
        int slashIndex = cidr.indexOf('/');
        if (slashIndex <= 0) {
            return false;
        }
        String baseIp = cidr.substring(0, slashIndex).trim();
        String prefixStr = cidr.substring(slashIndex + 1).trim();
        int prefixLength;
        try {
            prefixLength = Integer.parseInt(prefixStr);
        } catch (NumberFormatException e) {
            return false;
        }
        if (!ValidatorUtil.isIp(targetIp) || !ValidatorUtil.isIp(baseIp)) {
            return false;
        }
        try {
            InetAddress targetAddr = InetAddress.getByName(targetIp);
            InetAddress baseAddr = InetAddress.getByName(baseIp);
            byte[] targetBytes = targetAddr.getAddress();
            byte[] baseBytes = baseAddr.getAddress();
            if (targetBytes.length != baseBytes.length) {
                return false;
            }
            int maxPrefix = targetBytes.length * 8;
            if (prefixLength < 0 || prefixLength > maxPrefix) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (targetBytes[i] != baseBytes[i]) {
                    return false;
                }
            }
            if (remainingBits > 0) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((targetBytes[fullBytes] & mask) != (baseBytes[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==========================================
    // 2. 双轨连续失败累计与惩罚熔断
    // ==========================================

    /**
     * 构造带多租户与多账号体系作用域的防碰撞定界账号键（长度前缀定界编码）
     * <p>
     * 对 {@code tenantId}、{@code loginType} 与 {@code account} 均附加十进制长度前缀与定界符，
     * 彻底杜绝参数内含 {@code :}、{@code |} 或空租户引起的缓存键/封禁主体碰撞。
     * </p>
     *
     * @param tenantId  租户标识（可为 null 或空串）
     * @param loginType 账号体系标识（为空时默认回退 {@code "login"}）
     * @param account   登录账号/用户名/标识
     * @return 防碰撞作用域键；当 {@code account} 为空或空白时返回 null
     */
    public String buildScopedAccountKey(String tenantId, String loginType, String account) {
        if (account == null || account.isBlank()) {
            return null;
        }
        String normTenant = (tenantId == null || tenantId.isBlank()) ? "" : tenantId.trim();
        String normType = normalizeLoginType(loginType);
        String normAccount = account.trim();
        return "scoped:"
                + normTenant.length() + ":" + normTenant
                + "|" + normType.length() + ":" + normType
                + "|" + normAccount.length() + ":" + normAccount;
    }

    /**
     * 记录一次登录密码错误，原子累计账号与客户端 IP 失败次数（自动嗅探客户端 IP）
     *
     * @param account 登录账号/用户名/标识
     * @return 账号剩余可重试次数（若账号或 IP 达到阈值触发锁定则返回 0）
     */
    public int recordFailure(String account) {
        return recordFailure(account, null);
    }

    /**
     * 记录一次登录密码错误，原子累计指定账号与客户端 IP 的失败次数并在超限时触发锁定
     *
     * @param account  登录账号/用户名/标识
     * @param clientIp 显式指定的客户端 IP（为 null 时自动嗅探当前 HTTP 请求）
     * @return 剩余可重试次数（若账号或 IP 达到阈值触发锁定则返回 0）
     */
    public int recordFailure(String account, String clientIp) {
        return recordFailureInternal(legacyTarget(account), clientIp);
    }

    /**
     * 记录指定租户与账号体系下的一次登录密码错误（自动嗅探客户端 IP）
     *
     * @param tenantId  租户标识
     * @param loginType 账号体系标识（为空时默认 {@code "login"}）
     * @param account   登录账号/用户名/标识
     * @return 剩余可重试次数（若账号或 IP 达到阈值触发锁定则返回 0）
     */
    public int recordFailure(String tenantId, String loginType, String account) {
        return recordFailure(tenantId, loginType, account, null);
    }

    /**
     * 记录指定租户、账号体系与客户端 IP 的一次登录密码错误并在超限时触发隔离锁定
     *
     * @param tenantId  租户标识
     * @param loginType 账号体系标识（为空时默认 {@code "login"}）
     * @param account   登录账号/用户名/标识
     * @param clientIp  显式指定的客户端 IP（为 null 时自动嗅探当前 HTTP 请求）
     * @return 剩余可重试次数（若账号或 IP 达到阈值触发锁定则返回 0）
     */
    public int recordFailure(String tenantId, String loginType, String account, String clientIp) {
        return recordFailureInternal(scopedTarget(tenantId, loginType, account), clientIp);
    }

    private int recordFailureInternal(AccountTarget target, String clientIp) {
        String ip = resolveClientIp(clientIp);

        // 1. IP 维度连续输错累计与熔断判定
        int ipRemain = -1;
        if (ip != null && !ip.isBlank() && !isIpWhiteListed(ip)) {
            ipRemain = recordIpFailure(ip);
        }

        // 2. 账号维度连续输错累计与封禁判定
        int accountRemain = -1;
        if (target != null) {
            if (!isAccountDisabled(target)) {
                accountRemain = recordAccountFailure(target);
            } else {
                accountRemain = 0;
            }
        }

        // 3. 若 IP 或账号任一维度已被锁定，则返回 0
        if ((ip != null && !ip.isBlank() && isIpLocked(ip)) || (target != null && isAccountDisabled(target))) {
            return 0;
        }

        if (accountRemain >= 0) {
            return accountRemain;
        }
        return ipRemain >= 0 ? ipRemain : 0;
    }

    /**
     * 记录账号维度的登录失败并执行阈值封禁判定
     */
    private int recordAccountFailure(AccountTarget target) {
        int maxRetry = getMaxRetryCount();
        Duration lockDuration = getLockTime();
        Duration windowDuration = getWindowTime();

        StringRedisTemplate redisTemplate = getRedisTemplate();
        if (redisTemplate != null) {
            String key = KEY_PREFIX + target.cacheKey();
            try {
                Long count = redisTemplate.opsForValue().increment(key);
                if (count != null && count == 1) {
                    redisTemplate.expire(key, windowDuration);
                } else if (count != null && count > 1) {
                    Long ttl = redisTemplate.getExpire(key);
                    if (ttl != null && ttl == -1L) {
                        redisTemplate.expire(key, windowDuration);
                    }
                }

                if (count != null && count >= maxRetry) {
                    disableAccount(target, lockDuration);
                    redisTemplate.delete(key);
                    log.warn("【防暴力破解】账号连续密码错误达到上限，已触发账号锁定: target={}, count={}, lockDuration={}",
                            target.cacheKey(), count, lockDuration);
                    return 0;
                }
                int current = count != null ? count.intValue() : 0;
                return Math.max(0, maxRetry - current);
            } catch (Exception ex) {
                log.warn("Redis 记录登录失败次数异常，降级回退至本地内存模式: target={}, error={}", target.cacheKey(), ex.toString());
            }
        }

        return recordFailureLocal(target, maxRetry, lockDuration, windowDuration);
    }

    /**
     * 本地单机内存模式账号原子递增与封禁判定
     */
    private int recordFailureLocal(AccountTarget target, int maxRetry, Duration lockDuration, Duration windowDuration) {
        String cacheKey = target.cacheKey();
        if (localFailMap.size() >= MAX_LOCAL_FAIL_ENTRIES) {
            cleanupExpiredLocalEntries();
            if (localFailMap.size() >= MAX_LOCAL_FAIL_ENTRIES && !localFailMap.containsKey(cacheKey)) {
                log.warn("【防暴力破解/内存保护】单机内存失败计数容器已达到上限阈值 {}，拒绝录入新账号: target={}",
                        MAX_LOCAL_FAIL_ENTRIES, cacheKey);
                return maxRetry;
            }
        }

        long expireAt = System.currentTimeMillis() + windowDuration.toMillis();
        FailItem item = localFailMap.compute(cacheKey, (k, old) -> {
            if (old == null || old.isExpired()) {
                return new FailItem(1, expireAt);
            }
            old.getCount().incrementAndGet();
            return old;
        });

        int current = item.getCount().get();
        if (current >= maxRetry) {
            disableAccount(target, lockDuration);
            localFailMap.remove(cacheKey);
            log.warn("【防暴力破解/内存模式】账号连续密码错误达到上限，已触发账号锁定: target={}, count={}, lockDuration={}",
                    cacheKey, current, lockDuration);
            return 0;
        }
        return Math.max(0, maxRetry - current);
    }

    /**
     * 记录客户端 IP 维度的登录失败并在达到阈值时触发熔断锁定
     *
     * @param clientIp 客户端 IP（为 null 时自动嗅探当前 HTTP 请求）
     * @return IP 剩余可尝试次数（若被锁定或达到阈值则返回 0；白名单 IP 返回 ipMaxRetryCount）
     */
    public int recordIpFailure(String clientIp) {
        String ip = resolveClientIp(clientIp);
        if (ip == null || ip.isBlank() || isIpWhiteListed(ip)) {
            return getIpMaxRetryCount();
        }
        if (isIpLocked(ip)) {
            return 0;
        }

        int ipMaxRetry = getIpMaxRetryCount();
        Duration ipLockDuration = getIpLockTime();
        Duration ipWindowDuration = getIpWindowTime();

        StringRedisTemplate redisTemplate = getRedisTemplate();
        if (redisTemplate != null) {
            String failKey = KEY_IP_PREFIX + ip;
            String lockKey = KEY_IP_LOCK_PREFIX + ip;
            try {
                Long count = redisTemplate.opsForValue().increment(failKey);
                if (count != null && count == 1) {
                    redisTemplate.expire(failKey, ipWindowDuration);
                } else if (count != null && count > 1) {
                    Long ttl = redisTemplate.getExpire(failKey);
                    if (ttl != null && ttl == -1L) {
                        redisTemplate.expire(failKey, ipWindowDuration);
                    }
                }

                if (count != null && count >= ipMaxRetry) {
                    redisTemplate.opsForValue().set(lockKey, "LOCKED", ipLockDuration);
                    redisTemplate.delete(failKey);
                    log.warn("【防暴力破解】客户端 IP 连续登录失败次数达到上限，已触发 IP 临时熔断: ip={}, count={}, lockDuration={}",
                            ip, count, ipLockDuration);
                    return 0;
                }
                int current = count != null ? count.intValue() : 0;
                return Math.max(0, ipMaxRetry - current);
            } catch (Exception ex) {
                log.warn("Redis 记录客户端 IP 登录失败次数异常，降级回退至本地内存模式: ip={}, error={}", ip, ex.toString());
            }
        }

        return recordIpFailureLocal(ip, ipMaxRetry, ipLockDuration, ipWindowDuration);
    }

    /**
     * 本地单机内存模式客户端 IP 原子递增与熔断锁定
     */
    private int recordIpFailureLocal(String ip, int ipMaxRetry, Duration ipLockDuration, Duration ipWindowDuration) {
        if (localIpFailMap.size() >= MAX_LOCAL_FAIL_ENTRIES || localIpLockMap.size() >= MAX_LOCAL_FAIL_ENTRIES) {
            cleanupExpiredLocalIpEntries();
            if (localIpFailMap.size() >= MAX_LOCAL_FAIL_ENTRIES && !localIpFailMap.containsKey(ip)) {
                log.warn("【防暴力破解/内存保护】单机内存客户端 IP 失败计数容器已达到上限阈值 {}，拒绝录入新 IP: ip={}",
                        MAX_LOCAL_FAIL_ENTRIES, ip);
                return ipMaxRetry;
            }
        }

        if (isIpLockedLocal(ip)) {
            return 0;
        }

        long expireAt = System.currentTimeMillis() + ipWindowDuration.toMillis();
        FailItem item = localIpFailMap.compute(ip, (k, old) -> {
            if (old == null || old.isExpired()) {
                return new FailItem(1, expireAt);
            }
            old.getCount().incrementAndGet();
            return old;
        });

        int current = item.getCount().get();
        if (current >= ipMaxRetry) {
            localIpLockMap.put(ip, System.currentTimeMillis() + ipLockDuration.toMillis());
            localIpFailMap.remove(ip);
            log.warn("【防暴力破解/内存模式】客户端 IP 连续登录失败次数达到上限，已触发 IP 临时熔断: ip={}, count={}, lockDuration={}",
                    ip, current, ipLockDuration);
            return 0;
        }
        return Math.max(0, ipMaxRetry - current);
    }

    private void cleanupExpiredLocalEntries() {
        localFailMap.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isExpired());
    }

    private void cleanupExpiredLocalIpEntries() {
        long now = System.currentTimeMillis();
        localIpFailMap.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isExpired());
        localIpLockMap.entrySet().removeIf(entry -> entry.getValue() == null || now > entry.getValue());
    }

    // ==========================================
    // 3. 登录成功对称清零与手动解封
    // ==========================================

    /**
     * 登录成功时原子清空目标账号的失败计数器
     *
     * @param account 登录账号/用户名/标识
     */
    public void clearFailure(String account) {
        clearFailureInternal(legacyTarget(account));
    }

    /**
     * 登录成功时原子清空指定租户与账号体系下目标账号的失败计数器
     *
     * @param tenantId  租户标识
     * @param loginType 账号体系标识（为空时默认 {@code "login"}）
     * @param account   登录账号/用户名/标识
     */
    public void clearFailure(String tenantId, String loginType, String account) {
        clearFailureInternal(scopedTarget(tenantId, loginType, account));
    }

    private void clearFailureInternal(AccountTarget target) {
        if (target == null) {
            return;
        }
        String cacheKey = target.cacheKey();
        StringRedisTemplate redisTemplate = getRedisTemplate();
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(KEY_PREFIX + cacheKey);
            } catch (Exception ex) {
                log.warn("Redis 清除登录失败计数异常: target={}, error={}", cacheKey, ex.toString());
            }
        }
        localFailMap.remove(cacheKey);
        log.debug("已清空账号登录失败计数器: target={}", cacheKey);
    }

    /**
     * 清空指定客户端 IP 的失败计数与熔断锁定状态
     *
     * @param clientIp 客户端 IP（为 null 时自动嗅探当前 HTTP 请求）
     */
    public void clearIpFailure(String clientIp) {
        String ip = resolveClientIp(clientIp);
        if (ip == null || ip.isBlank()) {
            return;
        }

        StringRedisTemplate redisTemplate = getRedisTemplate();
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(KEY_IP_PREFIX + ip);
                redisTemplate.delete(KEY_IP_LOCK_PREFIX + ip);
            } catch (Exception ex) {
                log.warn("Redis 清除客户端 IP 失败计数与锁定状态异常: ip={}, error={}", ip, ex.toString());
            }
        }
        localIpFailMap.remove(ip);
        localIpLockMap.remove(ip);
        log.debug("已清空客户端 IP 登录失败计数器与锁定状态: ip={}", ip);
    }

    /**
     * 手动解除客户端 IP 熔断锁定
     *
     * @param clientIp 客户端 IP（为 null 时自动嗅探当前 HTTP 请求）
     * @see #clearIpFailure(String)
     */
    public void unlockIp(String clientIp) {
        clearIpFailure(clientIp);
    }

    /**
     * 同时清空账号与客户端 IP 的失败计数与锁定状态
     *
     * @param account  登录账号
     * @param clientIp 客户端 IP
     */
    public void clearAllFailure(String account, String clientIp) {
        clearFailure(account);
        clearIpFailure(clientIp);
    }

    /**
     * 同时清空指定租户与账号体系下的账号失败计数及客户端 IP 失败/锁定状态
     *
     * @param tenantId  租户标识
     * @param loginType 账号体系标识（为空时默认 {@code "login"}）
     * @param account   登录账号
     * @param clientIp  客户端 IP
     */
    public void clearAllFailure(String tenantId, String loginType, String account, String clientIp) {
        clearFailure(tenantId, loginType, account);
        clearIpFailure(clientIp);
    }

    // ==========================================
    // 4. 状态与剩余机会实时查询
    // ==========================================

    /**
     * 查询指定账号的剩余可重试次数
     *
     * @param account 登录账号/用户名/标识
     * @return 剩余可尝试次数（下限为 0；若已被锁定则直接返回 0）
     */
    public int getRemainRetryCount(String account) {
        return getRemainRetryCountInternal(legacyTarget(account));
    }

    /**
     * 查询指定租户与账号体系下目标账号的剩余可重试次数
     *
     * @param tenantId  租户标识
     * @param loginType 账号体系标识（为空时默认 {@code "login"}）
     * @param account   登录账号/用户名/标识
     * @return 剩余可尝试次数（下限为 0；若已被锁定则直接返回 0）
     */
    public int getRemainRetryCount(String tenantId, String loginType, String account) {
        return getRemainRetryCountInternal(scopedTarget(tenantId, loginType, account));
    }

    private int getRemainRetryCountInternal(AccountTarget target) {
        if (target == null) {
            return 0;
        }
        if (isAccountDisabled(target)) {
            return 0;
        }
        int maxRetry = getMaxRetryCount();
        int current = getFailureCountInternal(target);
        return Math.max(0, maxRetry - current);
    }

    /**
     * 获取指定账号当前已累计的失败次数
     *
     * @param account 登录账号/用户名/标识
     * @return 当前失败次数（不存在或过期时返回 0）
     */
    public int getFailureCount(String account) {
        return getFailureCountInternal(legacyTarget(account));
    }

    /**
     * 获取指定租户与账号体系下目标账号当前已累计的失败次数
     *
     * @param tenantId  租户标识
     * @param loginType 账号体系标识（为空时默认 {@code "login"}）
     * @param account   登录账号/用户名/标识
     * @return 当前失败次数（不存在或过期时返回 0）
     */
    public int getFailureCount(String tenantId, String loginType, String account) {
        return getFailureCountInternal(scopedTarget(tenantId, loginType, account));
    }

    private int getFailureCountInternal(AccountTarget target) {
        if (target == null) {
            return 0;
        }
        if (isAccountDisabled(target)) {
            return getMaxRetryCount();
        }
        String cacheKey = target.cacheKey();
        StringRedisTemplate redisTemplate = getRedisTemplate();
        if (redisTemplate != null) {
            try {
                String val = redisTemplate.opsForValue().get(KEY_PREFIX + cacheKey);
                if (val != null && !val.isBlank()) {
                    return Integer.parseInt(val);
                }
                return 0;
            } catch (Exception ex) {
                log.warn("Redis 读取登录失败次数异常，降级读取内存: target={}, error={}", cacheKey, ex.toString());
            }
        }

        FailItem item = localFailMap.get(cacheKey);
        if (item != null) {
            if (!item.isExpired()) {
                return item.getCount().get();
            }
            localFailMap.remove(cacheKey);
        }
        return 0;
    }

    /**
     * 查询指定客户端 IP 当前已累计的失败次数
     *
     * @param clientIp 客户端 IP（为 null 时自动嗅探当前 HTTP 请求）
     * @return 当前失败次数（白名单、不存在或已过期时返回 0）
     */
    public int getIpFailureCount(String clientIp) {
        String ip = resolveClientIp(clientIp);
        if (ip == null || ip.isBlank() || isIpWhiteListed(ip)) {
            return 0;
        }
        if (isIpLocked(ip)) {
            return getIpMaxRetryCount();
        }

        StringRedisTemplate redisTemplate = getRedisTemplate();
        if (redisTemplate != null) {
            try {
                String val = redisTemplate.opsForValue().get(KEY_IP_PREFIX + ip);
                if (val != null && !val.isBlank()) {
                    return Integer.parseInt(val);
                }
                return 0;
            } catch (Exception ex) {
                log.warn("Redis 读取客户端 IP 失败次数异常: ip={}, error={}", ip, ex.toString());
            }
        }

        FailItem item = localIpFailMap.get(ip);
        if (item != null) {
            if (!item.isExpired()) {
                return item.getCount().get();
            }
            localIpFailMap.remove(ip);
        }
        return 0;
    }

    /**
     * 查询指定客户端 IP 剩余可尝试次数
     *
     * @param clientIp 客户端 IP（为 null 时自动嗅探当前 HTTP 请求）
     * @return 剩余可尝试次数（若被锁定则返回 0；白名单 IP 返回 ipMaxRetryCount）
     */
    public int getIpRemainRetryCount(String clientIp) {
        String ip = resolveClientIp(clientIp);
        if (ip == null || ip.isBlank() || isIpWhiteListed(ip)) {
            return getIpMaxRetryCount();
        }
        if (isIpLocked(ip)) {
            return 0;
        }
        int maxRetry = getIpMaxRetryCount();
        int current = getIpFailureCount(ip);
        return Math.max(0, maxRetry - current);
    }

    /**
     * 校验指定账号是否处于锁定/封禁状态
     *
     * @param account 登录账号/用户名/标识
     * @return true 表示已被封禁锁定，false 表示正常未锁定
     */
    public boolean isLocked(String account) {
        return isLockedInternal(legacyTarget(account));
    }

    /**
     * 校验指定账号或客户端 IP 是否处于锁定状态（任一维度锁定即返回 true）
     *
     * @param account  登录账号/标识
     * @param clientIp 客户端 IP
     * @return true 表示账号或客户端 IP 处于锁定状态
     */
    public boolean isLocked(String account, String clientIp) {
        return isLockedInternal(legacyTarget(account), clientIp);
    }

    /**
     * 校验指定租户与账号体系下目标账号是否处于锁定/封禁状态
     *
     * @param tenantId  租户标识
     * @param loginType 账号体系标识（为空时默认 {@code "login"}）
     * @param account   登录账号/用户名/标识
     * @return true 表示已被封禁锁定，false 表示正常未锁定
     */
    public boolean isLocked(String tenantId, String loginType, String account) {
        return isLockedInternal(scopedTarget(tenantId, loginType, account));
    }

    /**
     * 校验指定租户与账号体系下目标账号或客户端 IP 是否处于锁定状态（任一维度锁定即返回 true）
     *
     * @param tenantId  租户标识
     * @param loginType 账号体系标识（为空时默认 {@code "login"}）
     * @param account   登录账号/用户名/标识
     * @param clientIp  客户端 IP
     * @return true 表示账号或客户端 IP 处于锁定状态
     */
    public boolean isLocked(String tenantId, String loginType, String account, String clientIp) {
        return isLockedInternal(scopedTarget(tenantId, loginType, account), clientIp);
    }

    private boolean isLockedInternal(AccountTarget target) {
        return target != null && isAccountDisabled(target);
    }

    private boolean isLockedInternal(AccountTarget target, String clientIp) {
        String ip = resolveClientIp(clientIp);
        if (ip != null && !ip.isBlank() && isIpLocked(ip)) {
            return true;
        }
        return isLockedInternal(target);
    }

    /**
     * 校验指定客户端 IP 是否处于熔断锁定状态
     *
     * @param clientIp 客户端 IP（为 null 时自动嗅探当前 HTTP 请求）
     * @return true 表示已被熔断锁定，false 表示正常未锁定（白名单 IP 始终为 false）
     */
    public boolean isIpLocked(String clientIp) {
        String ip = resolveClientIp(clientIp);
        if (ip == null || ip.isBlank() || isIpWhiteListed(ip)) {
            return false;
        }

        StringRedisTemplate redisTemplate = getRedisTemplate();
        if (redisTemplate != null) {
            try {
                Boolean hasKey = redisTemplate.hasKey(KEY_IP_LOCK_PREFIX + ip);
                if (Boolean.TRUE.equals(hasKey)) {
                    return true;
                }
            } catch (Exception ex) {
                log.warn("Redis 读取客户端 IP 锁定状态异常，降级读取内存: ip={}, error={}", ip, ex.toString());
            }
        }

        return isIpLockedLocal(ip);
    }

    private boolean isIpLockedLocal(String ip) {
        Long expireAt = localIpLockMap.get(ip);
        if (expireAt != null) {
            if (System.currentTimeMillis() < expireAt) {
                return true;
            }
            localIpLockMap.remove(ip);
        }
        return false;
    }

    /**
     * 获取客户端 IP 剩余锁定时间（秒）
     *
     * @param clientIp 客户端 IP（为 null 时自动嗅探当前 HTTP 请求）
     * @return 剩余锁定秒数，未被锁定或白名单 IP 返回 0
     */
    public long getIpLockTime(String clientIp) {
        String ip = resolveClientIp(clientIp);
        if (ip == null || ip.isBlank() || isIpWhiteListed(ip)) {
            return 0L;
        }

        StringRedisTemplate redisTemplate = getRedisTemplate();
        if (redisTemplate != null) {
            try {
                Long expire = redisTemplate.getExpire(KEY_IP_LOCK_PREFIX + ip);
                if (expire != null && expire > 0) {
                    return expire;
                }
            } catch (Exception ex) {
                log.warn("Redis 读取客户端 IP 锁定剩余时间异常: ip={}, error={}", ip, ex.toString());
            }
        }

        Long expireAt = localIpLockMap.get(ip);
        if (expireAt != null) {
            long remainMs = expireAt - System.currentTimeMillis();
            if (remainMs > 0) {
                return remainMs / 1000;
            }
            localIpLockMap.remove(ip);
        }
        return 0L;
    }

    /**
     * 获取账号剩余封禁时间（秒）
     *
     * @param account 登录账号/用户名/标识
     * @return 剩余封禁秒数，未被封禁时返回 0
     */
    public long getDisableTime(String account) {
        return getDisableTimeInternal(legacyTarget(account));
    }

    /**
     * 获取指定租户与账号体系下目标账号的剩余封禁时间（秒）
     *
     * @param tenantId  租户标识
     * @param loginType 账号体系标识（为空时默认 {@code "login"}）
     * @param account   登录账号/用户名/标识
     * @return 剩余封禁秒数，未被封禁时返回 0
     */
    public long getDisableTime(String tenantId, String loginType, String account) {
        return getDisableTimeInternal(scopedTarget(tenantId, loginType, account));
    }

    private long getDisableTimeInternal(AccountTarget target) {
        if (target == null) {
            return 0L;
        }
        long disableTime = getAccountDisableTime(target);
        return Math.max(0L, disableTime);
    }

    /**
     * 获取账号剩余锁定时间（秒）
     *
     * @param account 登录账号/用户名/标识
     * @return 剩余封禁秒数，未被封禁时返回 0
     * @see #getDisableTime(String)
     */
    public long getLockTime(String account) {
        return getDisableTime(account);
    }

    /**
     * 获取指定租户与账号体系下目标账号的剩余锁定时间（秒）
     *
     * @param tenantId  租户标识
     * @param loginType 账号体系标识（为空时默认 {@code "login"}）
     * @param account   登录账号/用户名/标识
     * @return 剩余封禁秒数，未被封禁时返回 0
     * @see #getDisableTime(String, String, String)
     */
    public long getLockTime(String tenantId, String loginType, String account) {
        return getDisableTime(tenantId, loginType, account);
    }

    // ==========================================
    // 5. Fail-Fast 快速失败前置防卫
    // ==========================================

    /**
     * 校验指定账号与当前请求客户端 IP 是否已被锁定，若被锁定则直接抛出 403 异常 (Fail-Fast)
     *
     * @param account 登录账号/用户名/标识（可为 null）
     */
    public void checkLocked(String account) {
        checkLocked(account, null);
    }

    /**
     * 校验指定账号与客户端 IP 是否已被锁定，若被锁定则直接 Fail-Fast 阻断
     *
     * @param account  登录账号/用户名/标识（可为 null）
     * @param clientIp 客户端真实 IP（为 null 时自动嗅探当前 HTTP 请求）
     */
    public void checkLocked(String account, String clientIp) {
        checkLockedInternal(legacyTarget(account), clientIp);
    }

    /**
     * 校验指定租户与账号体系下目标账号与当前请求客户端 IP 是否已被锁定，若被锁定则 Fail-Fast 阻断
     *
     * @param tenantId  租户标识
     * @param loginType 账号体系标识（为空时默认 {@code "login"}）
     * @param account   登录账号/用户名/标识（可为 null）
     */
    public void checkLocked(String tenantId, String loginType, String account) {
        checkLocked(tenantId, loginType, account, null);
    }

    /**
     * 校验指定租户与账号体系下目标账号与客户端 IP 是否已被锁定，若被锁定则 Fail-Fast 阻断
     *
     * @param tenantId  租户标识
     * @param loginType 账号体系标识（为空时默认 {@code "login"}）
     * @param account   登录账号/用户名/标识（可为 null）
     * @param clientIp  客户端真实 IP（为 null 时自动嗅探当前 HTTP 请求）
     */
    public void checkLocked(String tenantId, String loginType, String account, String clientIp) {
        checkLockedInternal(scopedTarget(tenantId, loginType, account), clientIp);
    }

    private void checkLockedInternal(AccountTarget target, String clientIp) {
        // 优先防卫客户端 IP 熔断
        checkIpLocked(clientIp);

        // 防卫账号封禁
        if (target != null) {
            checkAccountDisabled(target);
        }
    }

    /**
     * 校验当前上下文中的客户端真实 IP 是否已被熔断锁定
     */
    public void checkIpLocked() {
        checkIpLocked(null);
    }

    /**
     * 校验客户端真实 IP 是否已被熔断锁定，若锁定直接 Fail-Fast 抛出 HTTP 200 + code: 403 业务异常
     *
     * @param clientIp 客户端 IP（为 null 时自动嗅探当前 HTTP 请求）
     */
    public void checkIpLocked(String clientIp) {
        String ip = resolveClientIp(clientIp);
        if (ip != null && !ip.isBlank() && isIpLocked(ip)) {
            throw new BusinessException(CommonResultCode.FORBIDDEN, "客户端 IP 连续登录失败次数超限，已被临时锁定");
        }
    }

    // ==========================================
    // 6. 本地内存隔离清理与配置读取
    // ==========================================

    /**
     * 清理当前实例本地内存容器中的所有记录（专用于单元测试上下文零污染与调试排障）
     */
    public void clearLocalMemory() {
        localFailMap.clear();
        localIpFailMap.clear();
        localIpLockMap.clear();
    }

    /**
     * 获取当前本地内存容器记录数
     *
     * @return 内存记录数
     */
    public int getLocalMemorySize() {
        return localFailMap.size() + localIpFailMap.size() + localIpLockMap.size();
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

    private int getIpMaxRetryCount() {
        if (properties != null && properties.getLoginDefense() != null) {
            return properties.getLoginDefense().getIpMaxRetryCount();
        }
        return 30;
    }

    private Duration getIpLockTime() {
        if (properties != null && properties.getLoginDefense() != null && properties.getLoginDefense().getIpLockTime() != null) {
            return properties.getLoginDefense().getIpLockTime();
        }
        return Duration.ofMinutes(30);
    }

    private Duration getIpWindowTime() {
        if (properties != null && properties.getLoginDefense() != null && properties.getLoginDefense().getIpWindowTime() != null) {
            return properties.getLoginDefense().getIpWindowTime();
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

    private AccountTarget legacyTarget(String account) {
        if (account == null || account.isBlank()) {
            return null;
        }
        return new AccountTarget(account, account, null, false);
    }

    private AccountTarget scopedTarget(String tenantId, String loginType, String account) {
        String scopedKey = buildScopedAccountKey(tenantId, loginType, account);
        if (scopedKey == null) {
            return null;
        }
        return new AccountTarget(scopedKey, scopedKey, normalizeLoginType(loginType), true);
    }

    private static String normalizeLoginType(String loginType) {
        return (loginType == null || loginType.isBlank()) ? StpUtil.TYPE : loginType.trim();
    }

    private void disableAccount(AccountTarget target, Duration lockDuration) {
        if (target.scoped()) {
            LoginHelper.disableByLoginType(target.loginType(), target.disableSubject(), lockDuration);
        } else {
            LoginHelper.disable(target.disableSubject(), lockDuration);
        }
    }

    private boolean isAccountDisabled(AccountTarget target) {
        if (target.scoped()) {
            return LoginHelper.isDisableByLoginType(target.loginType(), target.disableSubject());
        }
        return LoginHelper.isDisable(target.disableSubject());
    }

    private void checkAccountDisabled(AccountTarget target) {
        if (target.scoped()) {
            LoginHelper.checkDisableByLoginType(target.loginType(), target.disableSubject());
        } else {
            LoginHelper.checkDisable(target.disableSubject());
        }
    }

    private long getAccountDisableTime(AccountTarget target) {
        if (target.scoped()) {
            return LoginHelper.getDisableTimeByLoginType(target.loginType(), target.disableSubject());
        }
        return LoginHelper.getDisableTime(target.disableSubject());
    }

    private record AccountTarget(String cacheKey, String disableSubject, String loginType, boolean scoped) {
    }
}

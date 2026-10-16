package io.github.atengk.boot.redis.lock.aspect;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.lock.annotation.DistributedLock;
import io.github.atengk.boot.redis.lock.enums.LockMode;
import io.github.atengk.boot.redis.properties.RedisProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 生产级声明式分布式锁 AOP 切面
 * 驱动 RedissonClient 实现四阶锁加锁、多键联锁、看门狗自动延期、安全持锁释放与慢锁审计
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Aspect
public class DistributedLockAspect {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockAspect.class);

    private final RedissonClient redissonClient;
    private final RedisProperties redisProperties;

    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public DistributedLockAspect(RedissonClient redissonClient, RedisProperties redisProperties) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "RedissonClient must not be null");
        this.redisProperties = Objects.requireNonNull(redisProperties, "RedisProperties must not be null");
    }

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        // 1. 解析目标锁键
        List<String> keyList = resolveLockKeys(joinPoint, distributedLock);
        RLock rLock = createRLock(keyList, distributedLock.mode());

        // 2. 计算超时与租约时间
        long waitTime = distributedLock.waitTime() >= 0
                ? distributedLock.waitTime()
                : redisProperties.getLock().getWaitTime();
        long leaseTime = distributedLock.leaseTime() >= 0
                ? distributedLock.leaseTime()
                : redisProperties.getLock().getLeaseTime();
        TimeUnit timeUnit = distributedLock.timeUnit() != null
                ? distributedLock.timeUnit()
                : redisProperties.getLock().getTimeUnit();

        // 3. 执行加锁
        boolean acquired;
        try {
            if (leaseTime > 0) {
                acquired = rLock.tryLock(waitTime, leaseTime, timeUnit);
            } else {
                // leaseTime <= 0 时激活 Redisson 看门狗 (Watchdog) 自动续期
                acquired = rLock.tryLock(waitTime, timeUnit);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[DistributedLock] 获取分布式锁被线程中断: keys={}", keyList);
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "加锁线程已被中断", e);
        }

        // 4. 加锁超时失败快速熔断
        if (!acquired) {
            String message = StringUtils.hasText(distributedLock.message())
                    ? distributedLock.message()
                    : "系统繁忙，获取分布式锁超时，请稍后再试";
            log.warn("[DistributedLock] 获取分布式锁超时，加锁失败: keys={}, mode={}, waitTime={}{}",
                    keyList, distributedLock.mode(), waitTime, timeUnit);
            throw new BusinessException(CommonResultCode.TOO_MANY_REQUESTS.getCode(), message);
        }

        log.debug("[DistributedLock] 加锁成功: keys={}, mode={}", keyList, distributedLock.mode());

        // 5. 执行业务逻辑并全方位守护
        long startTime = System.currentTimeMillis();
        try {
            return joinPoint.proceed();
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            long slowThreshold = redisProperties.getLock().getSlowThresholdMs();
            if (slowThreshold > 0 && duration >= slowThreshold) {
                log.warn("[DistributedLock] 慢锁告警: 方法 [{}] 持锁执行耗时 [{}ms], 超过预警阈值 [{}ms], 锁定键: {}",
                        joinPoint.getSignature().toShortString(), duration, slowThreshold, keyList);
            }
            safeUnlock(rLock, keyList);
        }
    }

    /**
     * 安全释放锁，防止 IllegalMonitorStateException 异常掩盖业务根因
     */
    private void safeUnlock(RLock rLock, List<String> keyList) {
        try {
            if (rLock != null && rLock.isHeldByCurrentThread()) {
                rLock.unlock();
                log.debug("[DistributedLock] 锁成功释放: keys={}", keyList);
            } else {
                log.warn("[DistributedLock] 锁当前未由本线程持有或已提前超时失效，跳过主动释放: keys={}", keyList);
            }
        } catch (Exception e) {
            log.warn("[DistributedLock] 释放分布式锁发生异常，安全忽略以保障业务异常不被掩盖: keys={}, error={}",
                    keyList, e.getMessage());
        }
    }

    /**
     * 解析生成锁键列表（支持单个与数组 SpEL）
     */
    private List<String> resolveLockKeys(ProceedingJoinPoint joinPoint, DistributedLock lock) {
        List<String> rawKeys = new ArrayList<>();

        if (StringUtils.hasText(lock.key())) {
            rawKeys.add(evaluateSpelKey(joinPoint, lock.key()));
        }

        if (lock.keys() != null && lock.keys().length > 0) {
            for (String k : lock.keys()) {
                if (StringUtils.hasText(k)) {
                    rawKeys.add(evaluateSpelKey(joinPoint, k));
                }
            }
        }

        // 若均未指定，兜底采用方法全限定签名
        if (rawKeys.isEmpty()) {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            rawKeys.add(method.getDeclaringClass().getSimpleName() + "#" + method.getName());
        }

        // 组装最终带前缀的完整键名
        String globalPrefix = redisProperties.getKeyPrefix();
        String lockPrefix = lock.prefix() != null ? lock.prefix() : "lock:";

        List<String> fullKeys = new ArrayList<>(rawKeys.size());
        for (String key : rawKeys) {
            StringBuilder sb = new StringBuilder();
            if (StringUtils.hasText(globalPrefix)) {
                sb.append(globalPrefix);
            }
            sb.append(lockPrefix).append(key);
            fullKeys.add(sb.toString());
        }
        return fullKeys;
    }

    /**
     * 解析 SpEL 表达式
     */
    private String evaluateSpelKey(ProceedingJoinPoint joinPoint, String spelExpression) {
        if (!spelExpression.contains("#")) {
            return spelExpression;
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();
        String[] paramNames = parameterNameDiscoverer.getParameterNames(method);

        EvaluationContext context = new StandardEvaluationContext();
        if (paramNames != null && paramNames.length > 0) {
            for (int i = 0; i < paramNames.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }

        try {
            Object value = expressionParser.parseExpression(spelExpression).getValue(context);
            if (value == null) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), "分布式锁 SpEL 表达式解析结果为空: " + spelExpression);
            }
            return value.toString();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), "解析分布式锁 SpEL 表达式失败: " + spelExpression, e);
        }
    }

    /**
     * 根据锁模式与键列表创建 Redisson RLock
     */
    private RLock createRLock(List<String> keyList, LockMode mode) {
        if (keyList.size() == 1) {
            return getSingleLock(keyList.get(0), mode);
        }

        // 针对多键联锁 MultiLock
        RLock[] locks = new RLock[keyList.size()];
        for (int i = 0; i < keyList.size(); i++) {
            locks[i] = getSingleLock(keyList.get(i), mode);
        }
        return redissonClient.getMultiLock(locks);
    }

    private RLock getSingleLock(String key, LockMode mode) {
        switch (mode) {
            case FAIR:
                return redissonClient.getFairLock(key);
            case READ:
                return redissonClient.getReadWriteLock(key).readLock();
            case WRITE:
                return redissonClient.getReadWriteLock(key).writeLock();
            case REENTRANT:
            default:
                return redissonClient.getLock(key);
        }
    }
}

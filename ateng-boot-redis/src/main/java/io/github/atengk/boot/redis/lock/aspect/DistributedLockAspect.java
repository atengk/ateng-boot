package io.github.atengk.boot.redis.lock.aspect;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.lock.annotation.DistributedLock;
import io.github.atengk.boot.redis.lock.enums.LockMode;
import io.github.atengk.boot.redis.lock.template.DistributedLockTemplate;
import io.github.atengk.boot.redis.properties.RedisProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
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

    private final DistributedLockTemplate distributedLockTemplate;

    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public DistributedLockAspect(DistributedLockTemplate distributedLockTemplate) {
        this.distributedLockTemplate = Objects.requireNonNull(distributedLockTemplate, "DistributedLockTemplate must not be null");
    }

    @Deprecated
    public DistributedLockAspect(DistributedLockTemplate distributedLockTemplate, RedisProperties redisProperties) {
        this(distributedLockTemplate);
    }

    /**
     * 兼容基于 RedissonClient 直接构造的构造器
     */
    public DistributedLockAspect(RedissonClient redissonClient, RedisProperties redisProperties) {
        this(new DistributedLockTemplate(redissonClient, redisProperties));
    }

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        List<String> rawKeys = resolveLockKeys(joinPoint, distributedLock);
        return distributedLockTemplate.execute(
                rawKeys,
                distributedLock.prefix(),
                distributedLock.mode(),
                distributedLock.waitTime(),
                distributedLock.leaseTime(),
                distributedLock.timeUnit(),
                distributedLock.message(),
                joinPoint::proceed,
                null
        );
    }

    /**
     * 解析生成业务锁键列表（支持单个与数组 SpEL，不含前缀）
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

        return rawKeys;
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
}

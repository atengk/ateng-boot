package io.github.atengk.boot.redis.idempotent.aspect;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.idempotent.RedisIdempotentTemplate;
import io.github.atengk.boot.redis.idempotent.annotation.RedisIdempotent;
import io.github.atengk.boot.redis.properties.RedisProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生产级声明式分布式防重幂等 AOP 切面
 * 拦截标注了 @RedisIdempotent 的业务方法，支持动态 SpEL 业务键解析与失败自动补偿
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Aspect
public class RedisIdempotentAspect {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotentAspect.class);

    private final RedisIdempotentTemplate idempotentTemplate;
    private final RedisProperties redisProperties;

    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();

    public RedisIdempotentAspect(RedisIdempotentTemplate idempotentTemplate, RedisProperties redisProperties) {
        Assert.notNull(idempotentTemplate, "idempotentTemplate must not be null");
        this.idempotentTemplate = idempotentTemplate;
        this.redisProperties = redisProperties;
    }

    public RedisIdempotentAspect(RedisIdempotentTemplate idempotentTemplate) {
        this(idempotentTemplate, null);
    }

    @Around("@annotation(redisIdempotent)")
    public Object around(ProceedingJoinPoint joinPoint, RedisIdempotent redisIdempotent) throws Throwable {
        String businessKey = resolveKey(joinPoint, redisIdempotent);
        Duration ttl = parseExpire(redisIdempotent.expire());

        return idempotentTemplate.execute(
                redisIdempotent.prefix(),
                businessKey,
                ttl,
                redisIdempotent.deleteOnFailure(),
                redisIdempotent.message(),
                () -> joinPoint.proceed()
        );
    }

    /**
     * 解析生成业务幂等键（支持动态 SpEL 表达式与方法签名兜底）
     */
    private String resolveKey(ProceedingJoinPoint joinPoint, RedisIdempotent redisIdempotent) {
        if (StringUtils.hasText(redisIdempotent.key())) {
            String key = redisIdempotent.key().trim();
            if (key.contains("#")) {
                return evaluateSpelKey(joinPoint, key);
            }
            return key;
        }

        // 未配置 key 时的默认兜底：采用 类名#方法名 签名
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }

    /**
     * 基于 SpEL 动态计算业务键
     */
    private String evaluateSpelKey(ProceedingJoinPoint joinPoint, String spelExpression) {
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
            Expression expression = expressionCache.computeIfAbsent(spelExpression, expressionParser::parseExpression);
            Object value = expression.getValue(context);
            if (value == null) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(),
                        "幂等键 SpEL 表达式计算结果为空: " + spelExpression);
            }
            return value.toString();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(),
                    "解析幂等键 SpEL 表达式失败: " + spelExpression, e);
        }
    }

    /**
     * 解析 expire 字符串为标准 Duration 时长
     *
     * @param expireText 时长表达式（如 "10m", "30s", "1h", "100"）
     * @return 标准 Duration，为空时返回 null
     */
    public static Duration parseExpire(String expireText) {
        if (!StringUtils.hasText(expireText)) {
            return null;
        }
        String trimmed = expireText.trim();
        if (trimmed.matches("\\d+")) {
            return Duration.ofSeconds(Long.parseLong(trimmed));
        }
        try {
            return DurationStyle.detectAndParse(trimmed);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 @RedisIdempotent expire 时长表达式: " + expireText, e);
        }
    }
}

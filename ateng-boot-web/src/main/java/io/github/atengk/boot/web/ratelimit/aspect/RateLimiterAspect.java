package io.github.atengk.boot.web.ratelimit.aspect;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.web.ratelimit.annotation.LimitType;
import io.github.atengk.boot.web.ratelimit.annotation.RateLimiter;
import io.github.atengk.boot.web.ratelimit.properties.RateLimiterProperties;
import io.github.atengk.boot.web.ratelimit.store.InMemoryRateLimiterStore;
import io.github.atengk.boot.web.ratelimit.store.RateLimiterStore;
import io.github.atengk.boot.web.util.IpUtil;
import io.github.atengk.boot.web.util.ServletUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 接口限流防刷 AOP 切面
 * 解析 @RateLimiter 注解，支持 DEFAULT、IP、USER 及 SpEL 业务键复合限流，触发阈值时回写 Retry-After 标头并抛出受控异常
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Aspect
public class RateLimiterAspect implements Ordered {

    /**
     * 切面优先级：置于 IP 访问控制之后，防重幂等与签名之前
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 200;

    private static final Logger log = LoggerFactory.getLogger(RateLimiterAspect.class);

    private final RateLimiterStore rateLimiterStore;
    private final RateLimiterProperties properties;
    private final AtomicReference<RateLimiterStore> fallbackStoreRef = new AtomicReference<>();
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();

    public RateLimiterAspect(RateLimiterStore rateLimiterStore) {
        this(rateLimiterStore, new RateLimiterProperties());
    }

    public RateLimiterAspect(RateLimiterStore rateLimiterStore, RateLimiterProperties properties) {
        this.rateLimiterStore = Objects.requireNonNull(rateLimiterStore, "RateLimiterStore must not be null");
        this.properties = properties != null ? properties : new RateLimiterProperties();
    }

    private RateLimiterStore getFallbackStore() {
        RateLimiterStore existing = fallbackStoreRef.get();
        if (existing != null) {
            return existing;
        }
        RateLimiterStore created = new InMemoryRateLimiterStore();
        if (fallbackStoreRef.compareAndSet(null, created)) {
            return created;
        }
        return fallbackStoreRef.get();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Around("@annotation(rateLimiter)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimiter rateLimiter) throws Throwable {
        String fullKey = resolveFullKey(joinPoint, rateLimiter);

        // 1. 尝试原子获取令牌许可（支持底层驱动故障时的 failOpen 降级）
        boolean acquired;
        try {
            acquired = rateLimiterStore.tryAcquire(
                    fullKey,
                    rateLimiter.count(),
                    rateLimiter.time(),
                    rateLimiter.timeUnit()
            );
        } catch (Exception ex) {
            if (properties.isFailOpen()) {
                log.warn("RateLimiter store execution failed, falling back to in-memory store due to failOpen=true, key: {}", fullKey, ex);
                acquired = getFallbackStore().tryAcquire(
                        fullKey,
                        rateLimiter.count(),
                        rateLimiter.time(),
                        rateLimiter.timeUnit()
                );
            } else {
                throw ex;
            }
        }

        if (!acquired) {
            // 2. 计算 Retry-After 窗口建议秒数并写入响应头
            long retryAfterSeconds = Math.max(1, rateLimiter.timeUnit().toSeconds(rateLimiter.time()));
            HttpServletResponse response = ServletUtil.getResponse();
            if (response != null) {
                response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            }

            // 3. 记录日志并抛出受控业务异常
            String message = StringUtils.hasText(rateLimiter.message())
                    ? rateLimiter.message()
                    : CommonResultCode.TOO_MANY_REQUESTS.getMsg();
            log.warn("RateLimiter blocked request on key [{}] (limit={}/{}{})",
                    fullKey, rateLimiter.count(), rateLimiter.time(), rateLimiter.timeUnit());
            throw new BusinessException(CommonResultCode.TOO_MANY_REQUESTS.getCode(), message);
        }

        // 4. 放行执行业务方法
        return joinPoint.proceed();
    }

    /**
     * 解析生成完整限流键
     */
    private String resolveFullKey(ProceedingJoinPoint joinPoint, RateLimiter rateLimiter) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(rateLimiter.prefix())) {
            sb.append(rateLimiter.prefix());
        }

        // 1. 维度键解析
        LimitType limitType = rateLimiter.limitType() != null ? rateLimiter.limitType() : LimitType.DEFAULT;
        sb.append(limitType.name().toLowerCase()).append(":");

        switch (limitType) {
            case IP -> {
                String ip = IpUtil.getClientIp();
                sb.append(StringUtils.hasText(ip) ? ip : "unknown_ip");
            }
            case USER -> {
                String userId = UserContextHolder.get();
                sb.append(StringUtils.hasText(userId) ? userId : "anonymous_user");
            }
            case TENANT -> {
                String tenantId = TenantContextHolder.getTenantId();
                sb.append(StringUtils.hasText(tenantId) ? tenantId : "default");
            }
            case DEFAULT -> {
                var request = ServletUtil.getRequest();
                if (request != null && StringUtils.hasText(request.getRequestURI())) {
                    sb.append(request.getRequestURI());
                } else {
                    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
                    sb.append(signature.getDeclaringTypeName()).append(".").append(signature.getName());
                }
            }
        }

        // 2. SpEL 业务键复合拼接
        if (StringUtils.hasText(rateLimiter.key())) {
            String spelValue = evaluateSpelKey(joinPoint, rateLimiter.key());
            sb.append(":").append(spelValue);
        }

        return sb.toString();
    }

    /**
     * 基于 SpEL 表达式计算业务键
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
                throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), "限流键表达式计算结果为空: " + spelExpression);
            }
            return value.toString();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), "解析限流键 SpEL 表达式失败: " + spelExpression, e);
        }
    }
}

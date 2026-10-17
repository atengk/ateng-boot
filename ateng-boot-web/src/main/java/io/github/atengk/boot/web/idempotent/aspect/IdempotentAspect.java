package io.github.atengk.boot.web.idempotent.aspect;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.web.idempotent.annotation.Idempotent;
import io.github.atengk.boot.web.idempotent.properties.IdempotentProperties;
import io.github.atengk.boot.web.idempotent.store.IdempotentKeyStore;
import io.github.atengk.boot.web.util.ServletUtil;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接口防重幂等 AOP 切面
 * 解析 @Idempotent 注解，支持精准 SpEL 业务键与自动请求特征指纹哈希，实施全生命周期锁控制
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Aspect
public class IdempotentAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotentAspect.class);

    private final IdempotentKeyStore keyStore;
    private final IdempotentProperties properties;
    private final ObjectMapper objectMapper;

    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();

    public IdempotentAspect(IdempotentKeyStore keyStore, IdempotentProperties properties) {
        this(keyStore, properties, null);
    }

    public IdempotentAspect(IdempotentKeyStore keyStore,
                            IdempotentProperties properties,
                            ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.keyStore = Objects.requireNonNull(keyStore, "IdempotentKeyStore must not be null");
        this.properties = Objects.requireNonNull(properties, "IdempotentProperties must not be null");
        this.objectMapper = objectMapperProvider != null ? objectMapperProvider.getIfAvailable() : null;
    }

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        String fullKey = resolveFullKey(joinPoint, idempotent);

        // 1. 尝试原子加锁抢占
        boolean acquired = keyStore.tryLock(fullKey, idempotent.timeout(), idempotent.timeUnit());
        if (!acquired) {
            String message = StringUtils.hasText(idempotent.message())
                    ? idempotent.message()
                    : CommonResultCode.REPEAT_SUBMIT.getMsg();
            log.warn("Idempotent check blocked duplicate request on key [{}]", fullKey);
            throw new BusinessException(CommonResultCode.REPEAT_SUBMIT.getCode(), message);
        }

        // 2. 执行业务方法，全生命周期管控
        try {
            return joinPoint.proceed();
        } catch (Throwable ex) {
            // 若执行发生异常且配置了异常自动释放，主动释放锁以允许用户立即重试
            if (idempotent.delKeyOnException()) {
                keyStore.unlock(fullKey);
                log.debug("Idempotent lock released due to exception on key [{}]", fullKey);
            }
            throw ex;
        }
    }

    /**
     * 解析生成完整幂等键
     */
    private String resolveFullKey(ProceedingJoinPoint joinPoint, Idempotent idempotent) {
        String businessKey;
        if (StringUtils.hasText(idempotent.key())) {
            businessKey = evaluateSpelKey(joinPoint, idempotent.key());
        } else {
            businessKey = generateAutoFingerprint(joinPoint);
        }

        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(properties.getKeyPrefix())) {
            sb.append(properties.getKeyPrefix());
        }
        if (StringUtils.hasText(idempotent.prefix())) {
            sb.append(idempotent.prefix());
        }
        sb.append(businessKey);
        return sb.toString();
    }

    /**
     * 基于 SpEL 表达式计算业务幂等键
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
                throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), "幂等键表达式计算结果为空: " + spelExpression);
            }
            return value.toString();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), "解析幂等键 SpEL 表达式失败: " + spelExpression, e);
        }
    }

    /**
     * 生成通用防抖自动指纹（URI + Method + UserID + 请求参数SHA-256）
     */
    private String generateAutoFingerprint(ProceedingJoinPoint joinPoint) {
        var request = ServletUtil.getRequest();
        String uri = request != null ? request.getRequestURI() : joinPoint.getSignature().toShortString();
        String method = request != null ? request.getMethod() : "POST";
        String userId = UserContextHolder.get() != null ? UserContextHolder.get() : "anonymous";

        StringBuilder paramContent = new StringBuilder();
        for (Object arg : joinPoint.getArgs()) {
            if (arg == null || arg instanceof ServletRequest || arg instanceof ServletResponse || arg instanceof MultipartFile) {
                continue;
            }
            paramContent.append(stringifyArg(arg)).append(";");
        }

        String raw = uri + "#" + method + "#" + userId + "#" + paramContent;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(raw.hashCode());
        }
    }

    /**
     * 将入参参数稳健序列化为字符串，优先利用 Jackson 避免复合对象内存哈希漂移
     */
    private String stringifyArg(Object arg) {
        if (arg == null) {
            return "null";
        }
        if (arg.getClass().isArray()) {
            if (arg instanceof Object[] objArray) {
                return Arrays.deepToString(objArray);
            }
            return String.valueOf(arg);
        }
        if (arg instanceof CharSequence || arg instanceof Number || arg instanceof Boolean) {
            return arg.toString();
        }
        if (objectMapper != null) {
            try {
                return objectMapper.writeValueAsString(arg);
            } catch (Exception ignored) {
            }
        }
        return arg.toString();
    }
}

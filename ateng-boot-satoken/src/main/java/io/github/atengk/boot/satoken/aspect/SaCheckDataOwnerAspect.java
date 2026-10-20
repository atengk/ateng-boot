package io.github.atengk.boot.satoken.aspect;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.satoken.annotation.CheckDataOwner;
import io.github.atengk.boot.satoken.spi.DataOwnerProvider;
import io.github.atengk.boot.satoken.spi.DefaultDataOwnerProvider;
import io.github.atengk.boot.satoken.util.LoginHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.ApplicationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 水平越权防御（IDOR）数据所有权切面
 * 拦截标注了 @CheckDataOwner 的方法，自动校验入参或间接业务资源的所有者是否为当前登录用户
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Slf4j
@Aspect
@RequiredArgsConstructor
public class SaCheckDataOwnerAspect {

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    private final ApplicationContext applicationContext;
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, DataOwnerProvider<?>> providerCache = new ConcurrentHashMap<>();

    @Before("@annotation(checkDataOwner)")
    public void beforeCheck(JoinPoint joinPoint, CheckDataOwner checkDataOwner) {
        // 1. 超级管理员特权快速放行
        if (checkDataOwner.allowAdmin() && LoginHelper.isSuperAdmin()) {
            log.debug("当前操作人具备超级管理员特权，数据所有权校验豁免通过");
            return;
        }

        // 2. 提取入参目标资源或所有者标识
        Object targetResourceOrOwner = resolveTargetResource(joinPoint, checkDataOwner.expr());

        // 3. 解析实际归属的所有者用户唯一标识
        String ownerId = resolveOwnerId(targetResourceOrOwner, checkDataOwner.provider());

        // 4. 比对当前登录用户唯一标识
        if (!LoginHelper.isSelf(ownerId)) {
            log.warn("检测到水平越权访问拦截: currentUserId={}, targetOwnerId={}, expr={}",
                    LoginHelper.getUserId(), ownerId, checkDataOwner.expr());
            throw new BusinessException(CommonResultCode.FORBIDDEN, checkDataOwner.message());
        }
    }

    /**
     * 解析目标资源或直接所有者标识
     */
    private Object resolveTargetResource(JoinPoint joinPoint, String expr) {
        if (expr == null || expr.isBlank()) {
            Object[] args = joinPoint.getArgs();
            return (args != null && args.length > 0) ? args[0] : null;
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();

        String[] paramNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
        EvaluationContext context = new StandardEvaluationContext();
        if (paramNames != null && args != null) {
            for (int i = 0; i < Math.min(paramNames.length, args.length); i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        context.setVariable("args", args);

        Expression expression = expressionCache.computeIfAbsent(expr, PARSER::parseExpression);
        return expression.getValue(context);
    }

    /**
     * 解析资源实际所有者的用户标识
     */
    private String resolveOwnerId(Object rawResourceOrOwner, Class<? extends DataOwnerProvider<?>> providerClass) {
        if (providerClass == null || DefaultDataOwnerProvider.class.equals(providerClass)) {
            return rawResourceOrOwner != null ? String.valueOf(rawResourceOrOwner) : null;
        }
        DataOwnerProvider<?> provider = providerCache.computeIfAbsent(providerClass, this::getOrCreateProvider);
        @SuppressWarnings("unchecked")
        DataOwnerProvider<Object> typedProvider = (DataOwnerProvider<Object>) provider;
        return typedProvider.getOwnerId(rawResourceOrOwner);
    }

    /**
     * 获取或创建 DataOwnerProvider 实例（优先从 Spring 容器获取）
     */
    private DataOwnerProvider<?> getOrCreateProvider(Class<?> providerClass) {
        if (applicationContext != null) {
            try {
                return (DataOwnerProvider<?>) applicationContext.getBean(providerClass);
            } catch (Exception ignored) {
                // 容器中未注册该 Bean，尝试通过无参构造器实例化
            }
        }
        try {
            return (DataOwnerProvider<?>) providerClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("无法实例化 DataOwnerProvider: " + providerClass.getName(), e);
        }
    }
}

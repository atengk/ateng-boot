package io.github.atengk.boot.job.snail.retry.generator;

import com.aizuda.snailjob.client.core.IdempotentIdGenerate;
import com.aizuda.snailjob.common.core.model.IdempotentIdContext;
import io.github.atengk.boot.job.snail.annotation.AtengSnailRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Spring SpEL 表达式的 SnailJob 重试业务幂等键生成器
 *
 * <p>支持从方法形参对象中提取强业务唯一标识（如订单号 {@code #order.orderNo}、流水号 {@code #dto.id}），
 * 确保同一业务实体在重试流中具备确定的幂等键，防御重试风暴与数据重复写入。
 * 当未配置 SpEL 或解析失败时，平滑降级至 {@link ArgsHashIdempotentIdGenerator}。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SpelIdempotentIdGenerator implements IdempotentIdGenerate {

    private static final Logger log = LoggerFactory.getLogger(SpelIdempotentIdGenerator.class);

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAM_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();
    private static final Map<String, Expression> EXPRESSION_CACHE = new ConcurrentHashMap<>();

    private final ArgsHashIdempotentIdGenerator fallbackGenerator = new ArgsHashIdempotentIdGenerator();

    @Override
    public String idGenerate(IdempotentIdContext context) throws Exception {
        if (context == null) {
            return fallbackGenerator.idGenerate(null);
        }

        String targetClassName = context.getTargetClassName();
        String methodName = context.getMethodName();
        Object[] args = context.getArgs();

        if (StringUtils.hasText(targetClassName) && StringUtils.hasText(methodName) && args != null) {
            try {
                Class<?> targetClass = ClassUtils.forName(targetClassName, ClassUtils.getDefaultClassLoader());
                Method targetMethod = findMatchingMethod(targetClass, methodName, args.length);

                if (targetMethod != null) {
                    AtengSnailRetry annotation = AnnotatedElementUtils.findMergedAnnotation(targetMethod, AtengSnailRetry.class);
                    if (annotation != null && StringUtils.hasText(annotation.idempotentSpel())) {
                        String spel = annotation.idempotentSpel().trim();
                        Expression expression = EXPRESSION_CACHE.computeIfAbsent(spel, PARSER::parseExpression);

                        StandardEvaluationContext evalContext = new StandardEvaluationContext();
                        String[] paramNames = PARAM_NAME_DISCOVERER.getParameterNames(targetMethod);

                        for (int i = 0; i < args.length; i++) {
                            Object arg = args[i];
                            evalContext.setVariable("p" + i, arg);
                            evalContext.setVariable("a" + i, arg);
                            if (paramNames != null && i < paramNames.length) {
                                evalContext.setVariable(paramNames[i], arg);
                            }
                        }

                        if (args.length == 1 && args[0] != null) {
                            evalContext.setRootObject(args[0]);
                        }

                        Object evaluatedValue = expression.getValue(evalContext);
                        if (evaluatedValue != null) {
                            String key = String.valueOf(evaluatedValue).trim();
                            if (StringUtils.hasText(key)) {
                                return StringUtils.hasText(context.getScene()) ? (context.getScene() + "_" + key) : key;
                            }
                        }
                    }
                }
            } catch (Throwable ex) {
                log.warn("SpEL 幂等键解析异常，自动降级至 ArgsHashIdempotentIdGenerator [Scene: {}, Class: {}, Method: {}]: {}",
                        context.getScene(), targetClassName, methodName, ex.getMessage());
            }
        }

        return fallbackGenerator.idGenerate(context);
    }

    /**
     * 在目标类中查找匹配名称和形参数量的目标方法
     */
    private Method findMatchingMethod(Class<?> targetClass, String methodName, int paramCount) {
        Method[] methods = ReflectionUtils.getAllDeclaredMethods(targetClass);
        for (Method method : methods) {
            if (method.getName().equals(methodName) && method.getParameterCount() == paramCount) {
                return method;
            }
        }
        return null;
    }
}

package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.concurrent.ConcurrentLruCache;
import io.github.atengk.boot.core.exception.BusinessException;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;

/**
 * 安全受限 Spring 表达式语言（SpEL）静态求值门面
 * 默认采用 SimpleEvaluationContext 只读数据绑定模式，严格阻断构造器实例化、任意类加载（T(...)）与运行时高危反射，根治 SpEL 注入与 RCE 风险
 * 内置容量 1024 的 ConcurrentLruCache 缓存 AST 解析树，防止高频重复编译引发 CPU 尖刺与内存溢出
 * 支持方法参数语义自动提取并注册形参名与 #p0/#a0 别名，且提供显式可信逃逸舱
 *
 * @author Ateng
 * @since 2026-10-27
 */
public final class SpelUtil {

    private static final int DEFAULT_CACHE_CAPACITY = 1024;
    private static final SpelExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();
    private static final ConcurrentLruCache<String, Expression> EXPRESSION_CACHE = new ConcurrentLruCache<>(DEFAULT_CACHE_CAPACITY);

    private SpelUtil() {
    }

    /**
     * 解析 SpEL 表达式字符串并缓存 AST 解析结果
     *
     * @param expression 待解析的 SpEL 表达式字符串（非空且非空白串）
     * @return 编译解析后的 Expression 实例
     * @throws BusinessException 当表达式为空或解析语法错误时抛出
     */
    public static Expression parseExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "SpEL 表达式不能为空或空白串");
        }
        return EXPRESSION_CACHE.computeIfAbsent(expression, expr -> {
            try {
                return PARSER.parseExpression(expr);
            } catch (ParseException e) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST, "SpEL 表达式解析失败: " + expr, e);
            }
        });
    }

    /**
     * 在默认安全只读上下文中求值表达式（无根对象与变量，如字面量或算术表达式）
     *
     * @param expression SpEL 表达式
     * @return 求值结果对象
     */
    public static Object evaluate(String expression) {
        return evaluate(expression, null, null, Object.class);
    }

    /**
     * 在默认安全只读上下文中求值表达式并强转为目标类型
     *
     * @param expression SpEL 表达式
     * @param returnType 期望的返回值类型
     * @param <T>        目标类型
     * @return 求值结果
     */
    public static <T> T evaluate(String expression, Class<T> returnType) {
        return evaluate(expression, null, null, returnType);
    }

    /**
     * 针对指定根对象在安全只读模式下求值表达式
     *
     * @param expression SpEL 表达式
     * @param rootObject 根对象
     * @return 求值结果对象
     */
    public static Object evaluate(String expression, Object rootObject) {
        return evaluate(expression, rootObject, null, Object.class);
    }

    /**
     * 针对指定根对象在安全只读模式下求值表达式并强转为目标类型
     *
     * @param expression SpEL 表达式
     * @param rootObject 根对象
     * @param returnType 期望的返回值类型
     * @param <T>        目标类型
     * @return 求值结果
     */
    public static <T> T evaluate(String expression, Object rootObject, Class<T> returnType) {
        return evaluate(expression, rootObject, null, returnType);
    }

    /**
     * 针对指定变量 Map 在安全只读模式下求值表达式
     *
     * @param expression SpEL 表达式
     * @param variables  变量映射集合（可为 null）
     * @return 求值结果对象
     */
    public static Object evaluate(String expression, Map<String, Object> variables) {
        return evaluate(expression, null, variables, Object.class);
    }

    /**
     * 针对指定变量 Map 在安全只读模式下求值表达式并强转为目标类型
     *
     * @param expression SpEL 表达式
     * @param variables  变量映射集合（可为 null）
     * @param returnType 期望的返回值类型
     * @param <T>        目标类型
     * @return 求值结果
     */
    public static <T> T evaluate(String expression, Map<String, Object> variables, Class<T> returnType) {
        return evaluate(expression, null, variables, returnType);
    }

    /**
     * 针对指定根对象与变量 Map 在安全只读模式下求值表达式
     *
     * @param expression SpEL 表达式
     * @param rootObject 根对象（可为 null）
     * @param variables  变量映射集合（可为 null）
     * @param returnType 期望的返回值类型
     * @param <T>        目标类型
     * @return 求值结果
     */
    public static <T> T evaluate(String expression, Object rootObject, Map<String, Object> variables, Class<T> returnType) {
        Expression parsedExpr = parseExpression(expression);
        EvaluationContext context = createSafeEvaluationContext(variables);
        try {
            return parsedExpr.getValue(context, rootObject, returnType);
        } catch (EvaluationException e) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "SpEL 表达式求值失败: " + expression, e);
        }
    }

    /**
     * 针对方法调用参数在安全只读模式下求值表达式
     *
     * @param method     当前调用的目标方法（非空）
     * @param args       方法入参数组（可为 null 或空）
     * @param expression SpEL 表达式
     * @return 求值结果对象
     */
    public static Object evaluateMethod(Method method, Object[] args, String expression) {
        return evaluateMethod(null, method, args, expression, Object.class);
    }

    /**
     * 针对方法调用参数在安全只读模式下求值表达式并强转为目标类型
     *
     * @param method     当前调用的目标方法（非空）
     * @param args       方法入参数组（可为 null 或空）
     * @param expression SpEL 表达式
     * @param returnType 期望的返回值类型
     * @param <T>        目标类型
     * @return 求值结果
     */
    public static <T> T evaluateMethod(Method method, Object[] args, String expression, Class<T> returnType) {
        return evaluateMethod(null, method, args, expression, returnType);
    }

    /**
     * 针对目标根对象与方法调用参数在安全只读模式下求值表达式
     *
     * @param rootObject 根对象（可为 null）
     * @param method     当前调用的目标方法（非空）
     * @param args       方法入参数组（可为 null 或空）
     * @param expression SpEL 表达式
     * @return 求值结果对象
     */
    public static Object evaluateMethod(Object rootObject, Method method, Object[] args, String expression) {
        return evaluateMethod(rootObject, method, args, expression, Object.class);
    }

    /**
     * 针对目标根对象与方法调用参数在安全只读模式下求值表达式并强转为目标类型
     *
     * @param rootObject 根对象（可为 null）
     * @param method     当前调用的目标方法（非空）
     * @param args       方法入参数组（可为 null 或空）
     * @param expression SpEL 表达式
     * @param returnType 期望的返回值类型
     * @param <T>        目标类型
     * @return 求值结果
     */
    public static <T> T evaluateMethod(Object rootObject, Method method, Object[] args, String expression, Class<T> returnType) {
        Objects.requireNonNull(method, "method 不能为空");
        Expression parsedExpr = parseExpression(expression);

        SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        bindMethodParameters(context, method, args);

        try {
            return parsedExpr.getValue(context, rootObject, returnType);
        } catch (EvaluationException e) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "SpEL 表达式求值失败: " + expression, e);
        }
    }

    /**
     * 显式可信逃逸舱：在 StandardEvaluationContext 全量反射模式下求值表达式
     * 允许调用类型引用（T(...)）、构造器与任意方法。
     * 存在任意代码执行（RCE）安全风险，仅允许在内部受信任配置或受控管理端脚本中调用，严禁直接传入未经验证的用户输入。
     *
     * @param expression SpEL 表达式
     * @param rootObject 根对象（可为 null）
     * @param returnType 期望的返回值类型
     * @param <T>        目标类型
     * @return 求值结果
     */
    public static <T> T evaluateTrusted(String expression, Object rootObject, Class<T> returnType) {
        return evaluateTrusted(expression, rootObject, null, returnType);
    }

    /**
     * 显式可信逃逸舱：在 StandardEvaluationContext 全量反射模式下求值表达式（携带变量集合）
     * 允许调用类型引用（T(...)）、构造器与任意方法。
     * 存在任意代码执行（RCE）安全风险，仅允许在内部受信任配置或受控管理端脚本中调用，严禁直接传入未经验证的用户输入。
     *
     * @param expression SpEL 表达式
     * @param rootObject 根对象（可为 null）
     * @param variables  变量映射集合（可为 null）
     * @param returnType 期望的返回值类型
     * @param <T>        目标类型
     * @return 求值结果
     */
    public static <T> T evaluateTrusted(String expression, Object rootObject, Map<String, Object> variables, Class<T> returnType) {
        Expression parsedExpr = parseExpression(expression);
        StandardEvaluationContext context = new StandardEvaluationContext(rootObject);
        if (variables != null && !variables.isEmpty()) {
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                context.setVariable(entry.getKey(), entry.getValue());
            }
        }
        try {
            return parsedExpr.getValue(context, rootObject, returnType);
        } catch (EvaluationException e) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "SpEL 表达式求值失败: " + expression, e);
        }
    }

    /**
     * 获取当前 AST 编译表达式缓存条目数
     *
     * @return 缓存大小
     */
    public static int getCacheSize() {
        return EXPRESSION_CACHE.size();
    }

    /**
     * 清空当前所有 AST 编译表达式缓存
     */
    public static void clearCache() {
        EXPRESSION_CACHE.clear();
    }

    /**
     * 构建安全受限的只读数据绑定求值上下文
     *
     * @param variables 变量映射集合
     * @return 初始化的 SimpleEvaluationContext 实例
     */
    private static EvaluationContext createSafeEvaluationContext(Map<String, Object> variables) {
        SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        if (variables != null && !variables.isEmpty()) {
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                context.setVariable(entry.getKey(), entry.getValue());
            }
        }
        return context;
    }

    /**
     * 解析方法形参并注册到上下文中
     * 同时支持形参名（#paramName）与位置索引别名（#p0/#a0, #p1/#a1）
     *
     * @param context 求值上下文
     * @param method  调用的目标方法
     * @param args    实际入参数组
     */
    private static void bindMethodParameters(EvaluationContext context, Method method, Object[] args) {
        if (args == null || args.length == 0) {
            return;
        }
        String[] parameterNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            context.setVariable("p" + i, arg);
            context.setVariable("a" + i, arg);
            if (parameterNames != null && i < parameterNames.length && parameterNames[i] != null) {
                context.setVariable(parameterNames[i], arg);
            }
        }
    }
}

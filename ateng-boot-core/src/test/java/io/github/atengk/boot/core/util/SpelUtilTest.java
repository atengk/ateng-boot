package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.expression.Expression;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SpelUtil 单元测试与安全对抗性验证
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("SpelUtil 表达式求值门面与防注入安全测试")
class SpelUtilTest {

    @BeforeEach
    @AfterEach
    void cleanCache() {
        SpelUtil.clearCache();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class SampleUser {
        private Long id;
        private String name;
        private Integer age;
    }

    static class SampleService {
        public String executeOrder(Long orderId, String clientType, SampleUser user) {
            return "SUCCESS";
        }

        public void emptyArgs() {
        }
    }

    @Test
    @DisplayName("基础静态计算与只读属性求值")
    void shouldEvaluateBasicExpressions() {
        // 纯数学计算
        Integer sum = SpelUtil.evaluate("1 + 2 * 3", Integer.class);
        assertEquals(7, sum);

        // 字符串拼接与无返回值类型重载
        Object concat = SpelUtil.evaluate("'Hello, ' + 'SpEL'");
        assertEquals("Hello, SpEL", concat);

        // 对象属性访问
        SampleUser user = new SampleUser(1001L, "Ateng", 18);
        String name = SpelUtil.evaluate("name", user, String.class);
        assertEquals("Ateng", name);
        Long id = SpelUtil.evaluate("id", user, Long.class);
        assertEquals(1001L, id);
    }

    @Test
    @DisplayName("变量上下文变量绑定与读取")
    void shouldEvaluateWithVariables() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("prefix", "ORD");
        vars.put("seq", 12345);

        String result = SpelUtil.evaluate("#prefix + '-' + #seq", vars, String.class);
        assertEquals("ORD-12345", result);

        // rootObject 与 variables 混合
        SampleUser user = new SampleUser(888L, "Admin", 20);
        String combined = SpelUtil.evaluate("name + ' (' + #role + ')'", user, Map.of("role", "SUPER"), String.class);
        assertEquals("Admin (SUPER)", combined);
    }

    @Test
    @DisplayName("方法参数提取与形参名 / 索引别名双向绑定")
    void shouldEvaluateMethodParameters() throws NoSuchMethodException {
        Method method = SampleService.class.getMethod("executeOrder", Long.class, String.class, SampleUser.class);
        SampleUser user = new SampleUser(999L, "Tester", 25);
        Object[] args = new Object[]{8888L, "APP", user};

        // 1. 基于方法形参名解析 (#orderId, #clientType, #user.name)
        Long orderId = SpelUtil.evaluateMethod(method, args, "#orderId", Long.class);
        assertEquals(8888L, orderId);

        String userName = SpelUtil.evaluateMethod(method, args, "#user.name", String.class);
        assertEquals("Tester", userName);

        // 2. 基于 #p0, #p1 索引别名解析
        Long p0 = SpelUtil.evaluateMethod(method, args, "#p0", Long.class);
        assertEquals(8888L, p0);
        String p1 = SpelUtil.evaluateMethod(method, args, "#p1", String.class);
        assertEquals("APP", p1);

        // 3. 基于 #a0, #a1 索引别名解析
        Long a0 = SpelUtil.evaluateMethod(method, args, "#a0", Long.class);
        assertEquals(8888L, a0);
        String a1 = SpelUtil.evaluateMethod(method, args, "#a1", String.class);
        assertEquals("APP", a1);

        // 4. 空参数或无参方法调用测试
        Method emptyMethod = SampleService.class.getMethod("emptyArgs");
        assertNull(SpelUtil.evaluateMethod(emptyMethod, new Object[0], "#p0"));
        assertNull(SpelUtil.evaluateMethod(emptyMethod, null, "#p0"));
    }

    @Test
    @DisplayName("安全沙箱严格阻断 RCE 注入攻击")
    void shouldBlockMaliciousSpelInjection() {
        // 尝试调用 Runtime 执行外部命令
        BusinessException ex1 = assertThrows(BusinessException.class, () ->
                SpelUtil.evaluate("T(java.lang.Runtime).getRuntime().exec('calc')"));
        assertEquals(CommonResultCode.BAD_REQUEST, ex1.getResultCode());
        assertNotNull(ex1.getCause());

        // 尝试构造 ProcessBuilder
        BusinessException ex2 = assertThrows(BusinessException.class, () ->
                SpelUtil.evaluate("new java.lang.ProcessBuilder('calc').start()"));
        assertEquals(CommonResultCode.BAD_REQUEST, ex2.getResultCode());
        assertNotNull(ex2.getCause());
    }

    @Test
    @DisplayName("显式可信逃逸舱 evaluateTrusted 允许受管反射求值")
    void shouldAllowTrustedEvaluationWhenExplicitlyInvoked() {
        // 在显式可信逃逸模式下，允许使用 T(...) 进行类型反射
        Class<?> clazz = SpelUtil.evaluateTrusted("T(java.lang.String)", null, Class.class);
        assertEquals(String.class, clazz);

        String upper = SpelUtil.evaluateTrusted("'hello'.toUpperCase()", null, String.class);
        assertEquals("HELLO", upper);
    }

    @Test
    @DisplayName("AST 编译结果有界 LRU 缓存命中与清空")
    void shouldCacheAndEvictParsedAstExpressions() {
        assertEquals(0, SpelUtil.getCacheSize());

        Expression expr1 = SpelUtil.parseExpression("1 + 1");
        assertNotNull(expr1);
        assertEquals(1, SpelUtil.getCacheSize());

        // 再次解析同一表达式应直接命中缓存
        Expression expr2 = SpelUtil.parseExpression("1 + 1");
        assertSame(expr1, expr2);
        assertEquals(1, SpelUtil.getCacheSize());

        // 评估求值同样复用缓存
        SpelUtil.evaluate("1 + 1");
        assertEquals(1, SpelUtil.getCacheSize());

        // 解析新表达式
        SpelUtil.evaluate("2 + 2");
        assertEquals(2, SpelUtil.getCacheSize());

        // 清空缓存
        SpelUtil.clearCache();
        assertEquals(0, SpelUtil.getCacheSize());
    }

    @Test
    @DisplayName("空值与空白串防卫校验")
    void shouldValidateBlankExpressions() {
        BusinessException ex1 = assertThrows(BusinessException.class, () -> SpelUtil.evaluate(null));
        assertEquals(CommonResultCode.BAD_REQUEST, ex1.getResultCode());

        BusinessException ex2 = assertThrows(BusinessException.class, () -> SpelUtil.evaluate("   "));
        assertEquals(CommonResultCode.BAD_REQUEST, ex2.getResultCode());
    }

    @Test
    @DisplayName("语法错误与求值异常因果链完整保留")
    void shouldPreserveCausalExceptionChain() {
        // 语法解析失败
        BusinessException parseEx = assertThrows(BusinessException.class, () -> SpelUtil.evaluate("1 + "));
        assertEquals(CommonResultCode.BAD_REQUEST, parseEx.getResultCode());
        assertThat(parseEx.getMessage()).contains("SpEL 表达式解析失败");
        assertNotNull(parseEx.getCause());

        // 运行时求值失败（如对 null 对象解引用）
        BusinessException evalEx = assertThrows(BusinessException.class, () ->
                SpelUtil.evaluate("user.name", new Object(), String.class));
        assertEquals(CommonResultCode.BAD_REQUEST, evalEx.getResultCode());
        assertThat(evalEx.getMessage()).contains("SpEL 表达式求值失败");
        assertNotNull(evalEx.getCause());
    }

    @Test
    @DisplayName("方法参数包含 null 元素与 null 方法对象防御")
    void shouldHandleNullArgumentsInMethodEvaluation() throws NoSuchMethodException {
        Method method = SampleService.class.getMethod("executeOrder", Long.class, String.class, SampleUser.class);
        Object[] argsWithNulls = new Object[]{null, "WEB", null};

        assertNull(SpelUtil.evaluateMethod(method, argsWithNulls, "#orderId"));
        assertNull(SpelUtil.evaluateMethod(method, argsWithNulls, "#p0"));
        assertEquals("WEB", SpelUtil.evaluateMethod(method, argsWithNulls, "#p1"));
        assertEquals("WEB", SpelUtil.evaluateMethod(method, argsWithNulls, "#a1"));
        assertNull(SpelUtil.evaluateMethod(method, argsWithNulls, "#user"));

        // 空 method 抛出 NullPointerException
        assertThrows(NullPointerException.class, () ->
                SpelUtil.evaluateMethod(null, argsWithNulls, "#p0"));
    }

    @Test
    @DisplayName("安全沙箱下严格禁止任意方法调用")
    void shouldBlockMethodInvocationInSafeMode() {
        SampleUser user = new SampleUser(123L, "User1", 30);
        // 尝试在安全只读模式下调用任意方法
        BusinessException ex = assertThrows(BusinessException.class, () ->
                SpelUtil.evaluate("toString()", user, String.class));
        assertEquals(CommonResultCode.BAD_REQUEST, ex.getResultCode());
        assertNotNull(ex.getCause());
    }

    @Test
    @DisplayName("evaluateTrusted 支持传入变量集合求值")
    void shouldEvaluateTrustedWithVariables() {
        Map<String, Object> vars = Map.of("word", "trusted");
        String result = SpelUtil.evaluateTrusted("#word.toUpperCase()", null, vars, String.class);
        assertEquals("TRUSTED", result);
    }
}

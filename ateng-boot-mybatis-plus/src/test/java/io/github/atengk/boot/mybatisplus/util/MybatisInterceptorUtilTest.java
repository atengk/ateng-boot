package io.github.atengk.boot.mybatisplus.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MybatisInterceptorUtil} 单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("MybatisInterceptorUtil 底层公共工具测试")
class MybatisInterceptorUtilTest {

    @Test
    @DisplayName("realTarget: null 或普通对象应直接安全返回")
    void testRealTargetWithNullOrPlainObject() {
        assertThat(MybatisInterceptorUtil.realTarget(null)).isNull();
        String plain = "test-target";
        assertThat(MybatisInterceptorUtil.realTarget(plain)).isSameAs(plain);
    }

    @Test
    @DisplayName("isFrameworkClass: 应正确识别并过滤各类框架与持久层类名")
    void testIsFrameworkClass() {
        // null 或空字符串
        assertThat(MybatisInterceptorUtil.isFrameworkClass(null)).isTrue();
        assertThat(MybatisInterceptorUtil.isFrameworkClass("")).isTrue();

        // JDK 与系统类
        assertThat(MybatisInterceptorUtil.isFrameworkClass("java.lang.Thread")).isTrue();
        assertThat(MybatisInterceptorUtil.isFrameworkClass("jdk.internal.reflect.DirectMethodHandleAccessor")).isTrue();

        // MyBatis 与 MyBatis-Spring
        assertThat(MybatisInterceptorUtil.isFrameworkClass("org.apache.ibatis.executor.statement.RoutingStatementHandler")).isTrue();
        assertThat(MybatisInterceptorUtil.isFrameworkClass("org.apache.ibatis.binding.MapperProxy")).isTrue();
        assertThat(MybatisInterceptorUtil.isFrameworkClass("org.mybatis.spring.SqlSessionTemplate")).isTrue();

        // MyBatis-Plus 官方
        assertThat(MybatisInterceptorUtil.isFrameworkClass("com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor")).isTrue();

        // 本框架自身拦截器与持久层模块
        assertThat(MybatisInterceptorUtil.isFrameworkClass("io.github.atengk.boot.mybatisplus.audit.DataChangeAuditInterceptor")).isTrue();
        assertThat(MybatisInterceptorUtil.isFrameworkClass("io.github.atengk.boot.mybatisplus.sqllog.SqlLogInterceptor")).isTrue();
        assertThat(MybatisInterceptorUtil.isFrameworkClass("io.github.atengk.boot.mybatisplus.slowsql.SlowSqlInterceptor")).isTrue();

        // Spring 与 AOP 容器
        assertThat(MybatisInterceptorUtil.isFrameworkClass("org.springframework.aop.framework.CglibAopProxy")).isTrue();
        assertThat(MybatisInterceptorUtil.isFrameworkClass("com.example.service.UserService$$SpringCGLIB$$0")).isTrue();

        // 真实业务类不应被过滤
        assertThat(MybatisInterceptorUtil.isFrameworkClass("com.example.service.UserServiceImpl")).isFalse();
        assertThat(MybatisInterceptorUtil.isFrameworkClass("io.github.atengk.boot.sample.mybatisplus.service.UserService")).isFalse();
        assertThat(MybatisInterceptorUtil.isFrameworkClass("com.company.controller.OrderController")).isFalse();

        // 包含 Test 的测试类不应被过滤，以便单测断言捕获
        assertThat(MybatisInterceptorUtil.isFrameworkClass("io.github.atengk.boot.mybatisplus.sqllog.SqlLogInterceptorTest")).isFalse();
    }

    @Test
    @DisplayName("resolveCallLocation: 应能定位到当前测试用例所在方法")
    void testResolveCallLocation() {
        String location = MybatisInterceptorUtil.resolveCallLocation();
        assertThat(location).isNotNull();
        assertThat(location).contains("MybatisInterceptorUtilTest.testResolveCallLocation");
        assertThat(location).contains("MybatisInterceptorUtilTest.java:");
    }
}

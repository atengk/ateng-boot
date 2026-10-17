package io.github.atengk.boot.mybatisplus.tenant;

import io.github.atengk.boot.mybatisplus.annotation.IgnoreTenant;
import io.github.atengk.boot.core.context.TenantContextHolder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengTenantLineInnerInterceptor 单元测试
 * 验证编程式忽略闭包、Mapper 类级 @IgnoreTenant 与方法级 @IgnoreTenant 的精准短路放行
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("AtengTenantLineInnerInterceptor 增强拦截器单元测试")
class AtengTenantLineInnerInterceptorTest {

    interface NormalMapper {
        void queryAll();
    }

    @IgnoreTenant
    interface ClassLevelIgnoreMapper {
        void queryA();
        void queryB();
    }

    interface MethodLevelIgnoreMapper {
        @IgnoreTenant
        void queryWithIgnore();

        void queryWithoutIgnore();
    }

    private AtengTenantLineInnerInterceptor interceptor;
    private Configuration configuration;

    @BeforeEach
    void setUp() {
        interceptor = new AtengTenantLineInnerInterceptor();
        configuration = new Configuration();
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private MappedStatement createStatement(String id) {
        return new MappedStatement.Builder(configuration, id, (parameterObject) -> null, null).build();
    }

    @Test
    @DisplayName("1. 常规方法：未标注任何忽略注解且不在忽略闭包中，shouldIgnore 返回 false")
    void shouldNotIgnoreNormalMethod() {
        MappedStatement ms = createStatement(NormalMapper.class.getName() + ".queryAll");
        assertThat(interceptor.shouldIgnore(ms)).isFalse();
    }

    @Test
    @DisplayName("2. 编程式闭包忽略：runWithoutTenant 内部 shouldIgnore 统一返回 true")
    void shouldIgnoreInRunWithoutTenantClosure() {
        MappedStatement ms = createStatement(NormalMapper.class.getName() + ".queryAll");
        assertThat(interceptor.shouldIgnore(ms)).isFalse();

        TenantContextHolder.runWithoutTenant(() -> {
            assertThat(interceptor.shouldIgnore(ms)).isTrue();
        });

        // 退出闭包后恢复为 false
        assertThat(interceptor.shouldIgnore(ms)).isFalse();
    }

    @Test
    @DisplayName("3. 类级注解忽略：Mapper 接口标注 @IgnoreTenant 时，其下所有方法均返回 true")
    void shouldIgnoreWhenClassHasAnnotation() {
        MappedStatement msA = createStatement(ClassLevelIgnoreMapper.class.getName() + ".queryA");
        MappedStatement msB = createStatement(ClassLevelIgnoreMapper.class.getName() + ".queryB");

        assertThat(interceptor.shouldIgnore(msA)).isTrue();
        assertThat(interceptor.shouldIgnore(msB)).isTrue();
    }

    @Test
    @DisplayName("4. 方法级注解忽略：仅标注了 @IgnoreTenant 的方法返回 true，未标方法返回 false")
    void shouldIgnoreWhenMethodHasAnnotation() {
        MappedStatement msIgnore = createStatement(MethodLevelIgnoreMapper.class.getName() + ".queryWithIgnore");
        MappedStatement msNormal = createStatement(MethodLevelIgnoreMapper.class.getName() + ".queryWithoutIgnore");

        assertThat(interceptor.shouldIgnore(msIgnore)).isTrue();
        assertThat(interceptor.shouldIgnore(msNormal)).isFalse();
    }

    @Test
    @DisplayName("5. 分页 Count 语句：MyBatis-Plus 自动追加的 _mpCount 后缀能正确剥离并识别方法级 @IgnoreTenant")
    void shouldIgnoreWhenMethodHasMpCountSuffix() {
        MappedStatement msCount = createStatement(MethodLevelIgnoreMapper.class.getName() + ".queryWithIgnore_mpCount");
        assertThat(interceptor.shouldIgnore(msCount)).isTrue();

        MappedStatement msNormalCount = createStatement(MethodLevelIgnoreMapper.class.getName() + ".queryWithoutIgnore_mpCount");
        assertThat(interceptor.shouldIgnore(msNormalCount)).isFalse();
    }
}

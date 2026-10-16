package io.github.atengk.boot.mybatisplus.autoconfigure;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import io.github.atengk.boot.mybatisplus.handler.DefaultMetaObjectHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MyBatis-Plus 模块自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-15
 */
@DisplayName("MyBatis-Plus 自动装配测试")
class AtengMybatisPlusAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengMybatisPlusAutoConfiguration.class));

    @Test
    @DisplayName("默认配置下成功装配审计填充器、MybatisPlusInterceptor 插件链与雪花发号器")
    void shouldAutoConfigureDefaultComponents() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(DefaultMetaObjectHandler.class);
            assertThat(context).hasSingleBean(MybatisPlusInterceptor.class);
            assertThat(context).hasSingleBean(IdentifierGenerator.class);

            MybatisPlusInterceptor interceptor = context.getBean(MybatisPlusInterceptor.class);
            List<InnerInterceptor> interceptors = interceptor.getInterceptors();
            assertThat(interceptors).hasSize(3);
            assertThat(interceptors.get(0)).isInstanceOf(PaginationInnerInterceptor.class);
            assertThat(interceptors.get(1)).isInstanceOf(BlockAttackInnerInterceptor.class);
            assertThat(interceptors.get(2)).isInstanceOf(OptimisticLockerInnerInterceptor.class);

            PaginationInnerInterceptor pagination = (PaginationInnerInterceptor) interceptors.get(0);
            assertThat(pagination.getMaxLimit()).isEqualTo(500L);
            assertThat(pagination.isOverflow()).isFalse();
        });
    }

    @Test
    @DisplayName("当配置 ateng.boot.mybatis-plus.audit.enabled=false 时禁用审计填充器")
    void shouldDisableAuditHandlerWhenConfigured() {
        contextRunner.withPropertyValues("ateng.boot.mybatis-plus.audit.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MetaObjectHandler.class);
                    assertThat(context).hasSingleBean(MybatisPlusInterceptor.class);
                });
    }

    @Test
    @DisplayName("配置指定 worker-id 与 datacenter-id 时正确初始化发号器")
    void shouldConfigureSnowflakeGeneratorWithIds() {
        contextRunner.withPropertyValues(
                "ateng.boot.mybatis-plus.snowflake.worker-id=3",
                "ateng.boot.mybatis-plus.snowflake.datacenter-id=7"
        ).run(context -> {
            assertThat(context).hasSingleBean(IdentifierGenerator.class);
            IdentifierGenerator generator = context.getBean(IdentifierGenerator.class);
            Number id = generator.nextId(new Object());
            assertThat(id).isNotNull();
        });
    }

    @Test
    @DisplayName("支持独立禁用各个拦截器插件")
    void shouldSupportDisablingInterceptors() {
        contextRunner.withPropertyValues(
                "ateng.boot.mybatis-plus.pagination.enabled=false",
                "ateng.boot.mybatis-plus.block-attack.enabled=false",
                "ateng.boot.mybatis-plus.optimistic-locker.enabled=false"
        ).run(context -> {
            assertThat(context).hasSingleBean(MybatisPlusInterceptor.class);
            MybatisPlusInterceptor interceptor = context.getBean(MybatisPlusInterceptor.class);
            assertThat(interceptor.getInterceptors()).isEmpty();
        });
    }

    @Test
    @DisplayName("自定义分页属性 maxLimit 与 overflow 正确注入插件")
    void shouldApplyCustomPaginationProperties() {
        contextRunner.withPropertyValues(
                "ateng.boot.mybatis-plus.pagination.max-limit=1000",
                "ateng.boot.mybatis-plus.pagination.overflow=true",
                "ateng.boot.mybatis-plus.pagination.db-type=mysql"
        ).run(context -> {
            MybatisPlusInterceptor interceptor = context.getBean(MybatisPlusInterceptor.class);
            PaginationInnerInterceptor pagination = (PaginationInnerInterceptor) interceptor.getInterceptors().get(0);
            assertThat(pagination.getMaxLimit()).isEqualTo(1000L);
            assertThat(pagination.isOverflow()).isTrue();
        });
    }

    @Test
    @DisplayName("当容器中存在 CurrentUserProvider 时能够正确装配给审计处理器")
    void shouldInjectCurrentUserProviderIntoAuditHandler() {
        contextRunner.withBean(io.github.atengk.boot.core.spi.CurrentUserProvider.class, () -> () -> "spi_user")
                .run(context -> {
                    assertThat(context).hasSingleBean(DefaultMetaObjectHandler.class);
                });
    }

    @Test
    @DisplayName("支持自动装配注入自定义 secretKey")
    void shouldConfigureSecretKey() {
        String testKey = "TestCustomSecretKey123456789012";
        contextRunner.withPropertyValues("ateng.boot.mybatis-plus.crypto.secret-key=" + testKey)
                .run(context -> {
                    assertThat(io.github.atengk.boot.mybatisplus.handler.EncryptTypeHandler.getGlobalSecretKey()).isEqualTo(testKey);
                });
    }
}

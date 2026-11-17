package io.github.atengk.boot.flyway.barrier;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.autoconfigure.DdlApplicationRunner;
import com.baomidou.mybatisplus.autoconfigure.DdlAutoConfiguration;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.ddl.IDdl;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.flyway.autoconfigure.AtengFlywayAutoConfiguration;
import io.github.atengk.boot.flyway.autoconfigure.AtengFlywayIsolatedInitializer;
import io.github.atengk.boot.flyway.autoconfigure.AtengFlywayMigrationInitializer;
import io.github.atengk.boot.mybatisplus.autoconfigure.AtengMybatisPlusAutoConfiguration;
import io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlRegistry;
import io.github.atengk.boot.mybatisplus.util.BatchUtil;
import io.github.atengk.boot.mybatisplus.util.PageUtil;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway 与 MyBatis-Plus 持久层初始化顺序屏障集成测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
class FlywayMybatisPlusBarrierTest {

    @AfterEach
    void cleanupMybatisStaticState() {
        BatchUtil.setCurrentUserProvider(null);
        BatchUtil.setAuditEnabled(true);
        BatchUtil.setDefaultOperator(null);
        BatchUtil.setDefaultBatchSize(0);
        PageUtil.setDefaultMaxLimit(500L);
    }

    private ApplicationContextRunner newMybatisRunner(String dbName, List<String> initOrder) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        DataSourceTransactionManagerAutoConfiguration.class,
                        AtengFlywayAutoConfiguration.class,
                        FlywayAutoConfiguration.class,
                        MybatisPlusAutoConfiguration.class,
                        DdlAutoConfiguration.class,
                        AtengMybatisPlusAutoConfiguration.class
                ))
                .withUserConfiguration(MapperTestConfiguration.class)
                .withBean("orderTracker", BeanPostProcessor.class, () -> new InitOrderRecorder(initOrder))
                .withBean("noopDdl", IDdl.class, () -> new IDdl() {
                    @Override
                    public void runScript(Consumer<DataSource> consumer) {
                    }

                    @Override
                    public List<String> getSqlFiles() {
                        return List.of();
                    }
                })
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1;MODE=MySQL",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.flyway.locations=classpath:db/slice1",
                        "ateng.boot.mybatis-plus.dynamic-sql.enabled=true",
                        "ateng.boot.mybatis-plus.tenant.enabled=false"
                );
    }

    @Test
    @DisplayName("普通模式下 Flyway 迁移应严格早于 SqlSessionFactory、SqlSessionTemplate、DynamicSqlRegistry 与 DdlApplicationRunner 且 Mapper 可直接读写")
    void shouldMigrateStrictlyBeforeMybatisPlusInfrastructureInNormalMode() {
        String dbName = "flyway_mp_norm_" + UUID.randomUUID().toString().replace("-", "");
        List<String> initOrder = new CopyOnWriteArrayList<>();

        newMybatisRunner(dbName, initOrder).run(context -> {
            assertThat(context).hasNotFailed();

            // 1. 验证 BeanDefinition 显式注入了前置依赖关系
            BeanDefinition factoryBd = context.getBeanFactory().getBeanDefinition("sqlSessionFactory");
            BeanDefinition templateBd = context.getBeanFactory().getBeanDefinition("sqlSessionTemplate");
            BeanDefinition registryBd = context.getBeanFactory().getBeanDefinition("dynamicSqlRegistry");
            BeanDefinition ddlRunnerBd = context.getBeanFactory().getBeanDefinition("ddlApplicationRunner");
            assertThat(factoryBd.getDependsOn()).contains("atengFlywayMigrationInitializer");
            assertThat(templateBd.getDependsOn()).contains("atengFlywayMigrationInitializer");
            assertThat(registryBd.getDependsOn()).contains("atengFlywayMigrationInitializer");
            assertThat(ddlRunnerBd.getDependsOn()).contains("atengFlywayMigrationInitializer");

            // 2. 验证真实初始化时间戳顺序
            int flywayIdx = initOrder.indexOf("flyway-initializer");
            assertThat(flywayIdx).isNotNegative();
            assertThat(initOrder.indexOf("sqlSessionFactory")).isGreaterThan(flywayIdx);
            assertThat(initOrder.indexOf("sqlSessionTemplate")).isGreaterThan(flywayIdx);
            assertThat(initOrder.indexOf("dynamicSqlRegistry")).isGreaterThan(flywayIdx);
            assertThat(initOrder.indexOf("ddlApplicationRunner")).isGreaterThan(flywayIdx);

            // 3. 验证就绪后 Mapper 可直接读写 Flyway 新建表
            SampleUserMapper mapper = context.getBean(SampleUserMapper.class);
            List<SampleUserEntity> rows = mapper.selectList(null);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getUsername()).isEqualTo("ateng");

            SampleUserEntity added = new SampleUserEntity();
            added.setId(2L);
            added.setUsername("mp-barrier-user");
            assertThat(mapper.insert(added)).isEqualTo(1);
            assertThat(mapper.selectCount(null)).isEqualTo(2L);
        });
    }

    @Test
    @DisplayName("隔离 DDL 凭据模式下 AtengFlywayIsolatedInitializer 同样应严格阻塞至持久层基础设施之前")
    void shouldEnforceInitializationBarrierInIsolatedCredentialMode() {
        byte[] aesKey = CryptoUtil.generateKey(128);
        byte[] aesIv = CryptoUtil.generateIv();
        String encPwd = "enc:aes:" + CryptoUtil.encodeBase64(aesKey) + ":" + CryptoUtil.encodeBase64(aesIv)
                + ":" + CryptoUtil.encryptCbc("", aesKey, aesIv);

        String dbName = "flyway_mp_iso_" + UUID.randomUUID().toString().replace("-", "");
        List<String> initOrder = new CopyOnWriteArrayList<>();

        newMybatisRunner(dbName, initOrder)
                .withPropertyValues(
                        "ateng.boot.flyway.credential.username=sa",
                        "ateng.boot.flyway.credential.password=" + encPwd
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AtengFlywayIsolatedInitializer.class);

                    assertThat(context.getBeanFactory().getBeanDefinition("sqlSessionFactory").getDependsOn())
                            .contains("atengFlywayIsolatedInitializer");
                    assertThat(context.getBeanFactory().getBeanDefinition("sqlSessionTemplate").getDependsOn())
                            .contains("atengFlywayIsolatedInitializer");
                    assertThat(context.getBeanFactory().getBeanDefinition("dynamicSqlRegistry").getDependsOn())
                            .contains("atengFlywayIsolatedInitializer");
                    assertThat(context.getBeanFactory().getBeanDefinition("ddlApplicationRunner").getDependsOn())
                            .contains("atengFlywayIsolatedInitializer");

                    int flywayIdx = initOrder.indexOf("flyway-isolated-initializer");
                    assertThat(flywayIdx).isNotNegative();
                    assertThat(initOrder.indexOf("sqlSessionFactory")).isGreaterThan(flywayIdx);
                    assertThat(initOrder.indexOf("sqlSessionTemplate")).isGreaterThan(flywayIdx);
                    assertThat(initOrder.indexOf("dynamicSqlRegistry")).isGreaterThan(flywayIdx);
                    assertThat(initOrder.indexOf("ddlApplicationRunner")).isGreaterThan(flywayIdx);

                    SampleUserMapper mapper = context.getBean(SampleUserMapper.class);
                    assertThat(mapper.selectCount(null)).isEqualTo(1L);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @MapperScan(basePackageClasses = SampleUserMapper.class)
    static class MapperTestConfiguration {
    }

    @TableName("sample_user")
    static class SampleUserEntity {

        @TableId
        private Long id;
        private String username;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }
    }

    interface SampleUserMapper extends BaseMapper<SampleUserEntity> {
    }

    private static final class InitOrderRecorder implements BeanPostProcessor {

        private final List<String> initOrder;

        private InitOrderRecorder(List<String> initOrder) {
            this.initOrder = initOrder;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
            if (bean instanceof AtengFlywayMigrationInitializer) {
                initOrder.add("flyway-initializer");
            } else if (bean instanceof AtengFlywayIsolatedInitializer) {
                initOrder.add("flyway-isolated-initializer");
            } else if (bean instanceof SqlSessionFactory) {
                initOrder.add("sqlSessionFactory");
            } else if (bean instanceof SqlSessionTemplate) {
                initOrder.add("sqlSessionTemplate");
            } else if (bean instanceof DynamicSqlRegistry) {
                initOrder.add("dynamicSqlRegistry");
            } else if (bean instanceof DdlApplicationRunner) {
                initOrder.add("ddlApplicationRunner");
            }
            return bean;
        }
    }
}

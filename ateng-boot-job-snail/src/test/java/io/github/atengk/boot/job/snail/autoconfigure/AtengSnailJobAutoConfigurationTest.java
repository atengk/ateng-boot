package io.github.atengk.boot.job.snail.autoconfigure;

import com.aizuda.snailjob.client.common.config.SnailJobProperties;
import com.aizuda.snailjob.client.job.core.Scanner;
import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.cache.JobExecutorInfoCache;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.client.job.core.dto.JobExecutorInfo;
import com.aizuda.snailjob.client.job.core.register.JobExecutorRegistrar;
import com.aizuda.snailjob.client.job.core.register.scan.JobExecutorScanner;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.core.autoconfigure.AtengAsyncAutoConfiguration;
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper;
import io.github.atengk.boot.job.snail.scanner.AtengSnailJobScanner;
import io.github.atengk.boot.job.snail.actuator.SnailJobEndpoint;
import io.github.atengk.boot.job.snail.actuator.SnailJobHealthIndicator;
import io.github.atengk.boot.job.snail.alert.SnailJobAlertAggregator;
import io.github.atengk.boot.job.snail.aspect.SnailJobContextAspect;
import io.github.atengk.boot.job.snail.autoconfigure.AtengSnailJobAutoConfiguration.SnailJobPropertiesBridge;
import io.github.atengk.boot.job.snail.drain.GracefulDrainStateMachine;
import io.github.atengk.boot.job.snail.log.SnailJobLogbackAppenderRegistrar;
import io.github.atengk.boot.job.snail.metrics.SnailJobMetricsBinder;
import io.github.atengk.boot.job.snail.retry.aspect.SnailRetryContextAspect;
import io.github.atengk.boot.job.snail.retry.callback.SnailRetryDeadLetterCallback;
import io.github.atengk.boot.job.snail.retry.context.SnailRetryContextStore;
import io.github.atengk.boot.job.snail.retry.template.SnailRetryTemplate;
import io.github.atengk.boot.job.snail.template.BatchExecutionResult;
import io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate;
import io.github.atengk.boot.job.snail.template.SnailShardingBatchTemplate;
import io.github.atengk.boot.job.snail.template.TenantJobTemplate;
import io.github.atengk.boot.job.snail.watchdog.SnailLocalWatchdog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SnailJob 自动装配与属性绑定单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class AtengSnailJobAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengSnailJobAutoConfiguration.class));

    @Test
    @DisplayName("测试默认配置下自动装配激活且属性具备生产合理默认值")
    void testDefaultAutoConfigurationEnabled() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengSnailJobAutoConfiguration.class);
            assertThat(context).hasSingleBean(io.github.atengk.boot.job.snail.properties.SnailJobProperties.class);
            assertThat(context).hasSingleBean(SnailJobPropertiesBridge.class);
            assertThat(context).hasSingleBean(io.github.atengk.boot.job.snail.template.SnailShardingBatchTemplate.class);
            assertThat(context).hasSingleBean(io.github.atengk.boot.job.snail.template.TenantJobTemplate.class);
            assertThat(context).hasSingleBean(io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate.class);
            assertThat(context).hasSingleBean(io.github.atengk.boot.job.snail.log.SnailJobLogbackAppenderRegistrar.class);
            assertThat(context).hasSingleBean(io.github.atengk.boot.job.snail.drain.GracefulDrainStateMachine.class);
            assertThat(context).hasSingleBean(io.github.atengk.boot.job.snail.alert.SnailJobAlertAggregator.class);
            assertThat(context).hasSingleBean(io.github.atengk.boot.job.snail.retry.callback.SnailRetryDeadLetterCallback.class);
            assertThat(context).hasSingleBean(io.github.atengk.boot.job.snail.metrics.SnailJobMetricsBinder.class);
            assertThat(context).hasSingleBean(io.github.atengk.boot.job.snail.actuator.SnailJobEndpoint.class);
            assertThat(context).hasSingleBean(io.github.atengk.boot.job.snail.actuator.SnailJobHealthIndicator.class);

            io.github.atengk.boot.job.snail.properties.SnailJobProperties props =
                    context.getBean(io.github.atengk.boot.job.snail.properties.SnailJobProperties.class);
            assertThat(props.isEnabled()).isTrue();
            assertThat(props.getServer()).isEqualTo("127.0.0.1:17888");
            assertThat(props.getNamespace()).isEqualTo("default");
            assertThat(props.getPort()).isEqualTo(17889);
            assertThat(props.getTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(props.getDrainTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(props.getDefaultJobTimeout()).isEqualTo(Duration.ofHours(1));
            assertThat(props.getMaxPayloadSize()).isEqualTo(DataSize.ofMegabytes(1));
            assertThat(props.isLogMirrorEnabled()).isTrue();
            assertThat(props.isManagedPoolEnabled()).isTrue();

            assertThat(props.getJob().isEnabled()).isTrue();
            assertThat(props.getJob().isContextAspectEnabled()).isTrue();

            assertThat(props.getRetry().isEnabled()).isTrue();
            assertThat(props.getRetry().isContextAspectEnabled()).isTrue();

            assertThat(props.getOpenapi().isEnabled()).isTrue();
            assertThat(props.getOpenapi().getPort()).isEqualTo(8080);
            assertThat(props.getOpenapi().getPrefix()).isEqualTo("snail-job");
            assertThat(props.getOpenapi().getTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(props.getOpenapi().getMaxRetries()).isEqualTo(3);
            assertThat(props.getOpenapi().getRetryInterval()).isEqualTo(Duration.ofMillis(500));

            assertThat(props.getAlert().isEnabled()).isTrue();
            assertThat(props.getAlert().getSuppressDuration()).isEqualTo(Duration.ofMinutes(5));
            assertThat(props.getAlert().getSuppressThreshold()).isEqualTo(3);

            assertThat(props.getHealthCacheTtl()).isEqualTo(Duration.ofSeconds(10));
            assertThat(props.getHealthProbeTimeout()).isEqualTo(Duration.ofMillis(800));
            assertThat(props.getRetry().getContextTtl()).isEqualTo(Duration.ofHours(24));
            assertThat(props.getRetry().getLocalMaxAttempts()).isEqualTo(3);
            assertThat(props.getRetry().getLocalBackoff()).isEqualTo(Duration.ofMillis(200));
            assertThat(props.getRetry().getInMemoryMaxSize()).isEqualTo(10000);
            assertThat(props.getBatch().getDefaultBatchSize()).isEqualTo(1000);
            assertThat(props.getBatch().getDefaultMaxConcurrency()).isEqualTo(8);
            assertThat(props.getBatch().getProgressLogInterval()).isEqualTo(5000);
            assertThat(props.getBatch().getMaxFailedItems()).isEqualTo(500);

            SnailJobPropertiesBridge bridge = context.getBean(SnailJobPropertiesBridge.class);
            assertThat(bridge.isServerConfigured()).isTrue();
        });
    }

    @Test
    @DisplayName("测试显式关闭 enabled 时自动装配平滑旁路退出")
    void testAutoConfigurationDisabled() {
        contextRunner
                .withPropertyValues("ateng.boot.job.snail.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengSnailJobAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(io.github.atengk.boot.job.snail.properties.SnailJobProperties.class);
                    assertThat(context).doesNotHaveBean(SnailJobPropertiesBridge.class);
                });
    }

    @Test
    @DisplayName("测试强类型配置属性绑定 (Duration、子模块配置)")
    void testPropertyBinding() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.job.snail.server=192.168.1.100:18888",
                        "ateng.boot.job.snail.group=order-service",
                        "ateng.boot.job.snail.namespace=prod",
                        "ateng.boot.job.snail.token=secret-token-123",
                        "ateng.boot.job.snail.port=27889",
                        "ateng.boot.job.snail.timeout=15s",
                        "ateng.boot.job.snail.drain-timeout=45s",
                        "ateng.boot.job.snail.default-job-timeout=30m",
                        "ateng.boot.job.snail.max-payload-size=5MB",
                        "ateng.boot.job.snail.job.enabled=false",
                        "ateng.boot.job.snail.retry.context-aspect-enabled=false",
                        "ateng.boot.job.snail.openapi.port=9090",
                        "ateng.boot.job.snail.openapi.prefix=api/v1/snail",
                        "ateng.boot.job.snail.openapi.https=true",
                        "ateng.boot.job.snail.alert.suppress-threshold=5",
                        "ateng.boot.job.snail.alert.suppress-duration=10m"
                )
                .run(context -> {
                    io.github.atengk.boot.job.snail.properties.SnailJobProperties props =
                            context.getBean(io.github.atengk.boot.job.snail.properties.SnailJobProperties.class);

                    assertThat(props.getServer()).isEqualTo("192.168.1.100:18888");
                    assertThat(props.getGroup()).isEqualTo("order-service");
                    assertThat(props.getNamespace()).isEqualTo("prod");
                    assertThat(props.getToken()).isEqualTo("secret-token-123");
                    assertThat(props.getPort()).isEqualTo(27889);
                    assertThat(props.getTimeout()).isEqualTo(Duration.ofSeconds(15));
                    assertThat(props.getDrainTimeout()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(props.getDefaultJobTimeout()).isEqualTo(Duration.ofMinutes(30));
                    assertThat(props.getMaxPayloadSize()).isEqualTo(DataSize.ofMegabytes(5));

                    assertThat(props.getJob().isEnabled()).isFalse();
                    assertThat(props.getRetry().isContextAspectEnabled()).isFalse();

                    assertThat(props.getOpenapi().getPort()).isEqualTo(9090);
                    assertThat(props.getOpenapi().getPrefix()).isEqualTo("api/v1/snail");
                    assertThat(props.getOpenapi().isHttps()).isTrue();

                    assertThat(props.getAlert().getSuppressThreshold()).isEqualTo(5);
                    assertThat(props.getAlert().getSuppressDuration()).isEqualTo(Duration.ofMinutes(10));
                });
    }

    @Test
    @DisplayName("测试当服务端地址为空时平滑探测旁路不崩溃")
    void testSilentProbeWhenServerBlank() {
        contextRunner
                .withPropertyValues("ateng.boot.job.snail.server=")
                .run(context -> {
                    assertThat(context).hasSingleBean(SnailJobPropertiesBridge.class);
                    SnailJobPropertiesBridge bridge = context.getBean(SnailJobPropertiesBridge.class);
                    assertThat(bridge.isServerConfigured()).isFalse();
                });
    }

    @Test
    @DisplayName("测试用户自定义 Bean 可平滑接管默认装配 (@ConditionalOnMissingBean)")
    void testMissingBeanOverride() {
        contextRunner
                .withUserConfiguration(CustomBridgeConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(SnailJobPropertiesBridge.class);
                    SnailJobPropertiesBridge bridge = context.getBean(SnailJobPropertiesBridge.class);
                    assertThat(bridge.getProperties().getServer()).isEqualTo("custom-server:9999");
                });
    }

    @Test
    @DisplayName("测试同步映射至 SnailJob 原生配置对象 (支持逗号分隔集群地址)")
    void testSyncNativeProperties() {
        contextRunner
                .withUserConfiguration(NativePropertiesConfiguration.class)
                .withPropertyValues(
                        "ateng.boot.job.snail.server=10.0.0.1:18888,10.0.0.2:18888",
                        "ateng.boot.job.snail.namespace=test-namespace",
                        "ateng.boot.job.snail.group=test-group",
                        "ateng.boot.job.snail.token=token-abc",
                        "ateng.boot.job.snail.port=19999"
                )
                .run(context -> {
                    SnailJobProperties nativeProps = context.getBean(SnailJobProperties.class);
                    assertThat(nativeProps.getServer().getHost()).isEqualTo("10.0.0.1");
                    assertThat(nativeProps.getServer().getPort()).isEqualTo(18888);
                    assertThat(nativeProps.getNamespace()).isEqualTo("test-namespace");
                    assertThat(nativeProps.getGroup()).isEqualTo("test-group");
                    assertThat(nativeProps.getToken()).isEqualTo("token-abc");
                    assertThat(nativeProps.getPort()).isEqualTo(19999);
                });
    }

    @Test
    @DisplayName("测试显式关闭 openapi 与 log-mirror 时相关 Bean 不被装配")
    void testOpenApiAndLogMirrorDisabled() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.job.snail.openapi.enabled=false",
                        "ateng.boot.job.snail.log-mirror-enabled=false"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate.class);
                    assertThat(context).doesNotHaveBean(io.github.atengk.boot.job.snail.log.SnailJobLogbackAppenderRegistrar.class);
                });
    }

    @Test
    @DisplayName("测试自定义 SnailJobAdminTemplate 可平滑接管默认装配 (@ConditionalOnMissingBean)")
    void testCustomAdminTemplateOverride() {
        contextRunner
                .withUserConfiguration(CustomAdminTemplateConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate.class);
                    io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate adminTemplate =
                            context.getBean(io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate.class);
                    assertThat(adminTemplate).isNotNull();
                });
    }

    @Test
    @DisplayName("测试 Core 受管线程池 taskExecutor 真注入且批量并发绝不回落 ForkJoinPool.commonPool")
    void testCoreTaskExecutorInjectionWithoutForkJoinFallback() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengAsyncAutoConfiguration.class, AtengSnailJobAutoConfiguration.class))
                .run(context -> {
                    Executor coreExecutor = context.getBean("taskExecutor", Executor.class);
                    SnailShardingBatchTemplate batchTemplate = context.getBean(SnailShardingBatchTemplate.class);
                    SnailJobAdminTemplate adminTemplate = context.getBean(SnailJobAdminTemplate.class);
                    SnailJobContextAspect contextAspect = context.getBean(SnailJobContextAspect.class);

                    assertThat(batchTemplate.getEffectiveExecutor()).isSameAs(coreExecutor);
                    assertThat(batchTemplate.getEffectiveExecutor()).isNotSameAs(ForkJoinPool.commonPool());
                    assertThat(adminTemplate.getTaskExecutor()).isSameAs(coreExecutor);
                    assertThat(contextAspect.getTaskExecutor()).isSameAs(coreExecutor);

                    Set<String> workerThreads = ConcurrentHashMap.newKeySet();
                    List<Long> data = List.of(1L, 2L, 3L, 4L);
                    BatchExecutionResult<Long> res = batchTemplate.executeStepParallel(
                            0L,
                            2,
                            2,
                            (lastId, size) -> data.stream().filter(id -> id > lastId).limit(size).toList(),
                            id -> id,
                            chunk -> workerThreads.add(Thread.currentThread().getName())
                    );
                    assertThat(res.getSuccessCount()).isEqualTo(4);
                    assertThat(workerThreads).isNotEmpty();
                    assertThat(workerThreads).noneMatch(name -> name.contains("ForkJoinPool.commonPool"));
                });
    }

    @Test
    @DisplayName("测试 syncNativeProperties 同步 openapi 全部连接属性及 Server Host 自动回退")
    void testSyncNativePropertiesSyncsOpenApiConfig() {
        contextRunner
                .withUserConfiguration(NativePropertiesConfiguration.class)
                .withPropertyValues(
                        "ateng.boot.job.snail.server=10.0.0.1:18888,10.0.0.2:18888",
                        "ateng.boot.job.snail.openapi.port=9090",
                        "ateng.boot.job.snail.openapi.prefix=api/v1/snail",
                        "ateng.boot.job.snail.openapi.https=true"
                )
                .run(context -> {
                    SnailJobProperties nativeProps = context.getBean(SnailJobProperties.class);
                    assertThat(nativeProps.getOpenapi().getHost()).isEqualTo("10.0.0.1");
                    assertThat(nativeProps.getOpenapi().getPort()).isEqualTo(9090);
                    assertThat(nativeProps.getOpenapi().getPrefix()).isEqualTo("api/v1/snail");
                    assertThat(nativeProps.getOpenapi().isHttps()).isTrue();
                });

        contextRunner
                .withUserConfiguration(NativePropertiesConfiguration.class)
                .withPropertyValues(
                        "ateng.boot.job.snail.server=10.0.0.1:18888",
                        "ateng.boot.job.snail.openapi.host=openapi.internal.local"
                )
                .run(context -> {
                    SnailJobProperties nativeProps = context.getBean(SnailJobProperties.class);
                    assertThat(nativeProps.getOpenapi().getHost()).isEqualTo("openapi.internal.local");
                });
    }

    @Test
    @DisplayName("测试 job.enabled 与 retry.enabled 条件门控准确控制对应组件装配")
    void testJobAndRetryEnabledGates() {
        contextRunner
                .withPropertyValues("ateng.boot.job.snail.job.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.job.snail.properties.SnailJobProperties.class);
                    assertThat(context).doesNotHaveBean(SnailLocalWatchdog.class);
                    assertThat(context).doesNotHaveBean(SnailJobContextAspect.class);
                    assertThat(context).doesNotHaveBean(SnailShardingBatchTemplate.class);
                    assertThat(context).doesNotHaveBean(TenantJobTemplate.class);
                    assertThat(context).hasSingleBean(SnailRetryTemplate.class);
                    assertThat(context).hasSingleBean(SnailRetryContextAspect.class);
                });

        contextRunner
                .withPropertyValues("ateng.boot.job.snail.retry.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SnailRetryTemplate.class);
                    assertThat(context).doesNotHaveBean(SnailRetryContextAspect.class);
                    assertThat(context).doesNotHaveBean(SnailRetryContextStore.class);
                    assertThat(context).doesNotHaveBean(SnailRetryDeadLetterCallback.class);
                    assertThat(context).hasSingleBean(SnailJobContextAspect.class);
                    assertThat(context).hasSingleBean(SnailLocalWatchdog.class);
                });
    }

    @Test
    @DisplayName("测试缺省 Logback 类路径下 SnailJobLogbackAppenderRegistrar 自动平滑退避跳过")
    void testLogbackRegistrarSkippedWithoutLogbackClass() {
        contextRunner
                .withClassLoader(new FilteredClassLoader("ch.qos.logback.classic.LoggerContext"))
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengSnailJobAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(SnailJobLogbackAppenderRegistrar.class);
                });
    }

    @Test
    @DisplayName("测试 AtengSnailJobScanner 独立扫描 @AtengSnailJob 且与双写 @JobExecutor 幂等共存")
    void testAtengSnailJobScannerStandaloneAndDualCompatibility() {
        contextRunner
                .withUserConfiguration(StandaloneJobScannerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengSnailJobScanner.class);
                    Scanner scanner = context.getBean(AtengSnailJobScanner.class);
                    List<JobExecutorInfo> scanned = scanner.doScan();

                    List<String> names = scanned.stream().map(JobExecutorInfo::getExecutorName).toList();
                    assertThat(names).containsExactlyInAnyOrder(
                            "standaloneNoArgJob",
                            "standaloneDtoParamJob",
                            "classStandaloneJob"
                    );
                    assertThat(names).doesNotContain("dualAnnotatedJob");
                });
    }

    @Test
    @DisplayName("测试 JobExecutorRegistrar 真实注册将独立与类级 @AtengSnailJob 写入缓存并触发执行")
    void testJobExecutorRegistrarEndToEndWithStandaloneAndDtoJob() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.aop.AopAutoConfiguration.class,
                        AtengAsyncAutoConfiguration.class,
                        AtengSnailJobAutoConfiguration.class))
                .withUserConfiguration(NativePropertiesConfiguration.class, StandaloneJobScannerConfiguration.class)
                .withBean(com.aizuda.snailjob.common.core.context.SnailSpringContext.class)
                .run(context -> {
                    SnailJobTestHelper.clear();
                    JobExecutorScanner nativeScanner = new JobExecutorScanner();
                    nativeScanner.setApplicationContext(context);
                    AtengSnailJobScanner atengScanner = context.getBean(AtengSnailJobScanner.class);

                    JobExecutorRegistrar registrar = new JobExecutorRegistrar(List.of(nativeScanner, atengScanner));
                    try {
                        registrar.registerRetryHandler(nativeScanner.doScan());
                        registrar.registerRetryHandler(atengScanner.doScan());

                        JobExecutorInfo noArgInfo = JobExecutorInfoCache.get("standaloneNoArgJob");
                        JobExecutorInfo dtoInfo = JobExecutorInfoCache.get("standaloneDtoParamJob");
                        JobExecutorInfo classInfo = JobExecutorInfoCache.get("classStandaloneJob");
                        JobExecutorInfo dualInfo = JobExecutorInfoCache.get("dualAnnotatedJob");

                        assertThat(noArgInfo).isNotNull();
                        assertThat(dtoInfo).isNotNull();
                        assertThat(classInfo).isNotNull();
                        assertThat(dualInfo).isNotNull();

                        JobArgs dtoArgs = new JobArgs();
                        dtoArgs.setJobParams("{\"name\":\"RegistrarDto\"}");
                        Object dtoResult = dtoInfo.getMethod().invoke(dtoInfo.getExecutor(), dtoArgs);
                        assertThat(dtoResult).isInstanceOf(ExecuteResult.class);
                        assertThat(((ExecuteResult) dtoResult).getResult()).isEqualTo("dto:RegistrarDto");

                        Object classResult = classInfo.getMethod().invoke(classInfo.getExecutor());
                        assertThat(classResult).isInstanceOf(ExecuteResult.class);
                        assertThat(((ExecuteResult) classResult).getResult()).isEqualTo("class-job");

                        Object dualResult = dualInfo.getMethod().invoke(dualInfo.getExecutor(), new JobArgs());
                        assertThat(dualResult).isInstanceOf(ExecuteResult.class);
                        assertThat(((ExecuteResult) dualResult).getResult()).isEqualTo("dual");
                    } finally {
                        SnailJobTestHelper.clear();
                    }
                });
    }

    @Test
    @DisplayName("测试 AtengSnailJobScanner 检测到独立任务重名冲突时 Fail-Fast 抛出异常")
    void testAtengSnailJobScannerFailsFastOnDuplicateStandaloneName() {
        contextRunner
                .withUserConfiguration(DuplicateStandaloneNameConfiguration.class)
                .run(context -> {
                    AtengSnailJobScanner scanner = context.getBean(AtengSnailJobScanner.class);
                    org.assertj.core.api.Assertions.assertThatThrownBy(scanner::doScan)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("duplicateStandaloneJob");
                });
    }

    @Test
    @DisplayName("测试 AtengSnailJobScanner 类级注解缺少目标执行方法时 Fail-Fast 抛出异常")
    void testAtengSnailJobScannerFailsFastWhenClassLevelMethodMissing() {
        contextRunner
                .withUserConfiguration(MissingClassMethodConfiguration.class)
                .run(context -> {
                    AtengSnailJobScanner scanner = context.getBean(AtengSnailJobScanner.class);
                    org.assertj.core.api.Assertions.assertThatThrownBy(scanner::doScan)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("missingMethodClassJob");
                });
    }

    @Test
    @DisplayName("测试 managed-pool-enabled=false 时退回调用方线程且模板不绑定受管池")
    void testManagedPoolDisabledFallsBackToCallerExecution() {
        String callerThreadName = Thread.currentThread().getName();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.aop.AopAutoConfiguration.class,
                        AtengAsyncAutoConfiguration.class,
                        AtengSnailJobAutoConfiguration.class))
                .withUserConfiguration(StandaloneJobScannerConfiguration.class)
                .withPropertyValues("ateng.boot.job.snail.managed-pool-enabled=false")
                .run(context -> {
                    SnailJobAdminTemplate adminTemplate = context.getBean(SnailJobAdminTemplate.class);
                    assertThat(adminTemplate.getTaskExecutor()).isNull();

                    SnailShardingBatchTemplate batchTemplate = context.getBean(SnailShardingBatchTemplate.class);
                    assertThat(batchTemplate.getEffectiveExecutor()).isNotSameAs(context.getBean("taskExecutor"));

                    SampleScanJobs jobs = context.getBean(SampleScanJobs.class);
                    ExecuteResult result = jobs.standaloneNoArg();
                    assertThat(result.getResult()).isEqualTo("ok:" + callerThreadName);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class StandaloneJobScannerConfiguration {

        @Bean
        public SampleScanJobs sampleScanJobs() {
            return new SampleScanJobs();
        }

        @Bean
        public ClassLevelStandaloneJob classLevelStandaloneJob() {
            return new ClassLevelStandaloneJob();
        }
    }

    static class SampleScanJobs {

        @AtengSnailJob(name = "standaloneNoArgJob", retryCount = 1, retryInterval = "10ms")
        public ExecuteResult standaloneNoArg() {
            return ExecuteResult.success("ok:" + Thread.currentThread().getName());
        }

        @AtengSnailJob(name = "standaloneDtoParamJob")
        public ExecuteResult standaloneDto(JobArgs args, SampleDto dto) {
            return ExecuteResult.success("dto:" + (dto != null ? dto.name() : "null"));
        }

        @JobExecutor(name = "dualAnnotatedJob")
        @AtengSnailJob(timeout = "200ms")
        public ExecuteResult dualAnnotated(JobArgs args) {
            return ExecuteResult.success("dual");
        }
    }

    @AtengSnailJob(name = "classStandaloneJob", method = "runJob")
    static class ClassLevelStandaloneJob {
        public ExecuteResult runJob() {
            return ExecuteResult.success("class-job");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DuplicateStandaloneNameConfiguration {

        @Bean
        public DuplicateJobA duplicateJobA() {
            return new DuplicateJobA();
        }

        @Bean
        public DuplicateJobB duplicateJobB() {
            return new DuplicateJobB();
        }
    }

    static class DuplicateJobA {
        @AtengSnailJob(name = "duplicateStandaloneJob")
        public ExecuteResult executeFirst() {
            return ExecuteResult.success();
        }
    }

    static class DuplicateJobB {
        @AtengSnailJob(name = "duplicateStandaloneJob")
        public ExecuteResult executeSecond() {
            return ExecuteResult.success();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MissingClassMethodConfiguration {

        @Bean
        public MissingClassMethodJob missingClassMethodJob() {
            return new MissingClassMethodJob();
        }
    }

    @AtengSnailJob(name = "missingMethodClassJob", method = "nonExistentMethod")
    static class MissingClassMethodJob {
        public ExecuteResult otherMethod() {
            return ExecuteResult.success();
        }
    }

    record SampleDto(String name) {
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomAdminTemplateConfiguration {
        @Bean
        public io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate customAdminTemplate() {
            return new io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomBridgeConfiguration {
        @Bean
        public SnailJobPropertiesBridge customBridge() {
            io.github.atengk.boot.job.snail.properties.SnailJobProperties props =
                    new io.github.atengk.boot.job.snail.properties.SnailJobProperties();
            props.setServer("custom-server:9999");
            return new SnailJobPropertiesBridge(props, true);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NativePropertiesConfiguration {
        @Bean
        public SnailJobProperties nativeProperties() {
            return new SnailJobProperties();
        }
    }
}

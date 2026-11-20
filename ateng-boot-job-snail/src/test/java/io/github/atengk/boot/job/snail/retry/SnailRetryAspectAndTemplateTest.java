package io.github.atengk.boot.job.snail.retry;

import com.aizuda.snailjob.client.core.Report;
import com.aizuda.snailjob.client.core.annotation.Retryable;
import com.aizuda.snailjob.client.core.cache.RetryerInfoCache;
import com.aizuda.snailjob.client.core.intercepter.RetrySiteSnapshot;
import com.aizuda.snailjob.client.core.intercepter.SnailRetryInterceptor;
import com.aizuda.snailjob.client.core.intercepter.SnailRetryPointcutAdvisor;
import com.aizuda.snailjob.client.core.register.RetryableRegistrar;
import com.aizuda.snailjob.client.core.register.scan.RetryableScanner;
import com.aizuda.snailjob.client.core.retryer.RetryType;
import com.aizuda.snailjob.client.core.retryer.RetryerInfo;
import com.aizuda.snailjob.client.core.strategy.LocalRetryStrategies;
import com.aizuda.snailjob.client.core.strategy.RetryStrategy;
import com.aizuda.snailjob.common.core.context.SnailSpringContext;
import com.aizuda.snailjob.common.core.model.IdempotentIdContext;
import com.aizuda.snailjob.common.core.model.SnailJobHeaders;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.snail.annotation.AtengSnailRetry;
import io.github.atengk.boot.job.snail.autoconfigure.AtengSnailJobAutoConfiguration;
import io.github.atengk.boot.job.snail.enums.JobTenantMode;
import io.github.atengk.boot.job.snail.event.SnailRetryDeadLetterEvent;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import io.github.atengk.boot.job.snail.retry.aspect.SnailRetryContextAspect;
import io.github.atengk.boot.job.snail.retry.callback.SnailRetryDeadLetterCallback;
import io.github.atengk.boot.job.snail.retry.context.InMemorySnailRetryContextStore;
import io.github.atengk.boot.job.snail.retry.context.SnailRetryContextRegistry;
import io.github.atengk.boot.job.snail.retry.context.SnailRetryContextStore;
import io.github.atengk.boot.job.snail.retry.context.SnailRetryFailureTracker;
import io.github.atengk.boot.job.snail.retry.generator.ArgsHashIdempotentIdGenerator;
import io.github.atengk.boot.job.snail.retry.generator.SpelIdempotentIdGenerator;
import io.github.atengk.boot.job.snail.retry.scanner.AtengSnailRetryScanner;
import io.github.atengk.boot.job.snail.retry.template.SnailRetryTemplate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.aop.Advisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SnailJob 分布式重试四维现场保真、SpEL 幂等与 SnailRetryTemplate 单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootTest(classes = {
        AtengSnailJobAutoConfiguration.class,
        SnailRetryAspectAndTemplateTest.TestConfig.class
})
class SnailRetryAspectAndTemplateTest {

    @Autowired
    private MockRetryService mockRetryService;

    @Autowired
    private SnailRetryTemplate retryTemplate;

    @Autowired
    private SpelIdempotentIdGenerator spelIdempotentIdGenerator;

    @Autowired
    private ArgsHashIdempotentIdGenerator argsHashIdempotentIdGenerator;

    @Autowired
    private SnailRetryContextStore retryContextStore;

    @Autowired
    private SnailRetryDeadLetterCallback deadLetterCallback;

    @Autowired
    private TestDeadLetterListener deadLetterListener;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
        retryContextStore.clear();
        SnailRetryContextRegistry.clear();
        SnailRetryFailureTracker.clear();
        RetrySiteSnapshot.removeAll();
        SnailJobTestHelper.clear();
        deadLetterListener.clear();
    }

    @Test
    @DisplayName("测试远程重试跨时间/跨节点保真还原 MDC traceId、User 与 TenantId，并在 finally 恢复现场")
    void testRemoteRetryContextRestorationAndCleanup() {
        // 1. 模拟业务首次调用，具备四维上下文
        MDC.put(SnailRetryContextAspect.TRACE_ID_KEY, "trace-orig-1001");
        UserContextHolder.set("operator-user-1");
        TenantContextHolder.setTenantId("tenant-finance-01");

        OrderCommand order = new OrderCommand("ORDER_20261031_001", "item_a", 100);

        // 首次调用（正常注册快照）
        String res = mockRetryService.payOrder(order);
        assertThat(res).isEqualTo("success: ORDER_20261031_001");

        // 2. 模拟远程重试触发：处于另外的工作线程（初始上下文完全为空）
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();

        // 设置当前处于 SnailJob 远程重试阶段
        RetrySiteSnapshot.setStage(RetrySiteSnapshot.EnumStage.REMOTE.getStage());

        try {
            // 执行远程重试
            String retryRes = mockRetryService.payOrder(order);
            assertThat(retryRes).isEqualTo("success: ORDER_20261031_001");
        } finally {
            RetrySiteSnapshot.removeAll();
        }

        // 验证执行结束后工作线程现场已被严格还原清理，绝无线程池复用脏读
        assertThat(MDC.get(SnailRetryContextAspect.TRACE_ID_KEY)).isNull();
        assertThat(UserContextHolder.getExplicit()).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    @DisplayName("测试 SpelIdempotentIdGenerator 正确通过 SpEL 表达式提取业务唯一幂等键")
    void testSpelIdempotentIdGeneration() throws Exception {
        OrderCommand order = new OrderCommand("ORDER_SN_8888", "phone", 1);
        IdempotentIdContext context = new IdempotentIdContext(
                "orderPayScene",
                MockRetryService.class.getName(),
                new Object[]{order},
                null,
                "payOrder"
        );

        String idempotentId = spelIdempotentIdGenerator.idGenerate(context);
        assertThat(idempotentId).isEqualTo("orderPayScene_ORDER_SN_8888");
    }

    @Test
    @DisplayName("测试 ArgsHashIdempotentIdGenerator 生成确定性 64 位 SHA-256 散列值")
    void testArgsHashIdempotentIdGeneration() throws Exception {
        IdempotentIdContext context = new IdempotentIdContext(
                "stockDeductScene",
                "com.example.StockService",
                new Object[]{"PROD_001", 10},
                "{\"prod\":\"PROD_001\",\"qty\":10}",
                "deduct"
        );

        String hash1 = argsHashIdempotentIdGenerator.idGenerate(context);
        String hash2 = argsHashIdempotentIdGenerator.idGenerate(context);

        assertThat(hash1).isNotNull().hasSize(64);
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("测试 SnailRetryTemplate 编程式执行成功返回结果")
    void testRetryTemplateExecuteSuccess() {
        AtomicInteger count = new AtomicInteger(0);

        String result = retryTemplate.execute("queryScene", () -> {
            if (count.incrementAndGet() < 2) {
                throw new RuntimeException("临时网络抖动");
            }
            return "SUCCESS_DATA";
        });

        assertThat(result).isEqualTo("SUCCESS_DATA");
        assertThat(count.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("测试 SnailRetryTemplate 耗尽重试后转译为 BusinessException 并上报")
    void testRetryTemplateExhaustedThrowsBusinessException() {
        AtomicInteger count = new AtomicInteger(0);

        assertThatThrownBy(() -> retryTemplate.executeWithIdempotent("failScene", "IDEM_999", () -> {
            count.incrementAndGet();
            throw new IllegalStateException("下游服务不可用");
        }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("业务重试耗尽");

        assertThat(count.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("测试并发多租户下通过细粒度幂等键实现四维上下文绝对隔离，杜绝跨租户串写")
    void testConcurrentMultiTenantRetryContextIsolation() {
        // 1. 租户 A 执行订单 A
        MDC.put(SnailRetryContextAspect.TRACE_ID_KEY, "trace-tenant-A");
        UserContextHolder.set("user-A");
        TenantContextHolder.setTenantId("tenant-A");
        OrderCommand orderA = new OrderCommand("ORDER_AAA", "item_a", 100);
        mockRetryService.payOrder(orderA);

        // 2. 租户 B 执行订单 B
        MDC.put(SnailRetryContextAspect.TRACE_ID_KEY, "trace-tenant-B");
        UserContextHolder.set("user-B");
        TenantContextHolder.setTenantId("tenant-B");
        OrderCommand orderB = new OrderCommand("ORDER_BBB", "item_b", 200);
        mockRetryService.payOrder(orderB);

        // 3. 模拟进入远程重试阶段
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        RetrySiteSnapshot.setStage(RetrySiteSnapshot.EnumStage.REMOTE.getStage());

        try {
            // 远程重试订单 A：断言恢复租户 A 的四维现场，绝不被租户 B 覆盖
            mockRetryService.payOrderWithTenantAssert(orderA, "trace-tenant-A", "user-A", "tenant-A");

            // 远程重试订单 B：断言恢复租户 B 的四维现场
            mockRetryService.payOrderWithTenantAssert(orderB, "trace-tenant-B", "user-B", "tenant-B");
        } finally {
            RetrySiteSnapshot.removeAll();
        }
    }

    @Test
    @DisplayName("测试 SnailRetryTemplate 手动上报方法在无外部服务时平滑保底返回")
    void testReportManualRetryFallback() {
        boolean reported = retryTemplate.reportManualRetry(
                "manualScene",
                "com.example.PaymentService",
                new Object[]{"PAY_123"},
                "IDEM_PAY_123",
                "BIZ_NO_999"
        );

        assertThat(reported).isTrue();
    }

    @Test
    @DisplayName("测试 SnailRetryContextStore 默认自动装配 InMemorySnailRetryContextStore 并正常工作")
    void testRetryContextStoreAutowired() {
        assertThat(retryContextStore).isNotNull();
        assertThat(retryContextStore).isInstanceOf(InMemorySnailRetryContextStore.class);

        AsyncContext.ContextSnapshot snapshot = AsyncContext.ContextSnapshot.capture();
        retryContextStore.put("test_key", snapshot);
        assertThat(retryContextStore.get("test_key")).isEqualTo(snapshot);

        retryContextStore.remove("test_key");
        assertThat(retryContextStore.get("test_key")).isNull();
    }

    @Test
    @DisplayName("测试远程重试阶段未命中快照且入参缺少 getTenantId 访问器时记录警告日志并安全保底")
    void testRemoteRetryFallbackWithoutTenantAccessor() {
        NoTenantCommand cmd = new NoTenantCommand("CMD_NO_TENANT_100", "test");
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();

        RetrySiteSnapshot.setStage(RetrySiteSnapshot.EnumStage.REMOTE.getStage());
        try {
            String result = mockRetryService.processNoTenant(cmd);
            assertThat(result).isEqualTo("processed: CMD_NO_TENANT_100");
            // 验证租户未被设置，不会抛错
            assertThat(TenantContextHolder.getTenantId()).isNull();
        } finally {
            RetrySiteSnapshot.removeAll();
        }
    }

    @Test
    @DisplayName("测试仅标注 @AtengSnailRetry（value 与 scene 两种写法）可被 RetryableRegistrar 扫描并通过真实 AOP 代理重试")
    void testStandaloneAtengSnailRetryScannedAndInterceptedByNativeAop() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, AtengSnailJobAutoConfiguration.class))
                .withUserConfiguration(NativeRetryAopTestConfig.class)
                .run(context -> {
                    SnailJobTestHelper.clear();
                    try {
                        RetryableScanner scanner = new RetryableScanner();
                        scanner.setApplicationContext(context);
                        AtengSnailRetryScanner atengScanner = context.getBean(AtengSnailRetryScanner.class);
                        RetryableRegistrar registrar = new RetryableRegistrar();
                        registrar.registerRetryHandler(scanner.doScan());
                        registrar.registerRetryHandler(atengScanner.doScan());

                        String targetClassName = StandaloneAnnotatedRetryService.class.getName();
                        RetryerInfo valueInfo = RetryerInfoCache.get("valueAliasOnlyScene", targetClassName);
                        RetryerInfo sceneInfo = RetryerInfoCache.get("sceneOnlyScene", targetClassName);
                        RetryerInfo dualInfo = RetryerInfoCache.get("nativePrecedenceScene", targetClassName);

                        assertThat(valueInfo).isNotNull();
                        assertThat(valueInfo.getLocalTimes()).isEqualTo(2);
                        assertThat(valueInfo.getIdempotentIdGenerate()).isEqualTo(SpelIdempotentIdGenerator.class);

                        assertThat(sceneInfo).isNotNull();
                        assertThat(sceneInfo.getLocalTimes()).isEqualTo(2);

                        // 混用时显式直写 @Retryable 优先生效
                        assertThat(dualInfo).isNotNull();
                        assertThat(dualInfo.getLocalTimes()).isEqualTo(5);
                        assertThat(RetryerInfoCache.get("ignoredAtengScene", targetClassName)).isNull();

                        StandaloneAnnotatedRetryService service = context.getBean(StandaloneAnnotatedRetryService.class);
                        TenantParamCommand cmd = new TenantParamCommand("ORD-STANDALONE-1", "tenant-param-99");

                        String res = service.retryByValueAlias(cmd);
                        assertThat(res).isEqualTo("recovered:ORD-STANDALONE-1:tenant-param-99");
                        assertThat(service.getObservedFirstTenant()).isEqualTo("tenant-param-99");
                        // 首次执行应用的租户应在方法退出后恢复，不污染调用方线程
                        assertThat(TenantContextHolder.getTenantId()).isNull();

                        assertThat(service.retryBySceneOnly(cmd)).isEqualTo("scene-ok:true");
                        assertThat(service.dualAnnotatedPrecedence(cmd)).isEqualTo("dual-ok:ORD-STANDALONE-1");
                    } finally {
                        SnailJobTestHelper.clear();
                    }
                });
    }

    @Test
    @DisplayName("测试 @AtengSnailRetry 首次执行生效 tenantMode、本地流不误删快照，且远程结束态完整清理关联键")
    void testFirstExecutionTenantModeAndCompleteCleanupOnRemoteCompletion() {
        TenantContextHolder.setTenantId("caller-tenant");
        try {
            String observed = mockRetryService.ignoreTenantFirstExecution("ORD-IGNORE-1");
            assertThat(observed).isEqualTo("ignore=true");
            // 退出后调用方原始租户完好无损
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("caller-tenant");
            assertThat(TenantContextHolder.isIgnoreTenant()).isFalse();
        } finally {
            TenantContextHolder.clear();
        }

        // 验证 value 别名在切面首次执行存储快照 + 远程结束态完整清理细粒度与粗粒度索引键
        MDC.put(SnailRetryContextAspect.TRACE_ID_KEY, "trace-clean-1");
        UserContextHolder.set("user-clean-1");
        TenantParamCommand cmd = new TenantParamCommand("ORD-CLEAN-1", "tenant-clean-1");
        mockRetryService.valueAliasOnly(cmd);

        String targetClass = MockRetryService.class.getName();
        String expectedContextKey = "valueSceneAlias:" + targetClass + ":valueAliasOnly";
        String expectedClassKey = "valueSceneAlias:" + targetClass;
        String expectedIdemKey = "valueSceneAlias_ORD-CLEAN-1";
        String expectedFineKey = expectedContextKey + ":" + expectedIdemKey;

        assertThat(retryContextStore.get(expectedFineKey)).isNotNull();
        assertThat(retryContextStore.get("valueSceneAlias:" + expectedIdemKey)).isNotNull();
        assertThat(retryContextStore.get(expectedContextKey)).isNotNull();
        assertThat(retryContextStore.get(expectedClassKey)).isNotNull();

        // 模拟本地重试阶段（stage=LOCAL, isRetry=true）：不得提前删除细粒度快照
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        SnailJobHeaders localHeaders = new SnailJobHeaders();
        localHeaders.setRetry(true);
        RetrySiteSnapshot.setRetryHeader(localHeaders);
        RetrySiteSnapshot.setStage(RetrySiteSnapshot.EnumStage.LOCAL.getStage());
        try {
            assertThatThrownBy(() -> mockRetryService.valueAliasFailOnce(cmd))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            RetrySiteSnapshot.removeAll();
        }
        // 本地重试失败时快照必须继续保留以供后续重试或远程上报消费
        assertThat(retryContextStore.get("valueSceneAlias:" + expectedIdemKey)).isNotNull();

        // 模拟远程重试成功完结：全部关联索引键必须被彻底清理，防止跨租户串扰
        RetrySiteSnapshot.setStage(RetrySiteSnapshot.EnumStage.REMOTE.getStage());
        try {
            mockRetryService.valueAliasOnly(cmd);
        } finally {
            RetrySiteSnapshot.removeAll();
        }
        assertThat(retryContextStore.get(expectedFineKey)).isNull();
        assertThat(retryContextStore.get("valueSceneAlias:" + expectedIdemKey)).isNull();
        assertThat(retryContextStore.get(expectedContextKey)).isNull();
        assertThat(retryContextStore.get(expectedClassKey)).isNull();
    }

    @Test
    @DisplayName("测试 SnailRetryTemplate 接入配置参数并在上报成功后保留快照、本地成功或上报失败后清除快照")
    void testRetryTemplateConfigWiringAndSnapshotLifecycle() {
        SnailJobProperties props = new SnailJobProperties();
        props.getRetry().setLocalMaxAttempts(2);
        props.getRetry().setLocalBackoff(Duration.ofMillis(10));

        InMemorySnailRetryContextStore localStore = new InMemorySnailRetryContextStore(100, Duration.ofHours(1));
        Report reportMock = Mockito.mock(Report.class);
        ObjectProvider<Report> reportProvider = simpleObjectProvider(reportMock);
        SnailRetryTemplate configuredTemplate = new SnailRetryTemplate(reportProvider, props, localStore);

        // 1. 本地成功执行：上下文快照立即清除
        MDC.put(SnailRetryContextAspect.TRACE_ID_KEY, "trace-tpl-ok");
        UserContextHolder.set("user-tpl-ok");
        TenantContextHolder.setTenantId("tenant-tpl-ok");
        String okRes = configuredTemplate.executeWithIdempotent("tplScene", "IDEM_OK", () -> "DONE");
        assertThat(okRes).isEqualTo("DONE");
        assertThat(localStore.get("tplScene:IDEM_OK")).isNull();

        // 2. 本地耗尽（按配置的 2 次）且 reportManualRetry 返回 true：必须保留上下文快照供远端回调还原
        Mockito.when(reportMock.report(Mockito.eq("tplScene"), Mockito.anyString(), Mockito.any()))
                .thenReturn(true);
        AtomicInteger attemptsKeep = new AtomicInteger(0);
        MDC.put(SnailRetryContextAspect.TRACE_ID_KEY, "trace-tpl-keep");
        UserContextHolder.set("user-tpl-keep");
        TenantContextHolder.setTenantId("tenant-tpl-keep");

        assertThatThrownBy(() -> configuredTemplate.executeWithIdempotent("tplScene", "IDEM_KEEP", "BIZ-KEEP", () -> {
            attemptsKeep.incrementAndGet();
            throw new IllegalStateException("remote pending");
        }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2 次");

        assertThat(attemptsKeep.get()).isEqualTo(2);
        AsyncContext.ContextSnapshot keptSnapshot = localStore.get("tplScene:IDEM_KEEP");
        assertThat(keptSnapshot).isNotNull();
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        keptSnapshot.apply();
        assertThat(MDC.get(SnailRetryContextAspect.TRACE_ID_KEY)).isEqualTo("trace-tpl-keep");
        assertThat(UserContextHolder.getExplicit()).isEqualTo("user-tpl-keep");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-tpl-keep");

        // 3. 本地耗尽且 reportManualRetry 返回 false（上报失败放弃远端重试）：及时清除上下文快照
        Mockito.when(reportMock.report(Mockito.eq("tplScene"), Mockito.anyString(), Mockito.any()))
                .thenReturn(false);
        assertThatThrownBy(() -> configuredTemplate.executeWithIdempotent("tplScene", "IDEM_DROP", () -> {
            throw new IllegalStateException("report failed");
        })).isInstanceOf(BusinessException.class);
        assertThat(localStore.get("tplScene:IDEM_DROP")).isNull();
    }

    @Test
    @DisplayName("测试 SnailRetryDeadLetterCallback 准确提取并透传幂等键、traceId、bizNo 与异常因果")
    void testDeadLetterCallbackExtractsIdempotentKeyTraceIdAndCause() {
        RetryerInfo info = new RetryerInfo(
                "orderPayScene",
                MockRetryService.class.getName(),
                java.util.Collections.emptySet(),
                java.util.Collections.emptySet(),
                mockRetryService,
                org.springframework.util.ReflectionUtils.findMethod(MockRetryService.class, "failWithCause", OrderCommand.class),
                RetryType.LOCAL_REMOTE,
                3,
                1,
                SpelIdempotentIdGenerator.class,
                "#order.orderNo",
                com.aizuda.snailjob.client.core.strategy.ExecutorAnnotationMethod.class,
                true,
                SnailRetryDeadLetterCallback.class,
                false,
                false,
                60000L,
                java.util.concurrent.TimeUnit.MILLISECONDS,
                com.aizuda.snailjob.client.core.RetryCondition.NoRetry.class
        );
        RetryerInfoCache.put(info);

        OrderCommand order = new OrderCommand("ORD-DL-999", "gpu", 5000);
        MDC.put(SnailRetryContextAspect.TRACE_ID_KEY, "trace-dl-888");
        UserContextHolder.set("user-dl-1");
        TenantContextHolder.setTenantId("tenant-dl-1");

        assertThatThrownBy(() -> mockRetryService.failWithCause(order))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("支付渠道连接池耗尽");

        // 模拟异地/异步回调线程无局部 MDC 现场
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();

        deadLetterCallback.doMaxRetryCallback("orderPayScene", MockRetryService.class.getName(), new Object[]{order});

        assertThat(deadLetterListener.events).hasSize(1);
        SnailRetryDeadLetterEvent event = deadLetterListener.events.get(0);
        assertThat(event.getScene()).isEqualTo("orderPayScene");
        assertThat(event.getIdempotentId()).isEqualTo("orderPayScene_ORD-DL-999");
        assertThat(event.getBizNo()).isEqualTo("ORD-DL-999");
        assertThat(event.getTraceId()).isEqualTo("trace-dl-888");
        assertThat(event.getThrowable())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("支付渠道连接池耗尽");
        assertThat(retryContextStore.get("orderPayScene:orderPayScene_ORD-DL-999")).isNull();
    }

    private static <T> ObjectProvider<T> simpleObjectProvider(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }
        };
    }

    @TestConfiguration
    @EnableAspectJAutoProxy
    static class TestConfig {
        @Bean
        public MockRetryService mockRetryService() {
            return new MockRetryService();
        }

        @Bean
        public TestDeadLetterListener testDeadLetterListener() {
            return new TestDeadLetterListener();
        }
    }

    static class TestDeadLetterListener implements ApplicationListener<SnailRetryDeadLetterEvent> {
        final List<SnailRetryDeadLetterEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onApplicationEvent(SnailRetryDeadLetterEvent event) {
            events.add(event);
        }

        void clear() {
            events.clear();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NoTenantCommand {
        private String orderNo;
        private String remark;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderCommand {
        private String orderNo;
        private String item;
        private Integer price;
    }

    @Service
    public static class MockRetryService {

        @AtengSnailRetry(scene = "orderPayScene", idempotentSpel = "#cmd.orderNo", tenantMode = JobTenantMode.PARAM)
        public String processNoTenant(NoTenantCommand cmd) {
            return "processed: " + cmd.getOrderNo();
        }

        @AtengSnailRetry(scene = "orderPayScene", idempotentSpel = "#order.orderNo", tenantMode = JobTenantMode.PARAM)
        public String payOrder(OrderCommand order) {
            // 如果处于远程重试阶段，断言四维上下文已被正确还原
            if (RetrySiteSnapshot.getStage() != null
                    && RetrySiteSnapshot.getStage() == RetrySiteSnapshot.EnumStage.REMOTE.getStage()) {
                assertThat(MDC.get(SnailRetryContextAspect.TRACE_ID_KEY)).isEqualTo("trace-orig-1001");
                assertThat(UserContextHolder.getExplicit()).isEqualTo("operator-user-1");
                assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-finance-01");
            }
            return "success: " + order.getOrderNo();
        }

        @AtengSnailRetry(scene = "orderPayScene", idempotentSpel = "#order.orderNo", tenantMode = JobTenantMode.PARAM)
        public String payOrderWithTenantAssert(OrderCommand order, String expectedTraceId, String expectedUser, String expectedTenant) {
            if (RetrySiteSnapshot.getStage() != null
                    && RetrySiteSnapshot.getStage() == RetrySiteSnapshot.EnumStage.REMOTE.getStage()) {
                assertThat(MDC.get(SnailRetryContextAspect.TRACE_ID_KEY)).isEqualTo(expectedTraceId);
                assertThat(UserContextHolder.getExplicit()).isEqualTo(expectedUser);
                assertThat(TenantContextHolder.getTenantId()).isEqualTo(expectedTenant);
            }
            return "success: " + order.getOrderNo();
        }

        @AtengSnailRetry(scene = "ignoreTenantScene", idempotentSpel = "#orderNo", tenantMode = JobTenantMode.IGNORE)
        public String ignoreTenantFirstExecution(String orderNo) {
            return "ignore=" + TenantContextHolder.isIgnoreTenant();
        }

        @AtengSnailRetry(value = "valueSceneAlias", idempotentSpel = "#cmd.orderNo", tenantMode = JobTenantMode.PARAM)
        public String valueAliasOnly(TenantParamCommand cmd) {
            return "ok:" + cmd.getOrderNo() + ":" + TenantContextHolder.getTenantId();
        }

        @AtengSnailRetry(value = "valueSceneAlias", idempotentSpel = "#cmd.orderNo", tenantMode = JobTenantMode.PARAM)
        public String valueAliasFailOnce(TenantParamCommand cmd) {
            throw new IllegalStateException("local retry transient failure");
        }

        @AtengSnailRetry(scene = "orderPayScene", idempotentSpel = "#order.orderNo", bizNo = "#order.orderNo", tenantMode = JobTenantMode.PARAM)
        public String failWithCause(OrderCommand order) {
            throw new IllegalStateException("支付渠道连接池耗尽");
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TenantParamCommand {
        private String orderNo;
        private String tenantId;
    }

    @Configuration(proxyBeanMethods = false)
    public static class NativeRetryAopTestConfig {
        @Bean
        public SnailSpringContext snailSpringContext() {
            return new SnailSpringContext();
        }

        @Bean
        public com.aizuda.snailjob.client.common.config.SnailJobProperties nativeSnailJobProperties() {
            return new com.aizuda.snailjob.client.common.config.SnailJobProperties();
        }

        @Bean
        public com.aizuda.snailjob.client.core.Report mockReport() {
            return org.mockito.Mockito.mock(com.aizuda.snailjob.client.core.Report.class);
        }

        @Bean
        public LocalRetryStrategies localRetryStrategies() {
            return new LocalRetryStrategies();
        }

        @Bean
        public Advisor snailJobPointcutAdvisor(RetryStrategy localRetryStrategies) {
            return new SnailRetryPointcutAdvisor(
                    new SnailRetryInterceptor(Ordered.HIGHEST_PRECEDENCE, localRetryStrategies));
        }

        @Bean
        public StandaloneAnnotatedRetryService standaloneAnnotatedRetryService() {
            return new StandaloneAnnotatedRetryService();
        }
    }

    public static class StandaloneAnnotatedRetryService {
        private final AtomicInteger valueAttempts = new AtomicInteger(0);
        private final AtomicReference<String> observedFirstTenant = new AtomicReference<>();

        @AtengSnailRetry(
                value = "valueAliasOnlyScene",
                idempotentSpel = "#cmd.orderNo",
                retryStrategy = RetryType.ONLY_LOCAL,
                localTimes = 2,
                localInterval = 1,
                tenantMode = JobTenantMode.PARAM
        )
        public String retryByValueAlias(TenantParamCommand cmd) {
            int attempt = valueAttempts.incrementAndGet();
            if (attempt == 1) {
                observedFirstTenant.set(TenantContextHolder.getTenantId());
                throw new IllegalStateException("first attempt transient error");
            }
            return "recovered:" + cmd.getOrderNo() + ":" + TenantContextHolder.getTenantId();
        }

        @AtengSnailRetry(
                scene = "sceneOnlyScene",
                idempotentSpel = "#cmd.orderNo",
                retryStrategy = RetryType.ONLY_LOCAL,
                localTimes = 2,
                localInterval = 1,
                tenantMode = JobTenantMode.IGNORE
        )
        public String retryBySceneOnly(TenantParamCommand cmd) {
            return "scene-ok:" + TenantContextHolder.isIgnoreTenant();
        }

        @Retryable(scene = "nativePrecedenceScene", localTimes = 5)
        @AtengSnailRetry(scene = "ignoredAtengScene", localTimes = 2, idempotentSpel = "#cmd.orderNo")
        public String dualAnnotatedPrecedence(TenantParamCommand cmd) {
            return "dual-ok:" + cmd.getOrderNo();
        }

        public String getObservedFirstTenant() {
            return observedFirstTenant.get();
        }
    }
}

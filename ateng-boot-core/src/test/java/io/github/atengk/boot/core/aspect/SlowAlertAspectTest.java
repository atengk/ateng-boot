package io.github.atengk.boot.core.aspect;

import io.github.atengk.boot.core.annotation.SlowAlert;
import io.github.atengk.boot.core.event.SlowAlertEvent;
import io.github.atengk.boot.core.properties.SlowAlertProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 慢调用切面 SlowAlertAspect 单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("慢调用切面 SlowAlertAspect 单测")
class SlowAlertAspectTest {

    private final SlowAlertProperties properties = new SlowAlertProperties();
    private final SlowAlertAspect aspect = new SlowAlertAspect(properties);

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("方法级 @SlowAlert 慢调用触发与脱敏入参记录测试")
    void shouldInterceptSlowMethodAndPreserveReturn() throws Exception {
        SampleService target = new SampleService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        SampleService proxy = factory.getProxy();

        String result = proxy.executeSlow("atengUser", "secretPassword123");
        assertThat(result).isEqualTo("ok:atengUser");
    }

    @Test
    @DisplayName("快方法执行不产生超时告警")
    void shouldNotAlertOnFastMethod() {
        SampleService target = new SampleService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        SampleService proxy = factory.getProxy();

        String result = proxy.executeFast();
        assertThat(result).isEqualTo("fast");
    }

    @Test
    @DisplayName("配置 includeArgs 为 false 时安全执行")
    void shouldSupportDisableArgsSnapshot() throws Exception {
        SampleService target = new SampleService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        SampleService proxy = factory.getProxy();

        proxy.executeWithoutArgs();
    }

    @Test
    @DisplayName("支持类级别 @SlowAlert 继承标注")
    void shouldSupportClassLevelAnnotation() throws Exception {
        ClassLevelService target = new ClassLevelService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        ClassLevelService proxy = factory.getProxy();

        proxy.doWork();
    }

    @Test
    @DisplayName("当全局开关 enabled 为 false 时，切面直接放行")
    void shouldBypassWhenDisabled() throws Exception {
        properties.setEnabled(false);

        SampleService target = new SampleService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        SampleService proxy = factory.getProxy();

        String result = proxy.executeSlow("user", "pass");
        assertThat(result).isEqualTo("ok:user");
    }

    @Test
    @DisplayName("发布 SlowAlertEvent：校验全量元数据、traceId、入参脱敏与超长截断")
    void shouldPublishSlowAlertEventWithMaskedTruncatedArgsAndTraceId() throws Exception {
        List<SlowAlertEvent> captured = new ArrayList<>();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof SlowAlertEvent slowAlertEvent) {
                captured.add(slowAlertEvent);
            }
        };
        SlowAlertProperties customProps = new SlowAlertProperties();
        SlowAlertAspect eventAspect = new SlowAlertAspect(customProps, publisher);

        SampleService target = new SampleService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(eventAspect);
        SampleService proxy = factory.getProxy();

        MDC.put("traceId", "trace-slow-950");
        String longUsername = "atengUser_extra_payload_for_truncation_verification";
        String result = proxy.executeSlowWithMaxArgs(longUsername, "superSecretPwd950");

        assertThat(result).isEqualTo("ok:" + longUsername);
        assertThat(captured).hasSize(1);
        SlowAlertEvent event = captured.get(0);
        assertThat(event.getClassName()).isEqualTo(SampleService.class.getName());
        assertThat(event.getMethodName()).isEqualTo("executeSlowWithMaxArgs");
        assertThat(event.getElapsedMs()).isGreaterThanOrEqualTo(30L);
        assertThat(event.getThresholdMs()).isEqualTo(30L);
        assertThat(event.getTraceId()).isEqualTo("trace-slow-950");
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getArgsSummary())
                .hasSize(" ... [TRUNCATED]".length() + 48)
                .doesNotContain("superSecretPwd950")
                .endsWith(" ... [TRUNCATED]");

        // 未超截断阈值时验证敏感凭据完整掩码为 ******
        captured.clear();
        SlowAlertAspect fullArgsAspect = new SlowAlertAspect(new SlowAlertProperties(), publisher);
        AspectJProxyFactory fullFactory = new AspectJProxyFactory(new SampleService());
        fullFactory.addAspect(fullArgsAspect);
        SampleService fullProxy = fullFactory.getProxy();

        fullProxy.executeSlow("shortUser", "superSecretPwd950");
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getArgsSummary())
                .contains("\"password\":\"******\"")
                .contains("shortUser")
                .doesNotContain("superSecretPwd950");
    }

    @Test
    @DisplayName("includeArgs 为 false 时 SlowAlertEvent 不采集原始入参")
    void shouldPublishEmptyArgsSummaryWhenIncludeArgsIsFalse() throws Exception {
        List<SlowAlertEvent> captured = new ArrayList<>();
        SlowAlertAspect eventAspect = new SlowAlertAspect(new SlowAlertProperties(), event -> {
            if (event instanceof SlowAlertEvent slowAlertEvent) {
                captured.add(slowAlertEvent);
            }
        });
        SampleService target = new SampleService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(eventAspect);
        SampleService proxy = factory.getProxy();

        proxy.executeWithoutArgs();
        assertThat(captured).hasSize(1);
        SlowAlertEvent event = captured.get(0);
        assertThat(event.getMethodName()).isEqualTo("executeWithoutArgs");
        assertThat(event.getArgsSummary()).isEmpty();
    }

    @Test
    @DisplayName("事件监听器抛出异常时隔离降级，不中断或污染业务方法返回值")
    void shouldIsolateListenerExceptionWithoutBreakingTargetMethod() throws Exception {
        ApplicationEventPublisher failingPublisher = event -> {
            throw new IllegalStateException("模拟下游告警监听器异常");
        };
        SlowAlertAspect eventAspect = new SlowAlertAspect(new SlowAlertProperties(), failingPublisher);

        SampleService target = new SampleService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(eventAspect);
        SampleService proxy = factory.getProxy();

        String result = proxy.executeSlow("atengUser", "secretPass123");
        assertThat(result).isEqualTo("ok:atengUser");
    }

    static class SampleService {

        @SlowAlert(value = "测试慢方法", threshold = 30)
        public String executeSlow(String username, String password) throws Exception {
            Thread.sleep(40);
            return "ok:" + username;
        }

        @SlowAlert(value = "测试超长入参截断", threshold = 30, maxArgsLength = 48)
        public String executeSlowWithMaxArgs(String username, String password) throws Exception {
            Thread.sleep(40);
            return "ok:" + username;
        }

        @SlowAlert(value = "测试快方法", threshold = 200)
        public String executeFast() {
            return "fast";
        }

        @SlowAlert(threshold = 30, includeArgs = false)
        public void executeWithoutArgs() throws Exception {
            Thread.sleep(40);
        }
    }

    @SlowAlert(threshold = 30)
    static class ClassLevelService {
        public void doWork() throws Exception {
            Thread.sleep(40);
        }
    }
}

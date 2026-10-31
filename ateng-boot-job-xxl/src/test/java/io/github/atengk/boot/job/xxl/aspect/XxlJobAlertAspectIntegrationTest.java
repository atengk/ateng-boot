package io.github.atengk.boot.job.xxl.aspect;

import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.job.xxl.alert.XxlJobAlertAggregator;
import io.github.atengk.boot.job.xxl.event.XxlJobAlertEvent;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * XXL-Job 上下文切面与三阶告警聚合自愈集成测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class XxlJobAlertAspectIntegrationTest {

    private List<XxlJobAlertEvent> capturedEvents;
    private XxlJobAlertAggregator alertAggregator;
    private MockJobService proxy;

    @BeforeEach
    void setUp() {
        capturedEvents = new ArrayList<>();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof XxlJobAlertEvent alertEvent) {
                capturedEvents.add(alertEvent);
            }
        };

        XxlJobProperties properties = new XxlJobProperties();
        properties.setAlertEnabled(true);
        properties.setAlertSuppressDuration(Duration.ofMinutes(5));
        properties.setAlertSuppressThreshold(2); // 连续 2 次失败触发摘要

        alertAggregator = new XxlJobAlertAggregator(properties, publisher, null);
        XxlJobContextAspect aspect = new XxlJobContextAspect(null, properties, null, alertAggregator);

        ProxyFactory factory = new ProxyFactory(new MockJobServiceImpl());
        factory.addAdvice(new org.springframework.aop.MethodBeforeAdvice() {
            @Override
            public void before(java.lang.reflect.Method method, Object[] args, Object target) {}
        });
        // 织入 Around 切面逻辑
        factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            // 将 invocation 包装为 ProceedingJoinPoint
            org.aspectj.lang.ProceedingJoinPoint pjp = createPjp(invocation);
            try {
                return aspect.aroundJob(pjp);
            } catch (Throwable t) {
                throw t;
            }
        });

        proxy = (MockJobService) factory.getProxy();
    }

    @Test
    @DisplayName("测试任务异常切面捕获并触发首发告警、风暴摘要与自愈全流程")
    void testAspectAlertAndSelfHealingLifecycle() {
        // 1. 首次失败：触发 INITIAL_FAILURE
        assertThrows(RuntimeException.class, () -> proxy.failingJob("param-1"));
        assertEquals(1, capturedEvents.size());
        XxlJobAlertEvent firstEvent = capturedEvents.get(0);
        assertEquals(XxlJobAlertEvent.AlertType.INITIAL_FAILURE, firstEvent.getAlertType());
        assertEquals("failingJob", firstEvent.getJobHandler());
        assertEquals(1, firstEvent.getFailureCount());
        assertNotNull(firstEvent.getTraceId());

        // 2. 第二次失败：达到阈值 2，触发 FAILURE_SUMMARY
        assertThrows(RuntimeException.class, () -> proxy.failingJob("param-2"));
        assertEquals(2, capturedEvents.size());
        XxlJobAlertEvent summaryEvent = capturedEvents.get(1);
        assertEquals(XxlJobAlertEvent.AlertType.FAILURE_SUMMARY, summaryEvent.getAlertType());
        assertEquals(2, summaryEvent.getFailureCount());

        // 3. 成功执行：触发 RECOVERY 自愈恢复
        proxy.failingJobSuccess("param-3");
        assertEquals(3, capturedEvents.size());
        XxlJobAlertEvent recoveryEvent = capturedEvents.get(2);
        assertEquals(XxlJobAlertEvent.AlertType.RECOVERY, recoveryEvent.getAlertType());
        assertEquals(2, recoveryEvent.getFailureCount(), "自愈事件应准确反映曾连续失败的总次数");
    }

    public interface MockJobService {
        void failingJob(String param);
        void failingJobSuccess(String param);
    }

    public static class MockJobServiceImpl implements MockJobService {
        @Override
        @XxlJob("failingJob")
        public void failingJob(String param) {
            throw new RuntimeException("Simulated job failure");
        }

        @Override
        @XxlJob("failingJob")
        public void failingJobSuccess(String param) {
            // success
        }
    }

    private static org.aspectj.lang.ProceedingJoinPoint createPjp(org.aopalliance.intercept.MethodInvocation invocation) {
        return (org.aspectj.lang.ProceedingJoinPoint) java.lang.reflect.Proxy.newProxyInstance(
                XxlJobAlertAspectIntegrationTest.class.getClassLoader(),
                new Class<?>[]{org.aspectj.lang.ProceedingJoinPoint.class},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    if ("getSignature".equals(methodName)) {
                        return createMethodSignature(invocation.getMethod());
                    }
                    if ("getTarget".equals(methodName)) {
                        return invocation.getThis();
                    }
                    if ("getArgs".equals(methodName)) {
                        return invocation.getArguments();
                    }
                    if ("proceed".equals(methodName)) {
                        if (args != null && args.length > 0 && args[0] instanceof Object[] newArgs) {
                            // 暂直接 proceed
                            return invocation.proceed();
                        }
                        return invocation.proceed();
                    }
                    return null;
                }
        );
    }

    private static org.aspectj.lang.reflect.MethodSignature createMethodSignature(java.lang.reflect.Method targetMethod) {
        return (org.aspectj.lang.reflect.MethodSignature) java.lang.reflect.Proxy.newProxyInstance(
                XxlJobAlertAspectIntegrationTest.class.getClassLoader(),
                new Class<?>[]{org.aspectj.lang.reflect.MethodSignature.class},
                (proxy, method, args) -> {
                    if ("getMethod".equals(method.getName())) {
                        return targetMethod;
                    }
                    if ("getName".equals(method.getName())) {
                        return targetMethod.getName();
                    }
                    if ("getDeclaringType".equals(method.getName())) {
                        return targetMethod.getDeclaringClass();
                    }
                    return null;
                }
        );
    }
}

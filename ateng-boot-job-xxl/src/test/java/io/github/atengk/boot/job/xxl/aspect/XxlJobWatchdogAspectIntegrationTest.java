package io.github.atengk.boot.job.xxl.aspect;

import com.xxl.job.core.context.XxlJobContext;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import io.github.atengk.boot.job.xxl.event.JobTimeoutEvent;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import io.github.atengk.boot.job.xxl.timeout.AdminTimeoutRegistry;
import io.github.atengk.boot.job.xxl.watchdog.LocalWatchdog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XXL-Job 看门狗超时中断与受管线程池全链路集成测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootTest(classes = XxlJobWatchdogAspectIntegrationTest.TestConfig.class)
class XxlJobWatchdogAspectIntegrationTest {

    @Autowired
    private WatchdogTestService testService;

    @Autowired
    private TimeoutEventListener eventListener;

    @Autowired
    private XxlJobProperties properties;

    @AfterEach
    void tearDown() {
        eventListener.clear();
        AdminTimeoutRegistry.clear();
        XxlJobContext.setXxlJobContext(null);
        properties.setManagedPoolEnabled(false);
    }

    @Test
    @DisplayName("测试方法级声明 @AtengXxlJob(timeout='200ms')：超时时确定性触发 Thread.interrupt() 并广播 JobTimeoutEvent")
    void testMethodLevelTimeoutInterruption() {
        XxlJobContext context = new XxlJobContext(201L, "", 80001L, System.currentTimeMillis(), "test.log", 0, 1);
        XxlJobContext.setXxlJobContext(context);

        testService.executeBlockedJobWith200msTimeout();

        // 验证超时事件已被监听到
        assertThat(eventListener.getEvents()).hasSize(1);
        JobTimeoutEvent event = eventListener.getEvents().get(0);
        assertThat(event.getJobId()).isEqualTo(201L);
        assertThat(event.getJobName()).isEqualTo("blockedJob");
        assertThat(event.getLogId()).isEqualTo(80001L);
        assertThat(event.getTimeout()).isEqualTo(Duration.ofMillis(200));
        assertThat(testService.wasInterrupted()).isTrue();
    }

    @Test
    @DisplayName("测试正常极速任务：声明 timeout='10s'，50ms 内执行完毕不触发任何中断与超时事件")
    void testQuickJobCompletesNormally() {
        testService.executeQuickJob();

        assertThat(eventListener.getEvents()).isEmpty();
        assertThat(testService.wasInterrupted()).isFalse();
    }

    @Test
    @DisplayName("测试 Admin 动态下发超时：通过 AdminTimeoutRegistry 触发看门狗强中断")
    void testAdminDynamicTimeoutInterruption() {
        long logId = 80002L;
        // 模拟 Admin 下发 1 秒超时
        AdminTimeoutRegistry.register(logId, 1);

        XxlJobContext context = new XxlJobContext(202L, "", logId, System.currentTimeMillis(), "test.log", 0, 1);
        XxlJobContext.setXxlJobContext(context);

        testService.executeLongJobWithoutAnnotationTimeout();

        assertThat(eventListener.getEvents()).hasSize(1);
        JobTimeoutEvent event = eventListener.getEvents().get(0);
        assertThat(event.getJobId()).isEqualTo(202L);
        assertThat(event.getTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(testService.wasInterrupted()).isTrue();
    }

    @Test
    @DisplayName("测试受管线程池执行模式 (managedPoolEnabled=true)：在 Core 异步线程池中受控执行并超时中断")
    void testManagedPoolExecutionAndTimeout() {
        properties.setManagedPoolEnabled(true);

        XxlJobContext context = new XxlJobContext(203L, "", 80003L, System.currentTimeMillis(), "test.log", 0, 1);
        XxlJobContext.setXxlJobContext(context);

        testService.executeBlockedJobWith200msTimeout();

        assertThat(eventListener.getEvents()).hasSize(1);
        JobTimeoutEvent event = eventListener.getEvents().get(0);
        assertThat(event.getThreadName()).contains("managed-job-pool");
        assertThat(testService.wasInterrupted()).isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class TestConfig {

        @Bean
        public XxlJobProperties xxlJobProperties() {
            return new XxlJobProperties();
        }

        @Bean
        public LocalWatchdog localWatchdog(ApplicationEventPublisher publisher) {
            return new LocalWatchdog(publisher);
        }

        @Bean(name = "taskExecutor")
        public ThreadPoolTaskExecutor taskExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(2);
            executor.setMaxPoolSize(4);
            executor.setThreadNamePrefix("managed-job-pool-");
            executor.initialize();
            return executor;
        }

        @Bean
        public XxlJobContextAspect xxlJobContextAspect(LocalWatchdog watchdog,
                                                       XxlJobProperties properties,
                                                       ThreadPoolTaskExecutor taskExecutor) {
            return new XxlJobContextAspect(watchdog, properties, taskExecutor);
        }

        @Bean
        public WatchdogTestService watchdogTestService() {
            return new WatchdogTestService();
        }

        @Bean
        public TimeoutEventListener timeoutEventListener() {
            return new TimeoutEventListener();
        }
    }

    @Component
    static class WatchdogTestService {

        private final AtomicBoolean interruptedFlag = new AtomicBoolean(false);

        @AtengXxlJob(value = "blockedJob", timeout = "200ms")
        public void executeBlockedJobWith200msTimeout() {
            interruptedFlag.set(false);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                interruptedFlag.set(true);
            }
        }

        @AtengXxlJob(value = "quickJob", timeout = "10s")
        public void executeQuickJob() {
            interruptedFlag.set(false);
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                interruptedFlag.set(true);
            }
        }

        @AtengXxlJob(value = "adminJob")
        public void executeLongJobWithoutAnnotationTimeout() {
            interruptedFlag.set(false);
            try {
                Thread.sleep(2500);
            } catch (InterruptedException e) {
                interruptedFlag.set(true);
            }
        }

        public boolean wasInterrupted() {
            return interruptedFlag.get();
        }
    }

    @Component
    static class TimeoutEventListener {

        private final CopyOnWriteArrayList<JobTimeoutEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        public void onJobTimeout(JobTimeoutEvent event) {
            events.add(event);
        }

        public CopyOnWriteArrayList<JobTimeoutEvent> getEvents() {
            return events;
        }

        public void clear() {
            events.clear();
        }
    }

}

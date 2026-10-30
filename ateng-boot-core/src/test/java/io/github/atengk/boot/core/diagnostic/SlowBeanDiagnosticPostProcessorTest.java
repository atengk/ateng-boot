package io.github.atengk.boot.core.diagnostic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 慢 Bean 诊断后处理器 SlowBeanDiagnosticPostProcessor 单测
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("慢 Bean 诊断后处理器单测")
class SlowBeanDiagnosticPostProcessorTest {

    private final SlowBeanDiagnosticPostProcessor processor =
            new SlowBeanDiagnosticPostProcessor(Duration.ofMillis(30));

    @BeforeEach
    @AfterEach
    void cleanUp() {
        SlowBeanDiagnosticPostProcessor.clearSlowBeans();
    }

    @Test
    @DisplayName("当 Bean 初始化耗时超过阈值时，准确收集并记录慢 Bean 概况")
    void shouldCaptureSlowBeanWhenExceedingThreshold() throws Exception {
        String slowBeanName = "slowTestService";
        Object slowBean = new Object();

        processor.postProcessBeforeInitialization(slowBean, slowBeanName);
        Thread.sleep(40);
        processor.postProcessAfterInitialization(slowBean, slowBeanName);

        List<SlowBeanDiagnosticPostProcessor.SlowBeanInfo> slowBeans = SlowBeanDiagnosticPostProcessor.getSlowBeans();
        assertThat(slowBeans).hasSize(1);
        assertThat(slowBeans.get(0).beanName()).isEqualTo(slowBeanName);
        assertThat(slowBeans.get(0).costMillis()).isGreaterThanOrEqualTo(30);
    }

    @Test
    @DisplayName("当 Bean 初始化耗时未超过阈值时，不予收录")
    void shouldNotCaptureFastBean() {
        String fastBeanName = "fastTestService";
        Object fastBean = new Object();

        processor.postProcessBeforeInitialization(fastBean, fastBeanName);
        processor.postProcessAfterInitialization(fastBean, fastBeanName);

        List<SlowBeanDiagnosticPostProcessor.SlowBeanInfo> slowBeans = SlowBeanDiagnosticPostProcessor.getSlowBeans();
        assertThat(slowBeans).isEmpty();
    }

    @Test
    @DisplayName("当阈值配置为 0 或空时，完全禁用慢 Bean 收集")
    void shouldDisableWhenThresholdZero() throws Exception {
        SlowBeanDiagnosticPostProcessor disabledProcessor =
                new SlowBeanDiagnosticPostProcessor(Duration.ZERO);

        Object bean = new Object();
        disabledProcessor.postProcessBeforeInitialization(bean, "testBean");
        Thread.sleep(20);
        disabledProcessor.postProcessAfterInitialization(bean, "testBean");

        assertThat(SlowBeanDiagnosticPostProcessor.getSlowBeans()).isEmpty();
    }
}

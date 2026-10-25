package io.github.atengk.boot.statemachine.interceptor;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.statemachine.properties.StateMachineProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * 状态机日志审计拦截器单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class LoggingStateMachineInterceptorTest {

    static class OrderContext {
        private final String customerName;
        private final String phoneNumber;

        public OrderContext(String customerName, String phoneNumber) {
            this.customerName = customerName;
            this.phoneNumber = phoneNumber;
        }

        @Override
        public String toString() {
            return "OrderContext{" +
                    "customerName='" + DesensitizeUtil.chineseName(customerName) + '\'' +
                    ", phoneNumber='" + DesensitizeUtil.mobilePhone(phoneNumber) + '\'' +
                    '}';
        }
    }

    @Test
    @DisplayName("测试日志打印正常执行且不抛出异常")
    void testLoggingNormalExecution() {
        StateMachineProperties.Logging loggingConfig = new StateMachineProperties.Logging();
        loggingConfig.setEnabled(true);
        loggingConfig.setLogContext(true);

        LoggingStateMachineInterceptor interceptor = new LoggingStateMachineInterceptor(loggingConfig);
        assertThat(interceptor.getLoggingProperties()).isSameAs(loggingConfig);

        OrderContext context = new OrderContext("张三丰", "13812345678");
        assertThat(context.toString()).contains("张*丰", "138****5678");

        assertThatNoException().isThrownBy(() -> {
            interceptor.beforeTransition("WAIT_PAY", "PAY", context);
            interceptor.afterTransition("WAIT_PAY", "PAID", "PAY", context, Duration.ofMillis(15));
            interceptor.onException("WAIT_PAY", "PAY", context, new RuntimeException("Test Exception"));
        });
    }

    @Test
    @DisplayName("测试禁用日志时安全短路")
    void testLoggingDisabled() {
        StateMachineProperties.Logging loggingConfig = new StateMachineProperties.Logging();
        loggingConfig.setEnabled(false);

        LoggingStateMachineInterceptor interceptor = new LoggingStateMachineInterceptor(loggingConfig);

        assertThatNoException().isThrownBy(() -> {
            interceptor.beforeTransition("WAIT_PAY", "PAY", null);
            interceptor.afterTransition("WAIT_PAY", "PAID", "PAY", null, Duration.ofMillis(5));
            interceptor.onException("WAIT_PAY", "PAY", null, new RuntimeException("Error"));
        });
    }
}

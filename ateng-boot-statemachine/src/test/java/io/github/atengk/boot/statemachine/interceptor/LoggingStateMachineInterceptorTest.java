package io.github.atengk.boot.statemachine.interceptor;

import io.github.atengk.boot.core.annotation.FieldSensitive;
import io.github.atengk.boot.core.enums.SensitiveType;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.statemachine.properties.StateMachineProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * 状态机日志审计拦截器单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
@ExtendWith(OutputCaptureExtension.class)
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

    @Test
    @DisplayName("测试基础字符串上下文脱敏格式化")
    void testFormatContextDesensitization() {
        StateMachineProperties.Logging loggingConfig = new StateMachineProperties.Logging();
        LoggingStateMachineInterceptor interceptor = new LoggingStateMachineInterceptor(loggingConfig);

        assertThat(interceptor.formatContext("13812345678")).isEqualTo("138****5678");
        assertThat(interceptor.formatContext("test@example.com")).isEqualTo("t**t@example.com");
        assertThat(interceptor.formatContext("buyerPhone=13812345678, email=buyer@example.com"))
                .isEqualTo("buyerPhone=138****5678, email=b***r@example.com");
        assertThat(interceptor.formatContext("custom-payload")).isEqualTo("custom-payload");
        assertThat(interceptor.formatContext(null)).isEqualTo("null");
    }

    static class SensitivePojoContext {
        @FieldSensitive(SensitiveType.CHINESE_NAME)
        private String customerName;
        @FieldSensitive(SensitiveType.MOBILE_PHONE)
        private String mobile;
        private String contactEmail;
        private String orderSn;

        public String getCustomerName() {
            return customerName;
        }

        public void setCustomerName(String customerName) {
            this.customerName = customerName;
        }

        public String getMobile() {
            return mobile;
        }

        public void setMobile(String mobile) {
            this.mobile = mobile;
        }

        public String getContactEmail() {
            return contactEmail;
        }

        public void setContactEmail(String contactEmail) {
            this.contactEmail = contactEmail;
        }

        public String getOrderSn() {
            return orderSn;
        }

        public void setOrderSn(String orderSn) {
            this.orderSn = orderSn;
        }
    }

    @Test
    @DisplayName("测试 POJO 上下文安全脱敏、machineId 日志输出及 Duration 慢跃迁 WARN 告警")
    void testMachineIdLoggingPojoDesensitizationAndSlowAlert(CapturedOutput output) {
        StateMachineProperties.Logging loggingConfig = new StateMachineProperties.Logging();
        assertThat(loggingConfig.getSlowTransitionThreshold()).isEqualTo(Duration.ofMillis(500));

        loggingConfig.setEnabled(true);
        loggingConfig.setLogContext(true);
        loggingConfig.setSlowTransitionThreshold(Duration.ofMillis(200));

        LoggingStateMachineInterceptor interceptor = new LoggingStateMachineInterceptor(loggingConfig);

        SensitivePojoContext ctx = new SensitivePojoContext();
        ctx.setCustomerName("张三丰");
        ctx.setMobile("13812345678");
        ctx.setContactEmail("buyer@example.com");
        ctx.setOrderSn("SO202610100001");

        interceptor.beforeTransition("ORDER_LIFECYCLE", "WAIT_PAY", "PAY", ctx);
        interceptor.afterTransition("ORDER_LIFECYCLE", "WAIT_PAY", "PAID", "PAY", ctx, Duration.ofMillis(50));
        interceptor.afterTransition("ORDER_LIFECYCLE", "PAID", "WAIT_DELIVER", "RISK_AUDIT", ctx, Duration.ofMillis(250));
        interceptor.onException("ORDER_LIFECYCLE", "WAIT_DELIVER", "SHIP", ctx, new IllegalStateException("快递异常"));

        String logs = output.getOut();
        assertThat(logs)
                .contains("[ORDER_LIFECYCLE]")
                .contains("张*丰")
                .contains("138****5678")
                .contains("b***r@example.com")
                .contains("SO202610100001")
                .doesNotContain("张三丰")
                .doesNotContain("13812345678")
                .doesNotContain("buyer@example.com");

        assertThat(logs).contains("WARN", "慢跃迁", "ORDER_LIFECYCLE", "250ms", "200ms");
    }
}

package io.github.atengk.boot.rabbitmq.retry;

import jakarta.validation.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.retry.RetryContext;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RabbitRetryPolicy 单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("RabbitRetryPolicy 重试策略单元测试")
class RabbitRetryPolicyTest {

    private final RabbitRetryPolicy policy = new RabbitRetryPolicy(3);

    @Test
    @DisplayName("毒丸异常 0 次重试：遇到 ValidationException 时 canRetry 立即返回 false")
    void testPoisonPillCannotRetry() {
        RetryContext context = mock(RetryContext.class);
        when(context.getRetryCount()).thenReturn(0);
        when(context.getLastThrowable()).thenReturn(new ValidationException("格式错误毒丸"));

        assertThat(policy.canRetry(context)).isFalse();
    }

    @Test
    @DisplayName("非法参数毒丸 0 次重试：遇到 IllegalArgumentException 时 canRetry 立即返回 false")
    void testIllegalArgumentCannotRetry() {
        RetryContext context = mock(RetryContext.class);
        when(context.getRetryCount()).thenReturn(0);
        when(context.getLastThrowable()).thenReturn(new IllegalArgumentException("非法入参"));

        assertThat(policy.canRetry(context)).isFalse();
    }

    @Test
    @DisplayName("常规可自愈异常允许重试：遇到 IOException 且未达最大重试次数时允许重试")
    void testTransientExceptionCanRetry() {
        RetryContext context = mock(RetryContext.class);
        when(context.getLastThrowable()).thenReturn(new IOException("网络抖动"));

        when(context.getRetryCount()).thenReturn(0);
        assertThat(policy.canRetry(context)).isTrue();

        when(context.getRetryCount()).thenReturn(2);
        assertThat(policy.canRetry(context)).isTrue();

        when(context.getRetryCount()).thenReturn(3);
        assertThat(policy.canRetry(context)).isFalse();
    }
}

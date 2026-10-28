package io.github.atengk.boot.rabbitmq.retry;

import io.github.atengk.boot.core.helper.FatalExceptionClassifier;
import org.springframework.retry.RetryContext;
import org.springframework.retry.policy.SimpleRetryPolicy;

/**
 * RabbitMQ 消费端受控重试策略
 * 结合 Core 模块 FatalExceptionClassifier 判定：
 * 遇毒丸异常 (反序列化错误、JSR-303 格式校验不通过、非法参数等) 0 次重试快速失败送入 DLQ；
 * 遇常规可自愈业务异常则按配置执行指数退避重试直至达到 maxAttempts 上限
 *
 * @author Ateng
 * @since 2026-10-21
 */
public class RabbitRetryPolicy extends SimpleRetryPolicy {

    public RabbitRetryPolicy(int maxAttempts) {
        super(maxAttempts);
    }

    @Override
    public boolean canRetry(RetryContext context) {
        Throwable lastThrowable = context.getLastThrowable();
        if (lastThrowable != null && FatalExceptionClassifier.isFatal(lastThrowable)) {
            return false;
        }
        return super.canRetry(context);
    }
}

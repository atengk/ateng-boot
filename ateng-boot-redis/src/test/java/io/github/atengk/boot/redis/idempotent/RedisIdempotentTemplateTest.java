package io.github.atengk.boot.redis.idempotent;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.redis.idempotent.enums.IdempotentStatus;
import io.github.atengk.boot.redis.properties.RedisProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisIdempotentTemplate 单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("RedisIdempotentTemplate 单元测试")
class RedisIdempotentTemplateTest {

    private final RedissonClient redissonClient = mock(RedissonClient.class);
    @SuppressWarnings("unchecked")
    private final RBucket<String> bucket = mock(RBucket.class);

    private RedisProperties properties;
    private RedisIdempotentTemplate template;

    @BeforeEach
    void setUp() {
        doReturn(bucket).when(redissonClient).getBucket(any(), eq(StringCodec.INSTANCE));
        properties = new RedisProperties();
        properties.setKeyPrefix("sample:");
        properties.getIdempotent().setPrefix("idemp:");
        properties.getIdempotent().setDefaultExpire(Duration.ofMinutes(5));
        template = new RedisIdempotentTemplate(redissonClient, properties);
        RedisIdempotentUtil.init(template);
    }

    @Test
    @DisplayName("测试正常执行业务闭包：状态由 TRYING 推进至 SUCCESS 并返回结果")
    void testExecuteSuccessAndStateTransition() throws Exception {
        when(bucket.setIfAbsent(eq(IdempotentStatus.TRYING.name()), any())).thenReturn(true);

        String result = template.execute("order:1001", Duration.ofMinutes(2), () -> "order_created");

        assertThat(result).isEqualTo("order_created");
        verify(bucket).setIfAbsent(eq(IdempotentStatus.TRYING.name()), eq(Duration.ofMinutes(2)));
        verify(bucket).set(eq(IdempotentStatus.SUCCESS.name()), eq(Duration.ofMinutes(2)));
        verify(bucket, never()).delete();
    }

    @Test
    @DisplayName("测试重复提交被拦截：抛出 IdempotentException 且不执行业务闭包")
    void testExecuteDuplicateBlocked() {
        when(bucket.setIfAbsent(eq(IdempotentStatus.TRYING.name()), any())).thenReturn(false);

        AtomicBoolean executed = new AtomicBoolean(false);

        assertThatThrownBy(() -> template.execute("order:1001", Duration.ofMinutes(2), () -> {
            executed.set(true);
            return "never_run";
        })).isInstanceOf(IdempotentException.class)
                .hasMessage(CommonResultCode.REPEAT_SUBMIT.getMsg())
                .extracting(ex -> ((IdempotentException) ex).getCode())
                .isEqualTo(CommonResultCode.REPEAT_SUBMIT.getCode());

        assertThat(executed.get()).isFalse();
        verify(bucket, never()).set(eq(IdempotentStatus.SUCCESS.name()), any());
    }

    @Test
    @DisplayName("测试带自定义提示语的重复拦截")
    void testExecuteDuplicateBlockedWithCustomMessage() {
        when(bucket.setIfAbsent(eq(IdempotentStatus.TRYING.name()), any())).thenReturn(false);

        assertThatThrownBy(() -> template.execute("order:1001", Duration.ofMinutes(2), true, "请勿重复结算订单", () -> "val"))
                .isInstanceOf(IdempotentException.class)
                .hasMessage("请勿重复结算订单");
    }

    @Test
    @DisplayName("测试默认 deleteOnFailure=true：业务异常时自动删除 Key 以允许重试并透传原异常")
    void testExecuteExceptionWithDeleteOnFailureTrue() {
        when(bucket.setIfAbsent(eq(IdempotentStatus.TRYING.name()), any())).thenReturn(true);

        assertThatThrownBy(() -> template.execute("order:1001", Duration.ofMinutes(2), () -> {
            throw new IllegalArgumentException("库存不足");
        })).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("库存不足");

        verify(bucket).delete();
        verify(bucket, never()).set(eq(IdempotentStatus.SUCCESS.name()), any());
        verify(bucket, never()).set(eq(IdempotentStatus.FAILED.name()), any());
    }

    @Test
    @DisplayName("测试 deleteOnFailure=false：业务异常时保留 Key 并更新状态为 FAILED")
    void testExecuteExceptionWithDeleteOnFailureFalse() {
        when(bucket.setIfAbsent(eq(IdempotentStatus.TRYING.name()), any())).thenReturn(true);

        assertThatThrownBy(() -> template.execute("order:1001", Duration.ofMinutes(2), false, () -> {
            throw new IllegalStateException("支付通道故障");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("支付通道故障");

        verify(bucket).set(eq(IdempotentStatus.FAILED.name()), eq(Duration.ofMinutes(2)));
        verify(bucket, never()).delete();
        verify(bucket, never()).set(eq(IdempotentStatus.SUCCESS.name()), any());
    }

    @Test
    @DisplayName("测试无返回值 Action 执行与失败自动释放")
    void testExecuteVoidAction() throws Exception {
        when(bucket.setIfAbsent(eq(IdempotentStatus.TRYING.name()), any())).thenReturn(true);

        AtomicBoolean executed = new AtomicBoolean(false);
        template.execute("action:key", Duration.ofSeconds(30), () -> executed.set(true));

        assertThat(executed.get()).isTrue();
        verify(bucket).set(eq(IdempotentStatus.SUCCESS.name()), eq(Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("测试对称运维与状态检测操作")
    void testSymmetricOperations() {
        when(bucket.setIfAbsent(eq(IdempotentStatus.TRYING.name()), any())).thenReturn(true);
        when(bucket.delete()).thenReturn(true);
        when(bucket.get()).thenReturn("SUCCESS");

        assertThat(template.tryAcquire("biz:1", Duration.ofMinutes(1))).isTrue();

        template.setSuccess("biz:1", Duration.ofMinutes(1));
        verify(bucket).set(eq(IdempotentStatus.SUCCESS.name()), eq(Duration.ofMinutes(1)));

        template.setFailed("biz:1", Duration.ofMinutes(1));
        verify(bucket).set(eq(IdempotentStatus.FAILED.name()), eq(Duration.ofMinutes(1)));

        assertThat(template.getStatus("biz:1")).isEqualTo(IdempotentStatus.SUCCESS);
        assertThat(template.isSuccess("biz:1")).isTrue();
        assertThat(template.isTrying("biz:1")).isFalse();
        assertThat(template.isFailed("biz:1")).isFalse();

        assertThat(template.delete("biz:1")).isTrue();
        verify(bucket).delete();
    }

    @Test
    @DisplayName("测试 Key 格式化与前缀处理")
    void testFormatKey() {
        // 全局前缀 sample:，默认前缀 idemp:
        assertThat(template.formatKey("order:1")).isEqualTo("sample:idemp:order:1");

        // 自定义前缀
        assertThat(template.formatKey("custom:", "order:2")).isEqualTo("sample:custom:order:2");

        // 空前缀配置处理
        properties.setKeyPrefix("");
        properties.getIdempotent().setPrefix("");
        assertThat(template.formatKey("testKey")).isEqualTo("testKey");
    }

    @Test
    @DisplayName("测试空白 Key 与空闭包前置卫语句防卫")
    void testFailFastValidations() {
        assertThatThrownBy(() -> template.execute("", () -> "ok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotent key must not be blank");

        assertThatThrownBy(() -> template.execute("key:1", (IdempotentSupplier<String, Exception>) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supplier must not be null");

        assertThatThrownBy(() -> template.execute("key:1", (IdempotentAction<Exception>) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action must not be null");
    }

    @Test
    @DisplayName("测试底层 Redis 异常因果链完整包装为 BusinessException")
    void testUnderlyingRedisExceptionWrapped() {
        RedisConnectionException rootCause = new RedisConnectionException("Redis node unreachable");
        when(bucket.setIfAbsent(any(), any())).thenThrow(rootCause);

        assertThatThrownBy(() -> template.execute("error:key", () -> "ok"))
                .isInstanceOf(BusinessException.class)
                .hasCause(rootCause)
                .hasMessageContaining("Redis 幂等抢占底层异常");
    }
}

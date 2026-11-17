package io.github.atengk.boot.web.captcha.store;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 基于 RedissonClient 的分布式图形验证码存储单元测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedissonCaptchaStore 单元测试")
class RedissonCaptchaStoreTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RBucket<String> bucket;

    private RedissonCaptchaStore store;

    @BeforeEach
    void setUp() {
        lenient().when(redissonClient.<String>getBucket("captcha:k1")).thenReturn(bucket);
        store = new RedissonCaptchaStore(redissonClient);
    }

    @Test
    @DisplayName("save 通过 RBucket 写入验证码答案与 TTL")
    void shouldSaveToRBucketWithTtl() {
        Duration ttl = Duration.ofMinutes(2);
        store.save("captcha:k1", "8", ttl);

        verify(bucket).set("8", ttl);
    }

    @Test
    @DisplayName("getAndDelete 调用 RBucket 原生原子 getAndDelete 实现一次消费")
    void shouldConsumeOnceViaRBucketGetAndDelete() {
        when(bucket.getAndDelete()).thenReturn("8").thenReturn(null);

        assertThat(store.getAndDelete("captcha:k1")).isEqualTo("8");
        assertThat(store.getAndDelete("captcha:k1")).isNull();
    }

    @Test
    @DisplayName("delete 调用 RBucket.delete 主动销毁验证码")
    void shouldDeleteViaRBucket() {
        store.delete("captcha:k1");

        verify(bucket).delete();
    }

    @Test
    @DisplayName("底层 Redisson 异常时转译为 BusinessException 并保留原始 cause")
    void shouldWrapRedissonExceptionWithCause() {
        when(redissonClient.<String>getBucket("captcha:err")).thenThrow(new IllegalStateException("Redisson shutdown"));

        assertThatThrownBy(() -> store.delete("captcha:err"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Redisson captcha delete failed")
                .hasCauseInstanceOf(IllegalStateException.class)
                .extracting("code")
                .isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
    }
}

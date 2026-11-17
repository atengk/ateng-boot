package io.github.atengk.boot.web.captcha.store;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 基于 StringRedisTemplate 的分布式图形验证码存储单元测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisCaptchaStore 单元测试")
class RedisCaptchaStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisCaptchaStore store;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new RedisCaptchaStore(redisTemplate);
    }

    @Test
    @DisplayName("save 正确写入键值与 Duration 过期时间")
    void shouldSaveAnswerWithTtl() {
        Duration ttl = Duration.ofMinutes(2);
        store.save("captcha:k1", "7G4M", ttl);

        verify(valueOperations).set("captcha:k1", "7G4M", ttl);
    }

    @Test
    @DisplayName("getAndDelete 通过原子 Lua 脚本读取并立即销毁")
    void shouldAtomicallyGetAndDeleteViaLuaScript() {
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList("captcha:k1"))))
                .thenReturn("7G4M")
                .thenReturn(null);

        String first = store.getAndDelete("captcha:k1");
        String second = store.getAndDelete("captcha:k1");

        assertThat(first).isEqualTo("7G4M");
        assertThat(second).isNull();
    }

    @Test
    @DisplayName("delete 主动清除指定 Redis 键")
    void shouldDeleteKey() {
        store.delete("captcha:k1");

        verify(redisTemplate).delete("captcha:k1");
    }

    @Test
    @DisplayName("底层 Redis 异常时转译为 BusinessException 并 100% 保留 cause 链")
    void shouldWrapRedisExceptionWithCause() {
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList("captcha:err"))))
                .thenThrow(new RuntimeException("Redis timeout"));

        assertThatThrownBy(() -> store.getAndDelete("captcha:err"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Redis captcha getAndDelete failed")
                .hasCauseInstanceOf(RuntimeException.class)
                .extracting("code")
                .isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
    }
}

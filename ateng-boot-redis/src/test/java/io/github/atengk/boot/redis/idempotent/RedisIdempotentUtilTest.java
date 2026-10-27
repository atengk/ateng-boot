package io.github.atengk.boot.redis.idempotent;

import io.github.atengk.boot.redis.idempotent.enums.IdempotentStatus;
import io.github.atengk.boot.redis.properties.RedisProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisIdempotentUtil 单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("RedisIdempotentUtil 单元测试")
class RedisIdempotentUtilTest {

    private final RedissonClient redissonClient = mock(RedissonClient.class);
    @SuppressWarnings("unchecked")
    private final RBucket<String> bucket = mock(RBucket.class);

    @BeforeEach
    void setUp() {
        doReturn(bucket).when(redissonClient).getBucket(any(), eq(StringCodec.INSTANCE));
        RedisProperties properties = new RedisProperties();
        RedisIdempotentTemplate template = new RedisIdempotentTemplate(redissonClient, properties);
        RedisIdempotentUtil.init(template);
    }

    @Test
    @DisplayName("测试通过静态门面执行业务闭包与运维方法")
    void testStaticFacadeMethods() throws Exception {
        when(bucket.setIfAbsent(eq("TRYING"), any())).thenReturn(true);
        when(bucket.delete()).thenReturn(true);
        when(bucket.get()).thenReturn("SUCCESS");

        String res = RedisIdempotentUtil.execute("util:key", Duration.ofSeconds(10), () -> "static_val");
        assertThat(res).isEqualTo("static_val");

        assertThat(RedisIdempotentUtil.tryAcquire("util:key", Duration.ofSeconds(10))).isTrue();
        RedisIdempotentUtil.setSuccess("util:key", Duration.ofSeconds(10));
        RedisIdempotentUtil.setFailed("util:key", Duration.ofSeconds(10));

        assertThat(RedisIdempotentUtil.getStatus("util:key")).isEqualTo(IdempotentStatus.SUCCESS);
        assertThat(RedisIdempotentUtil.isSuccess("util:key")).isTrue();
        assertThat(RedisIdempotentUtil.isTrying("util:key")).isFalse();
        assertThat(RedisIdempotentUtil.isFailed("util:key")).isFalse();

        assertThat(RedisIdempotentUtil.delete("util:key")).isTrue();
        verify(bucket).delete();
    }
}

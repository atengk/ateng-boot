package io.github.atengk.boot.redis.script;

import io.github.atengk.boot.core.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisScriptExecutor 受管原子 Lua 脚本执行器单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("RedisScriptExecutor 受管原子 Lua 脚本执行器单元测试")
class RedisScriptExecutorTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    private RedisScriptExecutor scriptExecutor;

    @BeforeEach
    void setUp() {
        when(redisTemplate.getValueSerializer()).thenReturn(mock(RedisSerializer.class));
        scriptExecutor = new RedisScriptExecutor(redisTemplate);
    }

    @Test
    @DisplayName("测试执行内联脚本返回 Long 类型")
    void testExecuteInlineLong() {
        String script = "return redis.call('incr', KEYS[1])";
        List<String> keys = List.of("counter:test");
        when(redisTemplate.execute(any(RedisScript.class), eq(keys), any(Object[].class))).thenReturn(10L);

        Long result = scriptExecutor.execute(script, Long.class, keys);

        assertThat(result).isEqualTo(10L);
        assertThat(scriptExecutor.getCacheSize()).isEqualTo(1);
        assertThat(scriptExecutor.containsCachedInlineScript(script, Long.class)).isTrue();
    }

    @Test
    @DisplayName("测试执行内联脚本返回 Integer 类型（自适应转换）")
    void testExecuteInlineInteger() {
        String script = "return 42";
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.emptyList()), any(Object[].class))).thenReturn(42L);

        Integer result = scriptExecutor.execute(script, Integer.class, Collections.emptyList());

        assertThat(result).isEqualTo(42);
    }

    @Test
    @DisplayName("测试执行内联脚本返回 Boolean 类型")
    void testExecuteInlineBoolean() {
        String script = "return redis.call('exists', KEYS[1]) == 1";
        List<String> keys = List.of("user:exist");
        when(redisTemplate.execute(any(RedisScript.class), eq(keys), any(Object[].class))).thenReturn(true);

        Boolean exists = scriptExecutor.execute(script, Boolean.class, keys);

        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("测试执行内联脚本返回 String 类型")
    void testExecuteInlineString() {
        String script = "return 'hello ' .. ARGV[1]";
        List<String> keys = Collections.emptyList();
        when(redisTemplate.execute(any(RedisScript.class), any(), any(StringRedisSerializer.class), eq(keys), any(Object[].class)))
                .thenReturn("hello world");

        String result = scriptExecutor.execute(script, String.class, keys, "world");

        assertThat(result).isEqualTo("hello world");
    }

    @Test
    @DisplayName("测试执行内联脚本返回 List 类型")
    void testExecuteInlineList() {
        String script = "return {KEYS[1], ARGV[1]}";
        List<String> keys = List.of("k1");
        when(redisTemplate.execute(any(RedisScript.class), eq(keys), any(Object[].class))).thenReturn(List.of("k1", "v1"));

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) scriptExecutor.execute(script, List.class, keys, "v1");

        assertThat(result).containsExactly("k1", "v1");
    }

    @Test
    @DisplayName("测试执行内联脚本反序列化为自定义 POJO")
    void testExecuteInlinePojo() {
        String script = "return redis.call('get', KEYS[1])";
        List<String> keys = List.of("user:1001");
        TestUserDto expectedUser = new TestUserDto(1001L, "ateng", 25);

        when(redisTemplate.execute(any(RedisScript.class), any(), any(Jackson2JsonRedisSerializer.class), eq(keys), any(Object[].class)))
                .thenReturn(expectedUser);

        TestUserDto user = scriptExecutor.execute(script, TestUserDto.class, keys);

        assertThat(user).isNotNull();
        assertThat(user.getId()).isEqualTo(1001L);
        assertThat(user.getUsername()).isEqualTo("ateng");
        assertThat(user.getAge()).isEqualTo(25);
    }

    @Test
    @DisplayName("测试执行 Classpath 下的 Lua 脚本并验证预编译缓存")
    void testExecuteClasspathScript() {
        String classpath = "classpath:scripts/test-script.lua";
        List<String> keys = List.of("seq:key");
        when(redisTemplate.execute(any(RedisScript.class), eq(keys), any(Object[].class))).thenReturn(100L);

        Long first = scriptExecutor.executeClasspath(classpath, Long.class, keys, 5);
        Long second = scriptExecutor.executeClasspath("scripts/test-script.lua", Long.class, keys, 5);

        assertThat(first).isEqualTo(100L);
        assertThat(second).isEqualTo(100L);
        assertThat(scriptExecutor.getCacheSize()).isEqualTo(1);
        assertThat(scriptExecutor.containsCachedClasspathScript(classpath, Long.class)).isTrue();
    }

    @Test
    @DisplayName("测试 Classpath 脚本不存在时抛出 IllegalArgumentException")
    void testClasspathScriptNotFound() {
        assertThatThrownBy(() -> scriptExecutor.executeClasspath("scripts/not-exist.lua", Long.class, Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Lua 脚本文件在 classpath 下不存在");
    }

    @Test
    @DisplayName("测试底层驱动异常受控转译为保留原始 cause 的 BusinessException")
    void testExceptionTranslation() {
        String script = "return redis.call('get', KEYS[1])";
        List<String> keys = List.of("k");
        QueryTimeoutException timeoutException = new QueryTimeoutException("Redis command timed out");
        when(redisTemplate.execute(any(RedisScript.class), eq(keys), any(Object[].class))).thenThrow(timeoutException);

        assertThatThrownBy(() -> scriptExecutor.execute(script, Long.class, keys))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("执行 Redis Lua 脚本异常")
                .hasCause(timeoutException);
    }

    @Test
    @DisplayName("测试参数防御性校验")
    void testParameterValidation() {
        assertThatThrownBy(() -> scriptExecutor.execute("", Long.class, Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> scriptExecutor.execute("return 1", null, Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> scriptExecutor.executeClasspath("", Long.class, Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> scriptExecutor.executeClasspath("scripts/test-script.lua", null, Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("测试显式传入参数序列化器且返回值序列化器为 null 时正确透传 argsSerializer")
    void testExecuteWithCustomArgsSerializerAndNullResultSerializer() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>("return 1", Long.class);
        RedisSerializer<String> customArgsSerializer = StringRedisSerializer.UTF_8;
        when(redisTemplate.execute(eq(script), eq(customArgsSerializer), any(), eq(Collections.emptyList()), any(Object[].class)))
                .thenReturn(1L);

        Long result = scriptExecutor.execute(script, customArgsSerializer, null, Collections.emptyList(), "arg1");

        assertThat(result).isEqualTo(1L);
        verify(redisTemplate).execute(eq(script), eq(customArgsSerializer), any(), eq(Collections.emptyList()), any(Object[].class));
    }

    @Test
    @DisplayName("测试清空缓存操作")
    void testClearCache() {
        scriptExecutor.execute("return 1", Long.class, Collections.emptyList());
        assertThat(scriptExecutor.getCacheSize()).isEqualTo(1);

        scriptExecutor.clearCache();
        assertThat(scriptExecutor.getCacheSize()).isZero();
    }

    public static class TestUserDto {
        private Long id;
        private String username;
        private Integer age;

        public TestUserDto() {}
        public TestUserDto(Long id, String username, Integer age) {
            this.id = id;
            this.username = username;
            this.age = age;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public Integer getAge() { return age; }
        public void setAge(Integer age) { this.age = age; }
    }
}

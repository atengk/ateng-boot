package io.github.atengk.boot.redis.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.Assert;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 受管原子 Lua 脚本执行器
 * 统一提供内联与 Classpath 脚本加载、SHA1 预编译缓存与强类型反序列化执行能力
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class RedisScriptExecutor {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, RedisScript<?>> scriptCache = new ConcurrentHashMap<>();

    public RedisScriptExecutor(RedisTemplate<String, Object> redisTemplate) {
        this(redisTemplate, RedisSerializerFactory.createSafeObjectMapper());
    }

    public RedisScriptExecutor(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        Assert.notNull(redisTemplate, "redisTemplate must not be null");
        Assert.notNull(objectMapper, "objectMapper must not be null");
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行内联 Lua 脚本并自动将结果反序列化为指定类型
     *
     * @param scriptContent Lua 脚本文本
     * @param resultType 预期返回值类型
     * @param keys 涉及的 Redis 键列表
     * @param args 传递给脚本的动态参数
     * @param <T> 返回值强类型
     * @return 脚本执行结果
     */
    @SuppressWarnings("unchecked")
    public <T> T execute(String scriptContent, Class<T> resultType, List<String> keys, Object... args) {
        Assert.hasText(scriptContent, "scriptContent must not be blank");
        Assert.notNull(resultType, "resultType must not be null");

        // Redis Lua 数字原生返回 Long，若上层需要 Integer 则自动适配转换
        if (Integer.class.equals(resultType)) {
            Long longVal = execute(scriptContent, Long.class, keys, args);
            return (T) (longVal != null ? Integer.valueOf(longVal.intValue()) : null);
        }

        String cacheKey = "inline:" + resultType.getName() + ":" + sha1(scriptContent);
        RedisScript<T> script = getOrCreateScript(cacheKey, scriptContent, resultType);
        RedisSerializer<T> resultSerializer = resolveResultSerializer(resultType);
        return execute(script, redisTemplate.getValueSerializer(), resultSerializer, keys, args);
    }

    /**
     * 执行 Classpath 路径下的 Lua 脚本并自动将结果反序列化为指定类型
     *
     * @param classpathLocation Classpath 路径（例如 "scripts/demo.lua" 或 "classpath:scripts/demo.lua"）
     * @param resultType 预期返回值类型
     * @param keys 涉及的 Redis 键列表
     * @param args 传递给脚本的动态参数
     * @param <T> 返回值强类型
     * @return 脚本执行结果
     */
    @SuppressWarnings("unchecked")
    public <T> T executeClasspath(String classpathLocation, Class<T> resultType, List<String> keys, Object... args) {
        Assert.hasText(classpathLocation, "classpathLocation must not be blank");
        Assert.notNull(resultType, "resultType must not be null");

        if (Integer.class.equals(resultType)) {
            Long longVal = executeClasspath(classpathLocation, Long.class, keys, args);
            return (T) (longVal != null ? Integer.valueOf(longVal.intValue()) : null);
        }

        String cleanLocation = cleanClasspathLocation(classpathLocation);
        String cacheKey = "classpath:" + resultType.getName() + ":" + cleanLocation;

        RedisScript<T> script = (RedisScript<T>) scriptCache.computeIfAbsent(cacheKey, k -> {
            String scriptContent = resolveScriptContentFromClasspath(cleanLocation);
            DefaultRedisScript<T> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptText(scriptContent);
            if (!Void.class.equals(resultType)) {
                redisScript.setResultType(resultType);
            }
            return redisScript;
        });

        RedisSerializer<T> resultSerializer = resolveResultSerializer(resultType);
        return execute(script, redisTemplate.getValueSerializer(), resultSerializer, keys, args);
    }

    /**
     * 执行指定 RedisScript 实例
     *
     * @param script 预构建的 RedisScript 实例
     * @param keys 涉及的 Redis 键列表
     * @param args 传递给脚本的动态参数
     * @param <T> 返回值强类型
     * @return 脚本执行结果
     */
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
        Assert.notNull(script, "script must not be null");
        RedisSerializer<T> resultSerializer = resolveResultSerializer(script.getResultType());
        return execute(script, redisTemplate.getValueSerializer(), resultSerializer, keys, args);
    }

    /**
     * 底层执行方法，支持显式传入参数与返回值序列化器并完成异常体系受控转译
     *
     * @param script 预构建的 RedisScript 实例
     * @param argsSerializer 参数序列化器
     * @param resultSerializer 返回值序列化器
     * @param keys 涉及的 Redis 键列表
     * @param args 传递给脚本的动态参数
     * @param <T> 返回值强类型
     * @return 脚本执行结果
     */
    public <T> T execute(RedisScript<T> script, RedisSerializer<?> argsSerializer, RedisSerializer<T> resultSerializer,
                         List<String> keys, Object... args) {
        Assert.notNull(script, "script must not be null");
        List<String> safeKeys = keys != null ? keys : Collections.emptyList();
        Object[] safeArgs = args != null ? args : new Object[0];
        RedisSerializer<?> safeArgsSerializer = argsSerializer != null ? argsSerializer : redisTemplate.getValueSerializer();

        try {
            if (resultSerializer != null) {
                return redisTemplate.execute(script, safeArgsSerializer, resultSerializer, safeKeys, safeArgs);
            } else {
                return redisTemplate.execute(script, safeKeys, safeArgs);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "执行 Redis Lua 脚本异常: " + e.getMessage(), e);
        }
    }

    /**
     * 获取当前已缓存的预编译脚本数量
     *
     * @return 脚本缓存大小
     */
    public int getCacheSize() {
        return scriptCache.size();
    }

    /**
     * 清空已缓存的所有预编译脚本
     */
    public void clearCache() {
        scriptCache.clear();
    }

    /**
     * 判断指定内联脚本是否已预编译并缓存
     *
     * @param scriptContent 脚本文本
     * @param resultType 预期结果类型
     * @return 是否已缓存
     */
    public boolean containsCachedInlineScript(String scriptContent, Class<?> resultType) {
        if (!StringUtils.hasText(scriptContent) || resultType == null) {
            return false;
        }
        String cacheKey = "inline:" + resultType.getName() + ":" + sha1(scriptContent);
        return scriptCache.containsKey(cacheKey);
    }

    /**
     * 判断指定 Classpath 脚本是否已预编译并缓存
     *
     * @param classpathLocation 类路径
     * @param resultType 预期结果类型
     * @return 是否已缓存
     */
    public boolean containsCachedClasspathScript(String classpathLocation, Class<?> resultType) {
        if (!StringUtils.hasText(classpathLocation) || resultType == null) {
            return false;
        }
        String cleanLocation = cleanClasspathLocation(classpathLocation);
        String cacheKey = "classpath:" + resultType.getName() + ":" + cleanLocation;
        return scriptCache.containsKey(cacheKey);
    }

    @SuppressWarnings("unchecked")
    private <T> RedisScript<T> getOrCreateScript(String cacheKey, String scriptContent, Class<T> resultType) {
        return (RedisScript<T>) scriptCache.computeIfAbsent(cacheKey, k -> {
            DefaultRedisScript<T> script = new DefaultRedisScript<>();
            script.setScriptText(scriptContent);
            if (!Void.class.equals(resultType)) {
                script.setResultType(resultType);
            }
            return script;
        });
    }

    @SuppressWarnings("unchecked")
    private <T> RedisSerializer<T> resolveResultSerializer(Class<T> resultType) {
        if (resultType == null || Void.class.equals(resultType)) {
            return null;
        }
        if (Long.class.equals(resultType) || Boolean.class.equals(resultType) || List.class.isAssignableFrom(resultType)) {
            return null;
        }
        if (String.class.equals(resultType)) {
            return (RedisSerializer<T>) StringRedisSerializer.UTF_8;
        }
        return new Jackson2JsonRedisSerializer<>(objectMapper, resultType);
    }

    private String cleanClasspathLocation(String location) {
        Assert.hasText(location, "classpathLocation must not be blank");
        String path = location.trim();
        if (path.startsWith("classpath:")) {
            path = path.substring("classpath:".length());
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    private String resolveScriptContentFromClasspath(String cleanLocation) {
        ClassPathResource resource = new ClassPathResource(cleanLocation);
        if (!resource.exists()) {
            throw new IllegalArgumentException("Lua 脚本文件在 classpath 下不存在: " + cleanLocation);
        }
        try (InputStream is = resource.getInputStream()) {
            return StreamUtils.copyToString(is, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "读取 classpath 下 Lua 脚本失败: " + cleanLocation, e);
        }
    }

    private static String sha1(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 algorithm not available", e);
        }
    }
}

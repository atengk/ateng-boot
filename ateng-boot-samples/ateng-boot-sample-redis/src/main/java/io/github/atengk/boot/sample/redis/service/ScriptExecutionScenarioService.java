package io.github.atengk.boot.sample.redis.service;

import io.github.atengk.boot.redis.atomic.RedisAtomicUtil;
import io.github.atengk.boot.redis.script.RedisScriptExecutor;
import io.github.atengk.boot.redis.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 受管 Lua 脚本执行与对称原子原语场景服务
 * 演示 RedisScriptExecutor 脚本加载执行、SHA1 预编译缓存与 RedisAtomicUtil CAS / 发号原语
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptExecutionScenarioService {

    private final RedisScriptExecutor redisScriptExecutor;
    private final RedisAtomicUtil redisAtomicUtil;

    /**
     * 执行内联 Lua 脚本
     *
     * @param script     Lua 源码
     * @param resultType 返回强类型
     * @param keys       键列表
     * @param args       实参列表
     * @param <T>        泛型
     * @return 执行结果
     */
    public <T> T executeInlineScript(String script, Class<T> resultType, List<String> keys, Object... args) {
        log.info("[ScriptExecution] 执行受管内联 Lua 脚本: keys={}", keys);
        return redisScriptExecutor.execute(script, resultType, keys, args);
    }

    /**
     * 执行 Classpath 下的 Lua 脚本文件
     *
     * @param path       脚本类路径 (如 "scripts/sample.lua")
     * @param resultType 返回强类型
     * @param keys       键列表
     * @param args       实参列表
     * @param <T>        泛型
     * @return 执行结果
     */
    public <T> T executeClasspathScript(String path, Class<T> resultType, List<String> keys, Object... args) {
        log.info("[ScriptExecution] 执行受管 Classpath Lua 脚本: path={}, keys={}", path, keys);
        return redisScriptExecutor.executeClasspath(path, resultType, keys, args);
    }

    /**
     * 基于 RedisAtomicUtil 执行 CAS 原子比较替换
     *
     * @param key    原子键
     * @param expect 期望旧值
     * @param update 目标新值
     * @return 是否替换成功
     */
    public boolean compareAndSet(String key, Long expect, Long update) {
        boolean success = redisAtomicUtil.compareAndSet(key, expect, update);
        log.info("[ScriptExecution] CAS 执行结果: key={}, expect={}, update={}, success={}", key, expect, update, success);
        return success;
    }

    /**
     * 基于 RedisAtomicUtil 执行原子获取并设置
     *
     * @param key    原子键
     * @param update 目标新值
     * @return 旧值（若不存在返回 null）
     */
    public Long getAndSet(String key, Long update) {
        Long prev = redisAtomicUtil.getAndSet(key, update);
        log.info("[ScriptExecution] GetAndSet 执行结果: key={}, prev={}, update={}", key, prev, update);
        return prev;
    }

    /**
     * 生成按日自重置带日期前缀的原子流水号 (如 ORD20261027000001)
     *
     * @param prefix 业务前缀
     * @param length 递增序列位宽
     * @return 完整流水号
     */
    public String generateDailyOrderNo(String prefix, int length) {
        String seq = redisAtomicUtil.generateDailySeq(prefix, length);
        log.info("[ScriptExecution] 生成按日原子流水号成功: prefix={}, length={}, seq={}", prefix, length, seq);
        return seq;
    }

    /**
     * 基于 ZSet 毫秒滑动窗口高精度频控校验
     *
     * @param key         限流键
     * @param maxRequests 窗口内最大许可次数
     * @param window      窗口时长
     * @return 是否放行
     */
    public boolean checkSlidingWindow(String key, int maxRequests, Duration window) {
        boolean allowed = redisAtomicUtil.isAllowedBySlidingWindow(key, maxRequests, window);
        log.info("[ScriptExecution] 滑动窗口频控校验: key={}, maxRequests={}, window={}, allowed={}",
                key, maxRequests, window, allowed);
        return allowed;
    }

    /**
     * 基于 RedisUtil 安全游标扫描匹配的键集合
     *
     * @param pattern 模式串
     * @param count   批次大小
     * @return 匹配键集合
     */
    public Set<String> scanKeys(String pattern, long count) {
        Set<String> keys = RedisUtil.scanKeys(pattern, count);
        log.info("[ScriptExecution] 游标扫描完成: pattern={}, count={}, matchedSize={}", pattern, count, keys.size());
        return keys;
    }
}

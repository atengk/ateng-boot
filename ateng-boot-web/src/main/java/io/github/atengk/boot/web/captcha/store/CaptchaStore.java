package io.github.atengk.boot.web.captcha.store;

import java.time.Duration;

/**
 * 图形验证码答案状态存储 SPI 接口
 * 提供带有效期的写入、严格单次消费的原子取删以及主动清理对称契约
 *
 * @author Ateng
 * @since 2026-11-17
 */
public interface CaptchaStore {

    /**
     * 保存验证码标识对应的标准答案并设置过期时长
     *
     * @param key    完整存储键（含前缀）
     * @param answer 验证码标准答案
     * @param ttl    有效存活时长（必须大于 0）
     */
    void save(String key, String answer, Duration ttl);

    /**
     * 原子读取并立即删除指定键对应的标准答案，确保严格一次一密防重放与防爆破
     *
     * @param key 完整存储键（含前缀）
     * @return 未过期时返回标准答案，不存在或已过期时返回 {@code null}
     */
    String getAndDelete(String key);

    /**
     * 主动删除指定验证码键（幂等操作，键不存在时静默忽略）
     *
     * @param key 完整存储键（含前缀）
     */
    void delete(String key);
}

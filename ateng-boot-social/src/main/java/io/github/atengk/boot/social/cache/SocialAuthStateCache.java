package io.github.atengk.boot.social.cache;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.social.model.StateContext;
import org.dromara.anduin.cache.AuthStateCache;

import java.time.Duration;

/**
 * 社交登录与 OAuth2 防伪 State 状态缓存高级契约
 * <p>
 * 继承 Dromara Anduin 的 {@link AuthStateCache} 基础协议接口，并扩展服务端防篡改 {@link StateContext} 上下文存取能力。
 * 支持单机内存（InMemory）与分布式（Redis）双模自适应驱动，在授权回调阶段提供原子消费与一次性销毁（阅后即焚防重放）。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
public interface SocialAuthStateCache extends AuthStateCache {

    /**
     * 默认防伪 State 状态有效时长（5 分钟）
     */
    Duration DEFAULT_STATE_TIMEOUT = Duration.ofMinutes(5);

    /**
     * 生成高熵 32 位防伪 State 并将 StateContext 绑定存入缓存（使用默认 5 分钟超时）
     *
     * @param context 业务上下文（租户、深链跳转与扩展参数）
     * @return 生成的高熵 32 位 State 随机字符串
     */
    default String generateState(StateContext context) {
        return generateState(context, DEFAULT_STATE_TIMEOUT);
    }

    /**
     * 生成高熵 32 位防伪 State 并将 StateContext 绑定存入缓存
     *
     * @param context 业务上下文（租户、深链跳转与扩展参数）
     * @param timeout 有效时长
     * @return 生成的高熵 32 位 State 随机字符串
     */
    default String generateState(StateContext context, Duration timeout) {
        String state = IdUtil.fastSimpleUUID();
        set(state, context, timeout);
        return state;
    }

    /**
     * 存入防伪 State 及其绑定的结构化上下文
     *
     * @param state   防伪 State 键
     * @param context 结构化业务上下文
     * @param timeout 有效时长
     */
    void set(String state, StateContext context, Duration timeout);

    /**
     * 根据 State 获取绑定的业务上下文（不消费/不删除）
     *
     * @param state 防伪 State 键
     * @return 业务上下文；若不存在或已过期则返回 null
     */
    StateContext getContext(String state);

    /**
     * 原子消费并删除 State（阅后即焚防重放）
     * <p>
     * 一旦读取成功，底层缓存立即物理销毁该 State，若后续再次尝试消费同一 State 则返回 null。
     * 彻底杜绝拦截 OAuth2 回调链接后的重放攻击与 CSRF 伪造。
     * </p>
     *
     * @param state 防伪 State 键
     * @return 业务上下文；若已被消费、不存在或已过期则返回 null
     */
    StateContext removeContext(String state);

    /**
     * 检查 State 是否存在且有效（不删除）
     *
     * @param state 防伪 State 键
     * @return true 存在且有效；false 不存在或已过期
     */
    boolean contains(String state);

    // ==================== 适配 Anduin 原生 AuthStateCache 契约 ====================

    @Override
    default void cache(String key, String value) {
        StateContext existing = getContext(key);
        if (existing != null) {
            // 若已预存结构化上下文，保留原有租户与扩展数据，仅在未设置跳转地址且传入有效深链时补充并持久化写回
            if (existing.getRedirectUrl() == null && isCustomRedirectUrl(key, value)) {
                existing.setRedirectUrl(value);
                set(key, existing, DEFAULT_STATE_TIMEOUT);
            }
            return;
        }
        StateContext fallbackContext = StateContext.builder()
                .redirectUrl(isCustomRedirectUrl(key, value) ? value : null)
                .build();
        set(key, fallbackContext, DEFAULT_STATE_TIMEOUT);
    }

    /**
     * 判断传入的 value 是否为自定义回调/跳转 URL（过滤 Anduin 默认以 key 作为 value 的占位行为）
     */
    static boolean isCustomRedirectUrl(String key, String value) {
        return value != null && !value.isBlank() && !value.equals(key);
    }

    @Override
    default String get(String key) {
        StateContext ctx = getContext(key);
        if (ctx == null) {
            return null;
        }
        return ctx.getRedirectUrl() != null ? ctx.getRedirectUrl() : key;
    }

    @Override
    default boolean containsKey(String key) {
        return contains(key);
    }

    @Override
    default void expire(String key) {
        removeContext(key);
    }
}

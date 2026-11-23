package io.github.atengk.boot.social.support;

/**
 * 社交登录 Redis Lua 脚本常量集
 *
 * @author Ateng
 * @since 2026-11-03
 */
public final class RedisLuaScripts {

    private RedisLuaScripts() {
    }

    /**
     * 原子获取并删除的 Lua 脚本（兼容全版本 Redis，保障 GETDEL 绝对原子语义）
     */
    public static final String LUA_GET_AND_DELETE = """
            local val = redis.call('GET', KEYS[1])
            if val then
                redis.call('DEL', KEYS[1])
            end
            return val
            """;
}

package io.github.atengk.boot.datasource.enums;

/**
 * 写后读主（Read-After-Write）防主从延迟一致性生效作用域枚举
 *
 * @author Ateng
 * @since 2026-10-24
 */
public enum ReadAfterWriteScope {

    /**
     * 单请求作用域（默认）：在同一 Web 请求生命周期内，一旦发生写操作，后续查询全部强制锁定主库，请求结束自动清理
     */
    REQUEST,

    /**
     * 时间窗口作用域：发生写操作后，在指定 TTL 时间窗口内（如 3 秒），后续请求全部强制路由至主库
     */
    WINDOW
}

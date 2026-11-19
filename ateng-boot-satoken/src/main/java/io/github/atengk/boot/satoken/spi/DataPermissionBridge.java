package io.github.atengk.boot.satoken.spi;

/**
 * 数据权限超管上下文桥接 SPI
 * 用于在存在持久层数据权限模块（如 {@code ateng-boot-mybatis-plus}）时，
 * 由安全拦截器弱依赖同步当前请求的超级管理员标记并在请求结束时确定性清理。
 *
 * @author Ateng
 * @since 2026-11-19
 */
public interface DataPermissionBridge {

    /**
     * 同步当前请求线程的超级管理员标识
     *
     * @param superAdmin true 表示超级管理员；false 表示普通用户或重置状态
     */
    void setSuperAdmin(boolean superAdmin);

    /**
     * 清理当前请求线程的数据权限上下文状态
     */
    void clear();
}

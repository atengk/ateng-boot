package io.github.atengk.boot.satoken.spi;

/**
 * 数据所有权反查业务扩展 SPI 接口
 * 供业务按需实现，用于根据业务资源标识（如 orderId, fileId）查询或推导数据归属者的用户唯一标识
 *
 * @param <T> 资源标识类型（如 Long, String）
 * @author Ateng
 * @since 2026-10-20
 */
@FunctionalInterface
public interface DataOwnerProvider<T> {

    /**
     * 根据业务资源标识反查数据所有者的用户唯一标识
     *
     * @param resourceId 业务资源标识
     * @return 数据归属者的用户唯一标识（userId），若资源不存在或非合法归属返回 null
     */
    String getOwnerId(T resourceId);
}

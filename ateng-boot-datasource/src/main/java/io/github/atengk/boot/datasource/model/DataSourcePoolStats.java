package io.github.atengk.boot.datasource.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 物理数据源连接池运行指标状态快照 DTO
 * <p>
 * 提供零外部依赖的强类型连接池实时度量视图，封装当前活跃连接、空闲连接、总连接以及排队等待线程等核心运行时数据。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourcePoolStats implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 数据源唯一标识 Key
     */
    private String dsKey;

    /**
     * 底层连接池名称
     */
    private String poolName;

    /**
     * 活跃连接数（当前正被业务线程借用中的物理连接总数）
     */
    private int activeConnections;

    /**
     * 空闲连接数（池中就绪可立即借用的物理连接数）
     */
    private int idleConnections;

    /**
     * 总连接数（当前连接池内持有的所有物理连接总数，包括活跃与空闲）
     */
    private int totalConnections;

    /**
     * 排队等待获取连接的线程数
     */
    private int threadsAwaitingConnection;

    /**
     * 连接池允许的最大容量 (MaximumPoolSize)
     */
    private int maximumPoolSize;

    /**
     * 连接池维持的最小空闲连接数 (MinimumIdle)
     */
    private int minimumIdle;
}

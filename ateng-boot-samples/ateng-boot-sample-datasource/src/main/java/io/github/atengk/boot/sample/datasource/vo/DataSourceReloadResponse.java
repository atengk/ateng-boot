package io.github.atengk.boot.sample.datasource.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 动态数据源无损热重载展示视图对象
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceReloadResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 已完成热重载的数据源标识
     */
    private String dsKey;

    /**
     * 是否执行了新连接池并发预热
     */
    private boolean preWarmed;

    /**
     * 实际完成预热并验证的物理连接数
     */
    private int warmedConnections;

    /**
     * 旧连接池是否启用超时优雅排空
     */
    private boolean gracefulDrained;

    /**
     * 热重载完成后数据源是否处于活跃路由状态
     */
    private boolean active;

    /**
     * 执行结果说明
     */
    private String message;
}

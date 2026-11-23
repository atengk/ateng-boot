package io.github.atengk.boot.sample.ftp.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * FTP 连接池运行时指标 VO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FtpPoolStatsVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 服务节点名称
     */
    private String serverName;

    /**
     * 当前活跃/借出连接数
     */
    private Integer activeConnections;

    /**
     * 当前空闲连接数
     */
    private Integer idleConnections;

    /**
     * 当前阻塞等待借出连接的线程数
     */
    private Integer waitingBorrowers;

    /**
     * 最大允许连接数（-1 表示无界或未限定）
     */
    private Integer maxConnections;

    /**
     * 最大空闲连接数
     */
    private Integer maxIdle;

    /**
     * 最小空闲连接数
     */
    private Integer minIdle;
}

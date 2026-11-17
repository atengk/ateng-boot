package io.github.atengk.boot.ftp.model;

import lombok.Builder;
import lombok.Value;

import java.io.Serial;
import java.io.Serializable;

/**
 * FTP 受管连接池运行时指标不可变快照
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Value
@Builder
public class FtpPoolStats implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 服务节点名称（如 default、backup）
     */
    String serverName;

    /**
     * 当前借出活跃连接数
     */
    int activeConnections;

    /**
     * 当前池中空闲连接数
     */
    int idleConnections;

    /**
     * 当前阻塞等待借出连接的线程数
     */
    int waitingBorrowers;

    /**
     * 连接池最大总容量上限
     */
    int maxTotal;

    /**
     * 连接池最大空闲容量上限
     */
    int maxIdle;

    /**
     * 连接池最小保底空闲连接数
     */
    int minIdle;
}

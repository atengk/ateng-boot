package io.github.atengk.boot.ftp.enums;

/**
 * FTP 数据传输网络模式枚举
 *
 * @author Ateng
 * @since 2026-10-22
 */
public enum FtpMode {

    /**
     * 本地被动模式 (PASV)：由客户端向服务端开辟的数据端口发起连接（云原生/NAT/容器防火墙默认推荐）
     */
    PASSIVE,

    /**
     * 本地主动模式 (PORT)：由服务端主动回连客户端开辟的临时数据端口
     */
    ACTIVE
}

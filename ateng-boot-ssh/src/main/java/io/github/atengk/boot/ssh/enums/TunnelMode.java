package io.github.atengk.boot.ssh.enums;

/**
 * SSH 端口转发隧道方向模式枚举
 *
 * @author Ateng
 * @since 2026-11-17
 */
public enum TunnelMode {

    /**
     * 本地端口转发（ssh -L）：将本地监听端口流量正向转发至远端目标地址
     */
    LOCAL,

    /**
     * 远程端口转发（ssh -R）：在远端 SSH 服务端监听端口并将流量反向转发至本地目标地址
     */
    REMOTE
}

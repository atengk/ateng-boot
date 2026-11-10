package io.github.atengk.boot.sample.ssh.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 本地端口转发受管句柄强类型状态 VO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshTunnelInfoVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 本地实际绑定的监听端口
     */
    private Integer localPort;

    /**
     * 本地绑定地址
     */
    private String localHost;

    /**
     * 目标远端主机地址
     */
    private String remoteHost;

    /**
     * 目标远端服务端口
     */
    private Integer remotePort;

    /**
     * 隧道长连接是否存活
     */
    private Boolean alive;
}

package io.github.atengk.boot.sample.ssh.vo;

import io.github.atengk.boot.ssh.enums.TunnelMode;
import io.github.atengk.boot.ssh.tunnel.SshTunnelHandle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 端口转发受管句柄强类型状态 VO
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
     * 隧道方向模式（LOCAL / REMOTE）
     */
    private TunnelMode tunnelMode;

    /**
     * 本地端口（LOCAL 模式为监听端口，REMOTE 模式为本地被代理目标端口）
     */
    private Integer localPort;

    /**
     * 本地绑定或目标地址
     */
    private String localHost;

    /**
     * 远端主机地址（LOCAL 模式为目标主机，REMOTE 模式为远端监听绑定地址）
     */
    private String remoteHost;

    /**
     * 远端端口（LOCAL 模式为目标端口，REMOTE 模式为远端实际监听绑定端口）
     */
    private Integer remotePort;

    /**
     * 隧道长连接是否存活
     */
    private Boolean alive;

    /**
     * 从底层受管隧道句柄转换为对外展示 VO
     *
     * @param handle 受管隧道句柄
     * @return 隧道状态 VO
     */
    public static SshTunnelInfoVO from(SshTunnelHandle handle) {
        if (handle == null) {
            return null;
        }
        return SshTunnelInfoVO.builder()
                .tunnelMode(handle.getTunnelMode())
                .localPort(handle.getLocalPort())
                .localHost(handle.getLocalHost())
                .remoteHost(handle.getRemoteHost())
                .remotePort(handle.getRemotePort())
                .alive(handle.isAlive())
                .build();
    }
}

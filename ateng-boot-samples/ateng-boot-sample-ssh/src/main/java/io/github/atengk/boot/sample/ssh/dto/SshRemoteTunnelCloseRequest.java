package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 远程端口转发受管释放请求 DTO
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshRemoteTunnelCloseRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待释放的远端监听端口
     */
    @NotNull(message = "远端端口不能为空")
    @Min(value = 1, message = "远端端口必须大于 0")
    @Max(value = 65535, message = "远端端口不能超过 65535")
    private Integer remotePort;
}

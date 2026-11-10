package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 本地端口转发受管释放请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshTunnelCloseRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待释放的本地监听端口
     */
    @NotNull(message = "本地端口不能为空")
    @Min(value = 1, message = "本地端口必须大于 0")
    private Integer localPort;
}

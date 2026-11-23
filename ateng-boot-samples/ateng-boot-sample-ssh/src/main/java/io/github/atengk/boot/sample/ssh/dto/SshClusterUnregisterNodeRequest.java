package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 注销具名 SSH 服务节点并联动驱逐旧会话请求 DTO
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshClusterUnregisterNodeRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待注销的服务节点名称
     */
    @NotBlank(message = "节点名称不能为空")
    private String serverName;
}

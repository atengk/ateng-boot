package io.github.atengk.boot.sample.ftp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 注销具名 FTP 服务节点请求 DTO
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnregisterNodeRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待注销的具名 FTP 服务节点标识
     */
    @NotBlank(message = "服务节点标识不能为空")
    private String serverName;
}

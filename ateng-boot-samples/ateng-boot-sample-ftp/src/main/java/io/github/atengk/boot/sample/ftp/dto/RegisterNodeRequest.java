package io.github.atengk.boot.sample.ftp.dto;

import io.github.atengk.boot.ftp.enums.FtpMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 动态注册具名 FTP 服务节点请求 DTO
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterNodeRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 服务节点标识（如 tenant-a）
     */
    @NotBlank(message = "服务节点标识不能为空")
    private String serverName;

    /**
     * FTP 服务端主机地址
     */
    @NotBlank(message = "FTP 主机地址不能为空")
    private String host;

    /**
     * FTP 服务端端口
     */
    @NotNull(message = "FTP 端口不能为空")
    @Min(value = 1, message = "FTP 端口必须大于 0")
    @Max(value = 65535, message = "FTP 端口不能超过 65535")
    @Builder.Default
    private Integer port = 21;

    /**
     * 认证用户名
     */
    @NotBlank(message = "认证用户名不能为空")
    private String username;

    /**
     * 认证密码
     */
    private String password;

    /**
     * 传输模式（可选，未传则继承全局主节点默认模式）
     */
    private FtpMode mode;
}

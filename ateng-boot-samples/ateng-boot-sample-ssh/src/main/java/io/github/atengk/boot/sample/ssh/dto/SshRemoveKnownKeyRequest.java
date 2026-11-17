package io.github.atengk.boot.sample.ssh.dto;

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
 * 定点移除内存中已知主机公钥指纹请求 DTO
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshRemoveKnownKeyRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标主机地址或域名
     */
    @NotBlank(message = "目标主机地址不能为空")
    private String host;

    /**
     * 目标 SSH 端口
     */
    @NotNull(message = "SSH 端口不能为空")
    @Min(value = 1, message = "端口范围必须为 1~65535")
    @Max(value = 65535, message = "端口范围必须为 1~65535")
    private Integer port;
}

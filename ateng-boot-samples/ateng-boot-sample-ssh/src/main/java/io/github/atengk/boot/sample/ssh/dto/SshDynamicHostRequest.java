package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;

/**
 * CMDB 纯内存资产动态直连请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshDynamicHostRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标主机地址或域名
     */
    @NotBlank(message = "目标主机地址不能为空")
    private String host;

    /**
     * SSH 端口
     */
    @NotNull(message = "SSH 端口不能为空")
    @Min(value = 1, message = "端口范围必须为 1~65535")
    @Max(value = 65535, message = "端口范围必须为 1~65535")
    @Builder.Default
    private Integer port = 22;

    /**
     * 登录用户名
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 登录口令（可选）
     */
    @ToString.Exclude
    private String password;

    /**
     * 纯内存私钥内容（可选）
     */
    @ToString.Exclude
    private String privateKey;

    /**
     * 待执行命令
     */
    @NotBlank(message = "待执行命令不能为空")
    private String command;
}

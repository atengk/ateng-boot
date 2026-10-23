package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * CMDB / 数据库动态读取资产连接执行请求载荷
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class SshDynamicConnectRequest {

    /**
     * 目标主机地址（必填）
     */
    @NotBlank(message = "目标主机地址不能为空")
    private String host;

    /**
     * SSH 端口（默认 22）
     */
    @Min(value = 1, message = "端口必须大于0")
    @Max(value = 65535, message = "端口不能超过65535")
    private int port = 22;

    /**
     * 用户名（必填）
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 密码凭据
     */
    private String password;

    /**
     * 纯内存私钥内容（PEM 格式文本）
     */
    private String privateKey;

    /**
     * 待执行命令（必填）
     */
    @NotBlank(message = "待执行命令不能为空")
    private String command;
}

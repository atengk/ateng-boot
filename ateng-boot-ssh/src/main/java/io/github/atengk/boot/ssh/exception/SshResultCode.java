package io.github.atengk.boot.ssh.exception;

import io.github.atengk.boot.core.code.IResultCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * SSH 远程治理模块专属响应状态码枚举
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@AllArgsConstructor
public enum SshResultCode implements IResultCode {

    /**
     * SSH 连接建立失败
     */
    CONNECT_FAILED(56001, "SSH连接建立失败"),

    /**
     * SSH 身份凭据认证失败
     */
    AUTH_FAILED(56002, "SSH身份凭据认证失败"),

    /**
     * SSH 服务端主机指纹校验未通过
     */
    HOST_KEY_REJECTED(56003, "SSH服务端主机指纹校验未通过"),

    /**
     * SSH 远程命令执行异常
     */
    COMMAND_EXEC_FAILED(56004, "SSH远程命令执行异常"),

    /**
     * SSH 远程命令执行超时
     */
    COMMAND_TIMEOUT(56005, "SSH远程命令执行超时"),

    /**
     * SFTP 文件传输或读写异常
     */
    SFTP_TRANSFER_FAILED(56006, "SFTP文件传输或读写异常"),

    /**
     * SFTP 远程文件或目录不存在
     */
    SFTP_FILE_NOT_FOUND(56007, "SFTP远程文件或目录不存在"),

    /**
     * SFTP 远程操作权限不足
     */
    SFTP_PERMISSION_DENIED(56008, "SFTP远程操作权限不足"),

    /**
     * SSH 端口转发隧道异常
     */
    TUNNEL_FAILED(56009, "SSH端口转发隧道异常"),

    /**
     * SSH 跳板机中继穿透失败
     */
    JUMP_HOST_FAILED(56010, "SSH跳板机中继穿透失败"),

    /**
     * 未找到指定的 SSH 服务节点
     */
    SERVER_NOT_FOUND(56011, "未找到指定的SSH服务节点"),

    /**
     * SSH 通用操作执行失败
     */
    OPERATION_FAILED(56012, "SSH通用操作执行失败"),

    /**
     * SSH 交互式终端创建失败
     */
    SHELL_OPEN_FAILED(56013, "SSH交互式终端创建失败"),

    /**
     * SSH 交互式终端操作失败
     */
    SHELL_OPERATION_FAILED(56014, "SSH交互式终端操作失败"),

    /**
     * SSH 集群批量命令执行异常
     */
    BATCH_EXEC_FAILED(56015, "SSH集群批量命令执行异常"),

    /**
     * SSH 服务节点连通性探测失败
     */
    PROBE_FAILED(56016, "SSH服务节点连通性探测失败");

    /**
     * 业务状态码
     */
    private final Integer code;

    /**
     * 状态描述信息
     */
    private final String msg;
}

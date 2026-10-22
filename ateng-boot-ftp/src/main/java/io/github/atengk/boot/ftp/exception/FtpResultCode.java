package io.github.atengk.boot.ftp.exception;

import io.github.atengk.boot.core.code.IResultCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * FTP 远程传输模块专属响应状态码枚举
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Getter
@AllArgsConstructor
public enum FtpResultCode implements IResultCode {

    /**
     * FTP 服务端连接失败
     */
    CONNECT_FAILED(51001, "FTP服务端连接失败"),

    /**
     * FTP 身份鉴权认证失败
     */
    AUTH_FAILED(51002, "FTP身份鉴权认证失败"),

    /**
     * FTP 远程文件或目录不存在
     */
    FILE_NOT_FOUND(51003, "FTP远程文件或目录不存在"),

    /**
     * FTP 文件上传失败
     */
    UPLOAD_FAILED(51004, "FTP文件上传失败"),

    /**
     * FTP 文件下载失败
     */
    DOWNLOAD_FAILED(51005, "FTP文件下载失败"),

    /**
     * FTP 文件或目录删除失败
     */
    DELETE_FAILED(51006, "FTP文件或目录删除失败"),

    /**
     * FTP 路径包含非法字符或路径穿越风险
     */
    INVALID_PATH(51007, "FTP路径包含非法字符或路径穿越风险"),

    /**
     * FTP 连接池已耗尽或借出超时
     */
    POOL_EXHAUSTED(51008, "FTP连接池已耗尽或借出超时"),

    /**
     * FTP 操作执行失败
     */
    OPERATION_FAILED(51009, "FTP操作执行失败"),

    /**
     * 未找到指定的 FTP 服务节点
     */
    SERVER_NOT_FOUND(51010, "未找到指定的FTP服务节点");

    /**
     * 业务状态码
     */
    private final Integer code;

    /**
     * 状态描述信息
     */
    private final String msg;
}

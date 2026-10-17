package io.github.atengk.boot.storage.exception;

import io.github.atengk.boot.core.code.IResultCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 存储模块专属响应状态码枚举
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Getter
@AllArgsConstructor
public enum StorageResultCode implements IResultCode {

    /**
     * 存储对象不存在
     */
    OBJECT_NOT_FOUND(50001, "存储对象不存在"),

    /**
     * 存储对象上传失败
     */
    UPLOAD_FAILED(50002, "存储对象上传失败"),

    /**
     * 存储对象下载失败
     */
    DOWNLOAD_FAILED(50003, "存储对象下载失败"),

    /**
     * 存储对象删除失败
     */
    DELETE_FAILED(50004, "存储对象删除失败"),

    /**
     * 存储路径包含非法字符或路径遍历风险
     */
    INVALID_PATH(50005, "存储路径包含非法字符或路径遍历风险"),

    /**
     * 未找到指定的存储源
     */
    STORAGE_SOURCE_NOT_FOUND(50006, "未找到指定的存储源"),

    /**
     * 预签名外链已过期或签名无效
     */
    PRESIGNED_URL_INVALID(50007, "预签名外链已过期或签名无效"),

    /**
     * 存储操作执行异常
     */
    OPERATION_FAILED(50008, "存储操作执行异常");

    /**
     * 状态码数值
     */
    private final Integer code;

    /**
     * 状态提示文本
     */
    private final String msg;
}

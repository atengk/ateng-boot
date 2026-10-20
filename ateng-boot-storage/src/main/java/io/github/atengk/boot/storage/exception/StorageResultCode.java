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
    OPERATION_FAILED(50008, "存储操作执行异常"),

    /**
     * 文件真实类型与扩展名不匹配（存在伪装风险）
     */
    STORAGE_FILE_TYPE_MISMATCH(40010, "文件真实类型与扩展名不匹配"),

    /**
     * 文件类型不被允许上传
     */
    STORAGE_FILE_NOT_ALLOWED(40011, "文件类型不被允许上传"),

    /**
     * 分片缺失或不连续，无法完成合并
     */
    STORAGE_CHUNK_MISSING(40012, "分片缺失或不连续，无法完成合并"),

    /**
     * 分片索引或总片数非法
     */
    STORAGE_CHUNK_INVALID(40013, "分片索引或总片数非法"),

    /**
     * 存储对象签名验证失败（防篡改与验签未通过）
     */
    STORAGE_SIGNATURE_INVALID(40018, "存储对象签名验证失败"),

    /**
     * 预签名外链访问已超时过期
     */
    STORAGE_URL_EXPIRED(40019, "预签名外链已过期"),

    /**
     * 上传对象大小超出最大限制配额
     */
    STORAGE_SIZE_EXCEEDED(40020, "上传对象大小超出限制配额"),

    /**
     * 存储对象散列哈希校验不匹配（完整性比对失败）
     */
    STORAGE_HASH_MISMATCH(40021, "存储对象散列校验不匹配");

    /**
     * 状态码数值
     */
    private final Integer code;

    /**
     * 状态提示文本
     */
    private final String msg;
}

package io.github.atengk.boot.milvus.exception;

import io.github.atengk.boot.core.code.IResultCode;
import lombok.Getter;

/**
 * Milvus 向量数据库专有业务状态码枚举
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Getter
public enum MilvusResultCode implements IResultCode {

    /**
     * 客户端初始化失败
     */
    CLIENT_INIT_FAILED(50010, "Milvus 客户端初始化失败"),

    /**
     * 集合不存在
     */
    COLLECTION_NOT_FOUND(40410, "Milvus 集合不存在"),

    /**
     * 集合已存在
     */
    COLLECTION_ALREADY_EXISTS(40011, "Milvus 集合已存在"),

    /**
     * 集合未加载至内存
     */
    COLLECTION_NOT_LOADED(40012, "Milvus 集合未加载至内存"),

    /**
     * 向量索引创建失败
     */
    INDEX_BUILD_FAILED(50011, "Milvus 向量索引创建失败"),

    /**
     * 分区不存在
     */
    PARTITION_NOT_FOUND(40411, "Milvus 分区不存在"),

    /**
     * 向量维度不匹配
     */
    VECTOR_DIMENSION_MISMATCH(40013, "向量维度不匹配"),

    /**
     * 多租户模式下租户标识缺失
     */
    TENANT_ID_REQUIRED(40310, "多租户模式下租户标识缺失"),

    /**
     * 数据库连接或操作执行失败
     */
    OPERATION_FAILED(50012, "Milvus 向量数据库操作失败");

    private final Integer code;
    private final String msg;

    MilvusResultCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}

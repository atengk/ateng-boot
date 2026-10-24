package io.github.atengk.boot.datasource.enums;

import io.github.atengk.boot.core.code.IResultCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 多数据源与物理隔离模块专属响应状态码枚举 (59001~59020)
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Getter
@AllArgsConstructor
public enum DatasourceResultCode implements IResultCode {

    /**
     * 未找到租户对应的物理数据源
     */
    TENANT_DATASOURCE_NOT_FOUND(59001, "未找到租户对应的物理数据源"),

    /**
     * 数据源切换异常
     */
    DATASOURCE_SWITCH_ERROR(59002, "数据源切换异常"),

    /**
     * 数据源连接异常
     */
    DATASOURCE_CONNECTION_ERROR(59003, "数据源连接异常"),

    /**
     * 当前数据源方言不支持该特化操作
     */
    UNSUPPORTED_DIALECT_FEATURE(59004, "当前数据源方言不支持该特化操作"),

    /**
     * 数据源不存在
     */
    DATASOURCE_NOT_FOUND(59005, "数据源不存在");

    private final Integer code;
    private final String msg;
}

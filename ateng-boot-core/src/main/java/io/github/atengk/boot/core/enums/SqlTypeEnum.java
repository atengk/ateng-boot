package io.github.atengk.boot.core.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 物理 SQL 操作类型枚举
 * 集中收敛涵盖 5 种数据库基础操作类型（SELECT、INSERT、UPDATE、DELETE、OTHER），
 * 为持久层操作审计、拦截器派发与日志分析提供标准强类型契约。
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Getter
@AllArgsConstructor
public enum SqlTypeEnum implements IBaseEnum<String> {

    /**
     * 查询操作
     */
    SELECT("SELECT", "查询"),

    /**
     * 插入操作
     */
    INSERT("INSERT", "插入"),

    /**
     * 更新操作
     */
    UPDATE("UPDATE", "更新"),

    /**
     * 删除操作
     */
    DELETE("DELETE", "删除"),

    /**
     * 其他操作（如 DDL、FLUSH 等）
     */
    OTHER("OTHER", "其他");

    @JsonValue
    private final String code;

    private final String label;

    /**
     * 判定当前操作是否属于 DML 写操作（INSERT / UPDATE / DELETE）
     *
     * @return 当为 INSERT、UPDATE 或 DELETE 时返回 true，否则返回 false
     */
    public boolean isDml() {
        return this == INSERT || this == UPDATE || this == DELETE;
    }

    /**
     * 判定当前操作是否属于只读查询操作（SELECT）
     *
     * @return 当为 SELECT 时返回 true，否则返回 false
     */
    public boolean isQuery() {
        return this == SELECT;
    }

    /**
     * 根据名称或编码安全匹配 SQL 操作类型
     *
     * @param code 状态码或枚举名
     * @return 对应的 SQL 类型，无法匹配或为 null/blank 时返回 OTHER
     */
    public static SqlTypeEnum of(String code) {
        if (code == null || code.isBlank()) {
            return OTHER;
        }
        String trimmed = code.trim();
        for (SqlTypeEnum type : values()) {
            if (type.code.equalsIgnoreCase(trimmed) || type.name().equalsIgnoreCase(trimmed)) {
                return type;
            }
        }
        return OTHER;
    }

    /**
     * 根据输入值（数字、字符串）安全解析操作类型
     * 供 Jackson 反序列化与多源入参兼容解析（支持大小写与通用映射）
     *
     * @param value 输入值对象
     * @return 匹配的操作类型，无法匹配时保底返回 OTHER
     */
    @JsonCreator
    public static SqlTypeEnum fromValue(Object value) {
        if (value == null) {
            return OTHER;
        }
        return of(value.toString());
    }
}

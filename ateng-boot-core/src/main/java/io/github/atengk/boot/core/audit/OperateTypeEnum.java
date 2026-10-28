package io.github.atengk.boot.core.audit;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Objects;

/**
 * 通用操作审计动作类型枚举
 * 涵盖 11 种通用操作动作，为跨层操作审计流转与日志消费提供强类型契约
 *
 * @author Ateng
 * @since 2026-10-18
 */
@Getter
@AllArgsConstructor
public enum OperateTypeEnum {

    /**
     * 其他操作
     */
    OTHER(0, "其他"),

    /**
     * 新增操作
     */
    INSERT(1, "新增"),

    /**
     * 修改操作
     */
    UPDATE(2, "修改"),

    /**
     * 删除操作
     */
    DELETE(3, "删除"),

    /**
     * 查询操作
     */
    QUERY(4, "查询"),

    /**
     * 导出操作
     */
    EXPORT(5, "导出"),

    /**
     * 导入操作
     */
    IMPORT(6, "导入"),

    /**
     * 登录操作
     */
    LOGIN(7, "登录"),

    /**
     * 登出操作
     */
    LOGOUT(8, "登出"),

    /**
     * 授权操作
     */
    GRANT(9, "授权"),

    /**
     * 清空操作
     */
    CLEAN(10, "清空");

    /**
     * 操作动作数字编码
     */
    private final Integer code;

    /**
     * 操作动作语义说明
     */
    private final String description;

    /**
     * 根据数值编码安全匹配操作类型
     *
     * @param code 数值编码
     * @return 对应的操作类型，未匹配或为 null 时保底返回 {@link #OTHER}
     */
    public static OperateTypeEnum of(Integer code) {
        if (code == null) {
            return OTHER;
        }
        for (OperateTypeEnum type : values()) {
            if (Objects.equals(type.code, code)) {
                return type;
            }
        }
        return OTHER;
    }

    /**
     * 根据名称或编码安全解析操作类型
     * 供 Jackson 反序列化与多源入参兼容解析（支持枚举名大小写、数字编码与数字字符串）
     *
     * @param value 输入值（数字、字符串）
     * @return 匹配的操作类型，无法匹配时保底返回 {@link #OTHER}
     */
    @JsonCreator
    public static OperateTypeEnum fromValue(Object value) {
        if (value == null) {
            return OTHER;
        }
        if (value instanceof Number number) {
            return of(number.intValue());
        }
        String str = value.toString().trim();
        for (OperateTypeEnum type : values()) {
            if (type.name().equalsIgnoreCase(str)) {
                return type;
            }
        }
        try {
            int code = Integer.parseInt(str);
            return of(code);
        } catch (NumberFormatException ignored) {
            return OTHER;
        }
    }
}

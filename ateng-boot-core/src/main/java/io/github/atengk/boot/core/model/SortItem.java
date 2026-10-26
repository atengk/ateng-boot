package io.github.atengk.boot.core.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.Objects;

/**
 * 传输层强类型排序项契约模型
 * 封装排序属性名称与排序方向，内置白名单安全正则校验以防御 SQL 注入
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode
public class SortItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 排序字段安全白名单校验正则（仅允许字母、数字和下划线且长度不超过64位，防御注入）
     */
    public static final String PROPERTY_REGEX = "^[a-zA-Z0-9_]{1,64}$";

    /**
     * 排序字段安全白名单预编译正则
     */
    private static final java.util.regex.Pattern PROPERTY_PATTERN = java.util.regex.Pattern.compile(PROPERTY_REGEX);

    /**
     * 排序属性名称
     */
    @NotBlank(message = "排序属性名称不能为空")
    @Pattern(regexp = PROPERTY_REGEX, message = "排序属性名称只能包含字母、数字和下划线")
    private String property;

    /**
     * 排序方向（默认 ASC）
     */
    @NotNull(message = "排序方向不能为空")
    private Direction direction = Direction.ASC;

    /**
     * 默认无参构造方法（供序列化与框架反射使用）
     */
    public SortItem() {
    }

    /**
     * 构造指定属性名称的升序排序项
     *
     * @param property 排序属性名称
     */
    public SortItem(String property) {
        this(property, Direction.ASC);
    }

    /**
     * 构造指定属性名称与排序方向的排序项
     *
     * @param property  排序属性名称
     * @param direction 排序方向
     */
    public SortItem(String property, Direction direction) {
        setProperty(property);
        this.direction = (direction != null) ? direction : Direction.ASC;
    }

    /**
     * 构造指定属性名称与升降序布尔值的排序项
     *
     * @param property 排序属性名称
     * @param asc      true 为升序 (ASC)，false 为降序 (DESC)
     */
    public SortItem(String property, boolean asc) {
        this(property, asc ? Direction.ASC : Direction.DESC);
    }

    /**
     * 校验排序属性名称是否符合安全白名单规范
     *
     * @param property 待校验的属性名
     * @return true 若符合白名单；否则 false
     */
    public static boolean isValidProperty(String property) {
        return property != null && PROPERTY_PATTERN.matcher(property).matches();
    }

    /**
     * 快捷创建升序排序项
     *
     * @param property 排序属性名称
     * @return 升序排序项实例
     */
    public static SortItem asc(String property) {
        return of(property, Direction.ASC);
    }

    /**
     * 快捷创建降序排序项
     *
     * @param property 排序属性名称
     * @return 降序排序项实例
     */
    public static SortItem desc(String property) {
        return of(property, Direction.DESC);
    }

    /**
     * 快捷创建排序项
     *
     * @param property  排序属性名称
     * @param direction 排序方向
     * @return 排序项实例
     */
    public static SortItem of(String property, Direction direction) {
        Objects.requireNonNull(property, "排序属性名称不能为 null");
        return new SortItem(property, direction);
    }

    /**
     * 快捷创建排序项
     *
     * @param property 排序属性名称
     * @param asc      是否升序
     * @return 排序项实例
     */
    public static SortItem of(String property, boolean asc) {
        Objects.requireNonNull(property, "排序属性名称不能为 null");
        return new SortItem(property, asc);
    }

    /**
     * 设置排序属性名称并执行安全正则防卫
     *
     * @param property 排序属性名称
     */
    public void setProperty(String property) {
        if (property != null && !property.trim().isEmpty() && !isValidProperty(property.trim())) {
            throw new IllegalArgumentException("排序属性名称格式非法，仅支持字母、数字和下划线且长度不超过64位: " + property);
        }
        this.property = property != null ? property.trim() : null;
    }

    /**
     * 获取排序列名称（兼容列名语义，等同于 {@link #getProperty()}）
     *
     * @return 排序列或属性名称
     */
    public String getColumn() {
        return getProperty();
    }

    /**
     * 设置排序列名称（兼容列名语义，等同于 {@link #setProperty(String)}）
     *
     * @param column 排序列或属性名称
     */
    public void setColumn(String column) {
        setProperty(column);
    }

    /**
     * 判断当前是否为升序排序
     *
     * @return true 若为升序或 direction 为 null（保底为升序）；否则 false
     */
    public boolean isAsc() {
        return direction == null || direction.isAscending();
    }

    /**
     * 排序方向枚举
     */
    public enum Direction {
        /**
         * 升序
         */
        ASC,

        /**
         * 降序
         */
        DESC;

        /**
         * 是否为升序
         *
         * @return true 若为升序；否则 false
         */
        public boolean isAscending() {
            return this == ASC;
        }

        /**
         * 是否为降序
         *
         * @return true 若为降序；否则 false
         */
        public boolean isDescending() {
            return this == DESC;
        }

        /**
         * 从字符串宽容解析排序方向（忽略大小写与首尾空格）
         *
         * @param value 字符串值（如 "asc", "DESC"）
         * @return 对应的 Direction 枚举
         */
        public static Direction fromString(String value) {
            if (value == null || value.trim().isEmpty()) {
                return ASC;
            }
            String trimmed = value.trim();
            if ("desc".equalsIgnoreCase(trimmed)) {
                return DESC;
            }
            if ("asc".equalsIgnoreCase(trimmed)) {
                return ASC;
            }
            throw new IllegalArgumentException("未知的排序方向: " + value + "，仅支持 ASC 或 DESC");
        }
    }
}

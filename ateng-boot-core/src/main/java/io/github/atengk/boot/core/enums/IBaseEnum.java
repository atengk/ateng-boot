package io.github.atengk.boot.core.enums;

import io.github.atengk.boot.core.model.OptionItem;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 强类型通用枚举契约接口
 * 统管业务状态码与展示标签，提供空安全判等、转换为 OptionItem 选项模型及 MyBatis-Plus IEnum 兼容能力
 *
 * @param <T> 状态码数据类型（如 Integer, String, Long 等）
 * @author Ateng
 * @since 2026-10-28
 */
public interface IBaseEnum<T extends Serializable> {

    /**
     * 获取枚举状态码值
     *
     * @return 状态码
     */
    T getCode();

    /**
     * 获取枚举展示标签/描述文本
     *
     * @return 展示标签
     */
    String getLabel();

    /**
     * 空安全判断给定状态码是否与当前枚举匹配
     *
     * @param code 待比对的状态码
     * @return 是否匹配
     */
    default boolean matches(T code) {
        return Objects.equals(getCode(), code);
    }

    /**
     * 将当前枚举实例转换为前端通用的选项模型
     *
     * @return 标准四字段 OptionItem
     */
    default OptionItem<T> toOption() {
        return OptionItem.of(getCode(), getLabel(), getDisabled(), getExtra());
    }

    /**
     * 当前枚举选项是否禁用（供前端下拉框或选项列表使用）
     *
     * @return 默认 false（启用）
     */
    default Boolean getDisabled() {
        return false;
    }

    /**
     * 扩展元数据（可放置 tag 颜色、排序权重、样式等扩展属性）
     *
     * @return 扩展键值映射，默认返回空 Map
     */
    default Map<String, Object> getExtra() {
        return Collections.emptyMap();
    }

    /**
     * 状态码别名方法，提供对 MyBatis-Plus IEnum 接口签名的天然兼容（零依赖引入）
     *
     * @return 状态码值
     */
    default T getValue() {
        return getCode();
    }
}

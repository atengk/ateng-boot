package io.github.atengk.boot.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

/**
 * 面向前端组件库标准下拉选择器与过滤器的强类型选项键值对模型
 * 封装编码、展示标签、禁用状态与扩展元数据
 *
 * @param <T> 编码类型
 * @author Ateng
 * @since 2026-10-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionItem<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 选项值/状态编码
     */
    private T code;

    /**
     * 选项展示标签/文本
     */
    private String label;

    /**
     * 是否禁用
     */
    @Builder.Default
    private Boolean disabled = false;

    /**
     * 扩展元数据（如颜色 tag、排序、描述或业务自定义参数）
     */
    @Builder.Default
    private Map<String, Object> extra = Collections.emptyMap();

    public static <T> OptionItem<T> of(T code, String label) {
        return OptionItem.<T>builder()
                .code(code)
                .label(label)
                .disabled(false)
                .extra(Collections.emptyMap())
                .build();
    }

    public static <T> OptionItem<T> of(T code, String label, Boolean disabled) {
        return OptionItem.<T>builder()
                .code(code)
                .label(label)
                .disabled(disabled != null && disabled)
                .extra(Collections.emptyMap())
                .build();
    }

    public static <T> OptionItem<T> of(T code, String label, Boolean disabled, Map<String, Object> extra) {
        return OptionItem.<T>builder()
                .code(code)
                .label(label)
                .disabled(disabled != null && disabled)
                .extra(extra != null ? extra : Collections.emptyMap())
                .build();
    }
}

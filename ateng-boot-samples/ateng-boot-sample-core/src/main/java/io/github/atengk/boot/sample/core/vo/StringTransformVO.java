package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 纯原生高性能通用字符串变换结果视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StringTransformVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 驼峰命名转换结果
     */
    private String camelCase;

    /**
     * 下划线命名转换结果
     */
    private String underlineCase;

    /**
     * 安全截断后的字符串
     */
    private String truncatedText;

    /**
     * 模板格式化后的文本
     */
    private String formattedText;
}

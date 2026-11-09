package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 编程式分页安全纠偏与防溢出偏移量计算响应视图对象
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageNormalizeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 原始输入页码
     */
    private long rawCurrent;

    /**
     * 原始输入单页大小
     */
    private long rawSize;

    /**
     * safeCurrent 安全纠偏后的页码
     */
    private long safeCurrent;

    /**
     * safeSize 安全纠偏后的单页大小
     */
    private long safeSize;

    /**
     * offset 安全计算的分页偏移量（防大数溢出）
     */
    private long offset;

    /**
     * normalize 原地修正后的页码
     */
    private long normalizedCurrent;

    /**
     * normalize 原地修正后的单页大小
     */
    private long normalizedSize;
}

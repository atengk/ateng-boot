package io.github.atengk.boot.document.word.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Word 多文档物理拼装控制选项
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StitchOptions {

    /**
     * 是否在每份子文档之间插入强制分页符（默认 false，保持连续无缝拼接）
     */
    @Builder.Default
    private boolean pageBreakBetween = false;
}

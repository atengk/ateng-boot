package io.github.atengk.boot.document.enums;

import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * PDF 关键字定位自动盖章命中匹配策略枚举
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Getter
@AllArgsConstructor
public enum KeywordStampOccurrence implements IBaseEnum<String> {

    /**
     * 仅匹配首次出现的关键字位置
     */
    FIRST("first", "首次匹配"),

    /**
     * 仅匹配末次出现的关键字位置（合同落款常用默认策略）
     */
    LAST("last", "末次匹配"),

    /**
     * 在全部命中的关键字位置均压印公章
     */
    ALL("all", "全部匹配");

    private final String code;
    private final String label;
}

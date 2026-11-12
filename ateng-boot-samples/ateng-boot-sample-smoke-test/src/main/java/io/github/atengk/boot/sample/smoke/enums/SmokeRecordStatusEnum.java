package io.github.atengk.boot.sample.smoke.enums;

import io.github.atengk.boot.core.annotation.EnumAlias;
import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 冒烟记录状态枚举
 *
 * @author Ateng
 * @since 2026-11-12
 */
@Getter
@AllArgsConstructor
@EnumAlias(value = "smokeRecordStatus", description = "冒烟记录状态字典")
public enum SmokeRecordStatusEnum implements IBaseEnum<String> {

    /**
     * 草稿
     */
    DRAFT("DRAFT", "草稿"),

    /**
     * 已发布
     */
    PUBLISHED("PUBLISHED", "已发布");

    private final String code;
    private final String label;
}

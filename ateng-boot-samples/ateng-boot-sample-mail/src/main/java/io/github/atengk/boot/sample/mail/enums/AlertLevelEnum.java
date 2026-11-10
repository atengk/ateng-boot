package io.github.atengk.boot.sample.mail.enums;

import io.github.atengk.boot.core.annotation.EnumAlias;
import io.github.atengk.boot.core.enums.IBaseEnum;

/**
 * 线上服务故障与告警级别通用枚举
 *
 * @author Ateng
 * @since 2026-11-10
 */
@EnumAlias("alertLevel")
public enum AlertLevelEnum implements IBaseEnum<String> {

    P0("P0", "P0 - 灾难性阻断故障"),
    P0_CRITICAL("P0-CRITICAL", "P0 - 灾难性阻断故障"),
    P1("P1", "P1 - 核心服务降级告警"),
    P1_WARNING("P1-WARNING", "P1 - 核心服务降级告警"),
    P2("P2", "P2 - 次要功能异常预警"),
    P3("P3", "P3 - 低风险运维提示");

    private final String code;
    private final String label;

    AlertLevelEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getLabel() {
        return label;
    }
}

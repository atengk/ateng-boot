package io.github.atengk.boot.sample.web.enums;

import io.github.atengk.boot.core.annotation.EnumAlias;
import io.github.atengk.boot.core.enums.IBaseEnum;

/**
 * 演示订单类型通用枚举（显式标注 @EnumAlias 别名覆盖）
 *
 * @author Ateng
 * @since 2026-10-28
 */
@EnumAlias("orderType")
public enum OrderTypeEnum implements IBaseEnum<String> {

    NORMAL("normal", "普通订单"),
    SECKILL("seckill", "秒杀订单"),
    PROMOTION("promotion", "促销订单");

    private final String code;
    private final String label;

    OrderTypeEnum(String code, String label) {
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

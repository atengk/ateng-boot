package io.github.atengk.boot.pay.config.properties;

import lombok.Data;

import java.io.Serializable;

/**
 * 资金分账高敏操作配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class PayProfitSharingProperties implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 资金分账功能物理总开关，默认为 false（严格关闭防误触误调用）
     */
    private boolean enabled = false;
}

package io.github.atengk.boot.sample.datasource.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 事务守卫与多源协调事务演练展示视图对象
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardTransactionResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 执行模式（DS_TRANSACTIONAL / TENANT_TX_REENTRANT）
     */
    private String mode;

    /**
     * 主流程订单号
     */
    private String primaryOrderNo;

    /**
     * 目标数据源订单号
     */
    private String targetOrderNo;

    /**
     * 当前租户标识（未绑定租户时为 null）
     */
    private String tenantId;

    /**
     * 执行结果说明
     */
    private String message;
}

package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 配额风控自验响应视图对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiQuotaTestVo {

    /**
     * 校验与执行状态 (PASS)
     */
    private String status;

    /**
     * 当前租户标识
     */
    private String tenantId;

    /**
     * 当前租户当日累计请求调用次数
     */
    private long currentDailyRequests;

    /**
     * 当前租户当日累计 Token 消耗总量
     */
    private long currentDailyTokens;
}

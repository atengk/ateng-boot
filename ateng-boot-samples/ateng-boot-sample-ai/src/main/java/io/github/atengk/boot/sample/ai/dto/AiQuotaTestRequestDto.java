package io.github.atengk.boot.sample.ai.dto;

import lombok.Data;

/**
 * 配额风控自验测试入参 DTO
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
public class AiQuotaTestRequestDto {

    /**
     * 待测试的租户标识
     */
    private String tenantId = "tenant-sample";

    /**
     * 请求的目标大模型
     */
    private String model;

    /**
     * 本次预估消耗的输入 Token 数量
     */
    private int promptTokens = 10;

    /**
     * 本次调用实际消耗的 Token 总量
     */
    private int totalTokens = 20;

    /**
     * 是否仅触发前置检查而不记录消耗
     */
    private boolean checkOnly = false;
}

package io.github.atengk.boot.ai.quota;

/**
 * AI 大模型调用配额风控检查器 SPI
 * <p>
 * 在大模型调用发起前执行租户或用户维度的额度检查（如每日调用次数限制、Token 消耗上限等）。
 * 若额度已用尽或超限，必须立即抛出业务异常（如 {@code AiException(AiResultCode.AI_QUOTA_EXCEEDED)}）实现快速失败（Fail-Fast）。
 * 同时提供调用完成后消耗累计更新回调。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-04
 */
public interface AiQuotaChecker {

    /**
     * 在大模型调用发起前检查配额
     *
     * @param tenantId     租户标识 (可为 null 或空白)
     * @param modelName    请求的目标模型名称
     * @param promptTokens 预估或已知的输入 Token 数
     * @throws io.github.atengk.boot.ai.exception.AiException 若配额超限时抛出
     */
    void checkQuota(String tenantId, String modelName, int promptTokens);

    /**
     * 在大模型调用成功后记录实际消耗的配额
     *
     * @param tenantId        租户标识 (可为 null 或空白)
     * @param modelName       请求的目标模型名称
     * @param totalTokensUsed 实际消耗的 Token 总数 (prompt + completion)
     */
    void recordUsage(String tenantId, String modelName, int totalTokensUsed);

    /**
     * 获取指定租户今日累计调用次数
     *
     * @param tenantId 租户标识
     * @return 今日累计调用次数
     */
    long getDailyRequests(String tenantId);

    /**
     * 获取指定租户今日累计 Token 消耗量
     *
     * @param tenantId 租户标识
     * @return 今日累计 Token 消耗量
     */
    long getDailyTokens(String tenantId);

    /**
     * 重置指定租户今日配额计数
     *
     * @param tenantId 租户标识
     */
    void resetQuota(String tenantId);

    /**
     * 判断当前是否工作在分布式集群模式
     *
     * @return true 为分布式模式，false 为单机内存模式
     */
    boolean isDistributed();
}

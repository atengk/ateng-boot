package io.github.atengk.boot.sms.guard;

import io.github.atengk.boot.sms.exception.SmsBudgetExceededException;

/**
 * 短信单日成本预算熔断器 SPI
 * 统计全系统单日累计发信总量并在达到配额阈值时触发硬熔断，防御恶意盗刷引发的高额账单
 *
 * @author Ateng
 * @since 2026-11-02
 */
public interface DailyBudgetBreaker {

    /**
     * 校验并原子累计发信计数
     * 若累计后总量超过单日配额阈值，立即抛出 {@link SmsBudgetExceededException}
     *
     * @param count 待发送条数（必须大于 0）
     * @throws SmsBudgetExceededException 当达到或超过单日配额上限时抛出
     */
    void checkAndIncrement(int count) throws SmsBudgetExceededException;

    /**
     * 校验并原子增加 1 条发信计数
     *
     * @throws SmsBudgetExceededException 当达到或超过单日配额上限时抛出
     */
    default void checkAndIncrement() throws SmsBudgetExceededException {
        checkAndIncrement(1);
    }

    /**
     * 获取单日发信配额上限阈值（0 表示不设硬上限）
     *
     * @return 单日最大配额
     */
    long getDailyMaxQuota();

    /**
     * 获取当前自然日已累计发送量
     *
     * @return 当前已发条数
     */
    long getCurrentDailyCount();

    /**
     * 重置发信计数（通常用于跨日重置或单元测试清理）
     */
    void reset();
}

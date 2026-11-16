package io.github.atengk.boot.mail.guard;

import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailMessage;

import java.time.LocalDate;

/**
 * 邮件自然日总配额硬熔断器 SPI
 * <p>
 * 按自然日维度统计真实物理外发的去重收件人次总量，触顶后强制拒绝发信以便上层同步发布告警事件，
 * 避免无上限消耗第三方 SMTP 服务商配额或触发信用封禁。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-16
 */
public interface MailDailyQuotaBreaker {

    /**
     * 校验自然日剩余配额并原子扣减本次去重收件人次
     *
     * @param account        当前路由发信账号
     * @param message        待发送邮件领域消息
     * @param recipientCount 本次去重后的物理收件人次（必须 >= 1）
     * @return 配额核查与扣减结果
     */
    QuotaCheckResult tryConsume(MailAccount account, MailMessage message, int recipientCount);

    /**
     * 查询指定自然日当前已消耗的发送人次
     *
     * @param date 目标自然日
     * @return 已消耗数量
     */
    long getCurrentCount(LocalDate date);

    /**
     * 重置配额计数（用于单元测试隔离或运维手动复位）
     */
    default void reset() {
    }

    /**
     * 配额核查结果值对象
     *
     * @param allowed        是否允许放行并完成扣减
     * @param quotaDate      统计所属自然日
     * @param dailyLimit     配置的单日配额上限
     * @param currentCount   当前累计已消耗人数（若被拒绝则为拒绝前已占用总数）
     * @param requestedCount 本次请求扣减的收件人次
     */
    record QuotaCheckResult(
            boolean allowed,
            LocalDate quotaDate,
            long dailyLimit,
            long currentCount,
            int requestedCount
    ) {
    }
}

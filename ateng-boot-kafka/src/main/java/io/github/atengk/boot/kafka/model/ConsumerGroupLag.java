package io.github.atengk.boot.kafka.model;

import java.util.List;

/**
 * Kafka 消费组积压监控模型
 *
 * <p>封装消费组在目标主题下的总积压消息数及各分区详细位移快照，
 * 支撑自动化弹性扩缩容、SRE 告警探测及运维对账闭环。</p>
 *
 * @param groupId 消费组 ID
 * @param topic 主题名称
 * @param totalLag 全分区总积压消息数 (各分区 lag 累计和)
 * @param partitionLags 各分区积压明细列表 (按分区号升序)
 * @author Ateng
 * @since 2026-10-29
 */
public record ConsumerGroupLag(
        String groupId,
        String topic,
        long totalLag,
        List<PartitionLag> partitionLags
) {

    /**
     * 规范构造函数：保证不可变性与集合防空
     */
    public ConsumerGroupLag {
        partitionLags = partitionLags != null ? List.copyOf(partitionLags) : List.of();
    }

    /**
     * 便捷构造函数：自动根据分区明细列表累计总积压量
     *
     * @param groupId 消费组 ID
     * @param topic 主题名称
     * @param partitionLags 各分区积压明细列表
     */
    public ConsumerGroupLag(String groupId, String topic, List<PartitionLag> partitionLags) {
        this(
                groupId,
                topic,
                partitionLags != null ? partitionLags.stream().mapToLong(PartitionLag::lag).sum() : 0L,
                partitionLags
        );
    }

    /**
     * 单分区积压明细模型
     *
     * @param partition 分区编号
     * @param committedOffset 消费组当前提交位移（未提交或无有效位移时为 -1L）
     * @param logEndOffset 分区最新日志末端位移 (LEO / High Watermark)
     * @param lag 分区积压消息数 (logEndOffset - committedOffset，保底非负)
     * @author Ateng
     * @since 2026-10-29
     */
    public record PartitionLag(
            int partition,
            long committedOffset,
            long logEndOffset,
            long lag
    ) {

        /**
         * 工厂方法：根据当前提交位移与最新日志位移构建单分区积压快照
         *
         * @param partition 分区编号
         * @param committedOffset 消费组已提交位移（可为 null，代表尚未提交位移）
         * @param logEndOffset 分区最新日志末端位移
         * @return 单分区积压快照
         */
        public static PartitionLag of(int partition, Long committedOffset, long logEndOffset) {
            long committed = committedOffset != null ? committedOffset : -1L;
            long effectiveCommitted = committed >= 0 ? committed : 0L;
            long lag = Math.max(0L, logEndOffset - effectiveCommitted);
            return new PartitionLag(partition, committed, logEndOffset, lag);
        }
    }
}

package io.github.atengk.boot.mail.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 批量邮件合并发信聚合执行回执领域模型
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchMailSendResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 批次邮件总数
     */
    private int totalCount;

    /**
     * 发送成功数
     */
    private int successCount;

    /**
     * 发送失败数
     */
    private int failureCount;

    /**
     * 批量执行总耗时
     */
    private Duration duration;

    /**
     * 批量完成时间戳
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * 每封邮件具体投递明细列表（保持批次原始顺序）
     */
    @Builder.Default
    private List<MailSendResultItem> items = new ArrayList<>();

    /**
     * 是否全量成功
     *
     * @return 若总数大于0且失败数为0返回 true
     */
    public boolean isAllSuccess() {
        return totalCount > 0 && failureCount == 0;
    }

    /**
     * 是否存在失败项
     *
     * @return 存在失败返回 true
     */
    public boolean hasFailure() {
        return failureCount > 0;
    }

    /**
     * 获取所有投递成功的明细列表
     *
     * @return 成功明细列表
     */
    public List<MailSendResultItem> getSuccessItems() {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        return items.stream().filter(MailSendResultItem::isSuccess).toList();
    }

    /**
     * 获取所有投递失败的明细列表
     *
     * @return 失败明细列表
     */
    public List<MailSendResultItem> getFailureItems() {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        return items.stream().filter(item -> !item.isSuccess()).toList();
    }
}

package io.github.atengk.boot.sample.mail.dto;

import io.github.atengk.boot.core.validation.ValidList;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 事务提交后批量发送（sendBatchAfterCommit）演示请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchAfterCommitRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 批量邮件通知条目列表
     */
    @Valid
    @NotEmpty(message = "批量邮件通知列表不能为空")
    private ValidList<SimpleTextMailRequest> items;

    /**
     * 是否模拟业务异常触发事务回滚（true 时应取消整批邮件发送）
     */
    @Builder.Default
    private boolean shouldRollback = false;
}

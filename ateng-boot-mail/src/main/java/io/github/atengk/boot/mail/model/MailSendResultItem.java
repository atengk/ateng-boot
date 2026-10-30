package io.github.atengk.boot.mail.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 批量邮件中单封邮件投递明细载荷
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailSendResultItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 批次内序号（0-based）
     */
    private int index;

    /**
     * 目标收件人列表
     */
    @Builder.Default
    private List<String> recipients = Collections.emptyList();

    /**
     * 邮件主题
     */
    private String subject;

    /**
     * 是否发送成功
     */
    private boolean success;

    /**
     * 发送成功时的详细回执
     */
    private MailSendResult result;

    /**
     * 发送失败时的错误描述
     */
    private String errorMessage;

    /**
     * 发送失败时的异常根因
     */
    private Throwable cause;

    /**
     * 完成时间戳
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * 构建成功明细
     *
     * @param index   批次内序号
     * @param message 邮件消息模型
     * @param result  发信回执
     * @return 成功明细对象
     */
    public static MailSendResultItem success(int index, MailMessage message, MailSendResult result) {
        return MailSendResultItem.builder()
                .index(index)
                .recipients(message != null && message.getTo() != null ? List.copyOf(message.getTo()) : Collections.emptyList())
                .subject(message != null ? message.getSubject() : null)
                .success(true)
                .result(result)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * 构建失败明细
     *
     * @param index        批次内序号
     * @param message      邮件消息模型
     * @param errorMessage 错误描述
     * @param cause        异常根因
     * @return 失败明细对象
     */
    public static MailSendResultItem failure(int index, MailMessage message, String errorMessage, Throwable cause) {
        return MailSendResultItem.builder()
                .index(index)
                .recipients(message != null && message.getTo() != null ? List.copyOf(message.getTo()) : Collections.emptyList())
                .subject(message != null ? message.getSubject() : null)
                .success(false)
                .errorMessage(errorMessage)
                .cause(cause)
                .timestamp(Instant.now())
                .build();
    }
}

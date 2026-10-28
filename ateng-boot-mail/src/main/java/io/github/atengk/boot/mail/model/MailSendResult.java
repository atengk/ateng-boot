package io.github.atengk.boot.mail.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 邮件发送结果回执领域模型
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailSendResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 遵循 RFC 5322 规范的唯一邮件消息标识
     */
    private String messageId;

    /**
     * 是否发送成功
     */
    private boolean success;

    /**
     * 目标收件人列表
     */
    @Builder.Default
    private List<String> recipients = Collections.emptyList();

    /**
     * 发送耗时
     */
    private Duration duration;

    /**
     * 发信完成时间戳
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * 构建成功回执
     *
     * @param messageId  邮件消息标识
     * @param recipients 目标收件人列表
     * @param duration   耗时
     * @return 结果对象
     */
    public static MailSendResult success(String messageId, List<String> recipients, Duration duration) {
        return MailSendResult.builder()
                .messageId(messageId)
                .success(true)
                .recipients(recipients != null ? List.copyOf(recipients) : Collections.emptyList())
                .duration(duration)
                .timestamp(Instant.now())
                .build();
    }
}

package io.github.atengk.boot.mail.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 邮件优先级与阅读回执模型构建单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
class MailPriorityAndArchiveBccTest {

    @Test
    @DisplayName("MailPriority: 枚举值与标准标头属性映射完全自洽")
    void testMailPriorityEnumValues() {
        assertThat(MailPriority.HIGH.getPriority()).isEqualTo(1);
        assertThat(MailPriority.HIGH.getXPriority()).isEqualTo("1");
        assertThat(MailPriority.HIGH.getImportance()).isEqualTo("High");

        assertThat(MailPriority.NORMAL.getPriority()).isEqualTo(3);
        assertThat(MailPriority.NORMAL.getXPriority()).isEqualTo("3");
        assertThat(MailPriority.NORMAL.getImportance()).isEqualTo("Normal");

        assertThat(MailPriority.LOW.getPriority()).isEqualTo(5);
        assertThat(MailPriority.LOW.getXPriority()).isEqualTo("5");
        assertThat(MailPriority.LOW.getImportance()).isEqualTo("Low");
    }

    @Test
    @DisplayName("MailMessage: 优先级与阅读回执链式构建正常赋值")
    void testMailMessagePriorityAndReadReceiptFluent() {
        MailMessage message = new MailMessage()
                .to("recipient@example.com")
                .subject("重要通知")
                .priority(MailPriority.HIGH)
                .readReceiptTo("receipt@example.com");

        assertThat(message.getPriority()).isEqualTo(MailPriority.HIGH);
        assertThat(message.getReadReceiptTo()).isEqualTo("receipt@example.com");
        assertThat(message.isRequestReadReceipt()).isTrue();
    }

    @Test
    @DisplayName("MailMessage: requestReadReceipt(true) 自动标记回执意图")
    void testMailMessageRequestReadReceiptOnly() {
        MailMessage message = new MailMessage()
                .to("recipient@example.com")
                .subject("需要回执")
                .requestReadReceipt(true);

        assertThat(message.isRequestReadReceipt()).isTrue();
        assertThat(message.getReadReceiptTo()).isNull();
    }

    @Test
    @DisplayName("MailMessage: bcc 集合安全追加与非空过滤")
    void testMailMessageBccChaining() {
        MailMessage message = new MailMessage()
                .bcc("bcc1@example.com", "bcc2@example.com")
                .bcc(List.of("bcc3@example.com"));

        assertThat(message.getBcc())
                .containsExactly("bcc1@example.com", "bcc2@example.com", "bcc3@example.com");
    }
}

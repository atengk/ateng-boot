package io.github.atengk.boot.mail.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 邮件消息领域对象单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
class MailMessageTest {

    @Test
    @DisplayName("测试 Fluent 链式构建完整邮件消息对象")
    void testFluentBuilding() {
        MailMessage message = new MailMessage()
                .account("ops")
                .from("monitor@atengk.com", "系统监控中心")
                .to("admin1@atengk.com", "admin2@atengk.com")
                .cc("lead@atengk.com")
                .bcc("audit@atengk.com")
                .replyTo("support@atengk.com")
                .subject("服务集群异常预警")
                .html("<h1>节点响应超时</h1>")
                .header("X-Priority", "1")
                .attach("log.txt", "error log details".getBytes())
                .inline("logo", "fake png data".getBytes(), "image/png");

        assertEquals("ops", message.getAccount());
        assertEquals("monitor@atengk.com", message.getFrom());
        assertEquals("系统监控中心", message.getFromName());
        assertEquals(List.of("admin1@atengk.com", "admin2@atengk.com"), message.getTo());
        assertEquals(List.of("lead@atengk.com"), message.getCc());
        assertEquals(List.of("audit@atengk.com"), message.getBcc());
        assertEquals("support@atengk.com", message.getReplyTo());
        assertEquals("服务集群异常预警", message.getSubject());
        assertEquals("<h1>节点响应超时</h1>", message.getText());
        assertTrue(message.isHtml());
        assertEquals("1", message.getHeaders().get("X-Priority"));
        assertTrue(message.hasAttachments());
        assertEquals(1, message.getAttachments().size());
        assertTrue(message.hasInlines());
        assertEquals(1, message.getInlines().size());
    }

    @Test
    @DisplayName("测试 MailMessage 确定性级联资源释放")
    void testCascadingClose() {
        AtomicInteger closedCount = new AtomicInteger(0);

        MailAttachment attachment1 = MailAttachment.of("a.txt", new ByteArrayResource("1".getBytes()))
                .onCleanup(closedCount::incrementAndGet);
        MailAttachment attachment2 = MailAttachment.of("b.txt", new ByteArrayResource("2".getBytes()))
                .onCleanup(closedCount::incrementAndGet);

        MailInlineResource inline1 = MailInlineResource.of("img1", new ByteArrayResource("img".getBytes()), "image/png")
                .onCleanup(closedCount::incrementAndGet);

        MailMessage message = new MailMessage()
                .attach(attachment1)
                .attach(attachment2)
                .inline(inline1);

        assertEquals(2, message.getAttachments().size());
        assertEquals(1, message.getInlines().size());
        assertEquals(0, closedCount.get());

        // 执行 close
        message.close();

        assertEquals(3, closedCount.get(), "message.close() 必须可靠释放所有挂载的附件与内联资源");
        assertFalse(message.hasAttachments());
        assertFalse(message.hasInlines());
    }

    @Test
    @DisplayName("测试文本与HTML正文切换机制")
    void testTextAndHtmlSwitch() {
        MailMessage msg = new MailMessage();
        msg.text("纯文本通知");
        assertFalse(msg.isHtml());
        assertEquals("纯文本通知", msg.getText());

        msg.html("<b>富文本通知</b>");
        assertTrue(msg.isHtml());
        assertEquals("<b>富文本通知</b>", msg.getText());
    }
}

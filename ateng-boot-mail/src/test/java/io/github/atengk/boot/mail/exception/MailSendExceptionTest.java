package io.github.atengk.boot.mail.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 邮件发送异常体系测试用例
 *
 * @author Ateng
 * @since 2026-10-22
 */
class MailSendExceptionTest {

    @Test
    @DisplayName("测试异常构造与因果链保留")
    void testExceptionCreationAndCause() {
        IOException cause = new IOException("网络不可达");
        MailSendException ex = new MailSendException(MailResultCode.MAIL_SEND_ERROR, "发信异常", cause);

        assertEquals(50001, ex.getCode());
        assertEquals("发信异常", ex.getMsg());
        assertSame(cause, ex.getCause());
        assertSame(MailResultCode.MAIL_SEND_ERROR, ex.getResultCode());
    }

    @Test
    @DisplayName("测试缺省构造器与简易工厂方法")
    void testConvenienceConstructors() {
        MailSendException ex1 = new MailSendException(MailResultCode.MAIL_CONNECT_TIMEOUT);
        assertEquals(50002, ex1.getCode());

        MailSendException ex2 = new MailSendException("直接抛出失败", new RuntimeException("root"));
        assertEquals(50001, ex2.getCode());
        assertEquals("直接抛出失败", ex2.getMsg());
        assertNotNull(ex2.getCause());
    }
}

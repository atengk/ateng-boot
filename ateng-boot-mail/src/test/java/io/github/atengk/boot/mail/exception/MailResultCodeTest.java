package io.github.atengk.boot.mail.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 邮件状态码测试用例
 *
 * @author Ateng
 * @since 2026-10-22
 */
class MailResultCodeTest {

    @Test
    @DisplayName("测试状态码枚举定义与契约规范")
    void testResultCodeContract() {
        for (MailResultCode code : MailResultCode.values()) {
            assertNotNull(code.getCode());
            assertNotNull(code.getMsg());
            assertFalse(code.getMsg().isBlank());
        }

        assertEquals(50001, MailResultCode.MAIL_SEND_ERROR.getCode());
        assertEquals(50002, MailResultCode.MAIL_CONNECT_TIMEOUT.getCode());
        assertEquals(50003, MailResultCode.MAIL_AUTHENTICATION_ERROR.getCode());
        assertEquals(40001, MailResultCode.MAIL_RECIPIENT_EMPTY.getCode());
        assertEquals(40002, MailResultCode.MAIL_ACCOUNT_NOT_FOUND.getCode());
        assertEquals(40003, MailResultCode.MAIL_ATTACHMENT_SIZE_EXCEEDED.getCode());
    }
}

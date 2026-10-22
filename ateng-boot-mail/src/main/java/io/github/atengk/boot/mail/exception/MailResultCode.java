package io.github.atengk.boot.mail.exception;

import io.github.atengk.boot.core.code.IResultCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 邮件服务业务响应状态码枚举
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Getter
@AllArgsConstructor
public enum MailResultCode implements IResultCode {

    /**
     * 邮件发送失败
     */
    MAIL_SEND_ERROR(50001, "邮件发送失败"),

    /**
     * 邮件服务器连接超时
     */
    MAIL_CONNECT_TIMEOUT(50002, "邮件服务器连接超时"),

    /**
     * 邮件服务器身份认证失败
     */
    MAIL_AUTHENTICATION_ERROR(50003, "邮件服务器身份认证失败"),

    /**
     * 收件人列表不能为空
     */
    MAIL_RECIPIENT_EMPTY(40001, "收件人列表不能为空"),

    /**
     * 未找到指定的发信账号
     */
    MAIL_ACCOUNT_NOT_FOUND(40002, "未找到指定的发信账号"),

    /**
     * 邮件附件大小超出上限
     */
    MAIL_ATTACHMENT_SIZE_EXCEEDED(40003, "邮件附件大小超出上限"),

    /**
     * 邮件模板渲染失败
     */
    MAIL_TEMPLATE_RENDER_ERROR(40004, "邮件模板渲染失败");

    private final Integer code;
    private final String msg;
}

package io.github.atengk.boot.sample.mail.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Freemarker 渲染与退信/退订合规协议头演示请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComplianceFreemarkerRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 收件人电子邮箱
     */
    @NotBlank(message = "收件人电子邮箱不能为空")
    @Email(message = "收件人电子邮箱格式不合法")
    private String to;

    /**
     * 接收用户姓名/昵称
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 通知标题
     */
    @NotBlank(message = "通知标题不能为空")
    private String noticeTitle;

    /**
     * 单封邮件独立 SMTP Envelope Return-Path 退信地址
     */
    @NotBlank(message = "Return-Path 退信地址不能为空")
    @Email(message = "Return-Path 退信电子邮箱格式不合法")
    private String returnPath;

    /**
     * RFC 2369 List-Unsubscribe 一键退订标头值
     */
    @NotBlank(message = "List-Unsubscribe 标头不能为空")
    private String listUnsubscribe;

    /**
     * RFC 8058 List-Unsubscribe-Post 标头值（可选，默认 List-Unsubscribe=One-Click）
     */
    @Builder.Default
    private String listUnsubscribePost = "List-Unsubscribe=One-Click";
}

package io.github.atengk.boot.mail.template;

import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.model.MailSendResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 编程式动态账号发信会话操作门面
 * <p>
 * 封装已绑定受管 LRU 物理池化 Sender 的动态账号，提供对称完整的
 * {@link #send(MailMessage)}、函数式 DSL 及便捷文本/HTML/模板发信能力。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class DynamicMailSession {

    private final MailTemplate template;
    private final MailAccount account;

    public DynamicMailSession(MailTemplate template, MailAccount account) {
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.account = Objects.requireNonNull(account, "account must not be null");
    }

    /**
     * 获取绑定的受管动态发信账号实例
     *
     * @return 动态账号实体
     */
    public MailAccount getAccount() {
        return account;
    }

    /**
     * 基于强类型邮件消息模型发送邮件
     *
     * @param message 邮件消息模型
     * @return 发送结果回执
     */
    public MailSendResult send(MailMessage message) {
        Objects.requireNonNull(message, "MailMessage must not be null");
        return template.sendWithAccount(account, message);
    }

    /**
     * 基于函数式 DSL 发送邮件
     *
     * @param messageConfigurer 消息配置器闭包
     * @return 发送结果回执
     */
    public MailSendResult send(Consumer<MailMessage> messageConfigurer) {
        Objects.requireNonNull(messageConfigurer, "messageConfigurer must not be null");
        MailMessage message = new MailMessage();
        messageConfigurer.accept(message);
        return send(message);
    }

    /**
     * 发送纯文本邮件至单收件人
     *
     * @param to      收件人邮箱
     * @param subject 邮件主题
     * @param text    纯文本内容
     * @return 发送结果回执
     */
    public MailSendResult sendText(String to, String subject, String text) {
        return sendText(to != null ? List.of(to) : List.of(), subject, text);
    }

    /**
     * 发送纯文本邮件至多收件人
     *
     * @param to      收件人邮箱集合
     * @param subject 邮件主题
     * @param text    纯文本内容
     * @return 发送结果回执
     */
    public MailSendResult sendText(Collection<String> to, String subject, String text) {
        MailMessage message = new MailMessage()
                .to(to != null ? new ArrayList<>(to) : new ArrayList<>())
                .subject(subject)
                .text(text);
        return send(message);
    }

    /**
     * 发送富文本 HTML 邮件至单收件人
     *
     * @param to      收件人邮箱
     * @param subject 邮件主题
     * @param html    HTML 内容
     * @return 发送结果回执
     */
    public MailSendResult sendHtml(String to, String subject, String html) {
        return sendHtml(to != null ? List.of(to) : List.of(), subject, html);
    }

    /**
     * 发送富文本 HTML 邮件至多收件人
     *
     * @param to      收件人邮箱集合
     * @param subject 邮件主题
     * @param html    HTML 内容
     * @return 发送结果回执
     */
    public MailSendResult sendHtml(Collection<String> to, String subject, String html) {
        MailMessage message = new MailMessage()
                .to(to != null ? new ArrayList<>(to) : new ArrayList<>())
                .subject(subject)
                .html(html);
        return send(message);
    }

    /**
     * 发送动态模板渲染邮件至单收件人
     *
     * @param to           收件人邮箱
     * @param subject      邮件主题
     * @param templateName 模板名称
     * @param model        数据模型字典
     * @return 发送结果回执
     */
    public MailSendResult sendTemplate(String to, String subject, String templateName, Map<String, Object> model) {
        return sendTemplate(to != null ? List.of(to) : List.of(), subject, templateName, model);
    }

    /**
     * 发送动态模板渲染邮件至多收件人
     *
     * @param to           收件人邮箱集合
     * @param subject      邮件主题
     * @param templateName 模板名称
     * @param model        数据模型字典
     * @return 发送结果回执
     */
    public MailSendResult sendTemplate(Collection<String> to, String subject, String templateName, Map<String, Object> model) {
        String renderedHtml = template.getTemplateRenderer().render(templateName, model);
        return sendHtml(to, subject, renderedHtml);
    }

    // ==================== 受管非阻塞异步发信门面 ====================

    /**
     * 基于强类型邮件消息模型异步发送邮件
     *
     * @param message 邮件消息模型
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendAsync(MailMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("MailMessage must not be null");
        }
        return template.sendAsyncWithAccount(account, message);
    }

    /**
     * 基于函数式 DSL 异步发送邮件
     *
     * @param messageConfigurer 消息配置器闭包
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendAsync(Consumer<MailMessage> messageConfigurer) {
        if (messageConfigurer == null) {
            throw new IllegalArgumentException("messageConfigurer must not be null");
        }
        MailMessage message = new MailMessage();
        messageConfigurer.accept(message);
        return sendAsync(message);
    }

    /**
     * 异步发送纯文本邮件至单收件人
     *
     * @param to      收件人邮箱
     * @param subject 邮件主题
     * @param text    纯文本内容
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendTextAsync(String to, String subject, String text) {
        return sendTextAsync(to != null ? List.of(to) : List.of(), subject, text);
    }

    /**
     * 异步发送纯文本邮件至多收件人
     *
     * @param to      收件人邮箱集合
     * @param subject 邮件主题
     * @param text    纯文本内容
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendTextAsync(Collection<String> to, String subject, String text) {
        MailMessage message = new MailMessage()
                .to(to != null ? new ArrayList<>(to) : new ArrayList<>())
                .subject(subject)
                .text(text);
        return sendAsync(message);
    }

    /**
     * 异步发送富文本 HTML 邮件至单收件人
     *
     * @param to      收件人邮箱
     * @param subject 邮件主题
     * @param html    HTML 内容
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendHtmlAsync(String to, String subject, String html) {
        return sendHtmlAsync(to != null ? List.of(to) : List.of(), subject, html);
    }

    /**
     * 异步发送富文本 HTML 邮件至多收件人
     *
     * @param to      收件人邮箱集合
     * @param subject 邮件主题
     * @param html    HTML 内容
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendHtmlAsync(Collection<String> to, String subject, String html) {
        MailMessage message = new MailMessage()
                .to(to != null ? new ArrayList<>(to) : new ArrayList<>())
                .subject(subject)
                .html(html);
        return sendAsync(message);
    }

    /**
     * 异步发送动态模板渲染邮件至单收件人
     *
     * @param to           收件人邮箱
     * @param subject      邮件主题
     * @param templateName 模板名称
     * @param model        数据模型字典
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendTemplateAsync(String to, String subject, String templateName, Map<String, Object> model) {
        return sendTemplateAsync(to != null ? List.of(to) : List.of(), subject, templateName, model);
    }

    /**
     * 异步发送动态模板渲染邮件至多收件人
     *
     * @param to           收件人邮箱集合
     * @param subject      邮件主题
     * @param templateName 模板名称
     * @param model        数据模型字典
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendTemplateAsync(Collection<String> to, String subject, String templateName, Map<String, Object> model) {
        String renderedHtml = template.getTemplateRenderer().render(templateName, model);
        return sendHtmlAsync(to, subject, renderedHtml);
    }

    // ==================== 本地事务感知发信门面 ====================

    /**
     * 事务提交后发信（当且仅当当前 Spring 物理事务 Commit 成功后触发）
     *
     * @param message 邮件消息模型
     */
    public void sendAfterCommit(MailMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("MailMessage must not be null");
        }
        template.sendAfterCommitWithAccount(account, message);
    }

    /**
     * 事务提交后发信（函数式 DSL 方式）
     *
     * @param messageConfigurer 消息配置器
     */
    public void sendAfterCommit(Consumer<MailMessage> messageConfigurer) {
        if (messageConfigurer == null) {
            throw new IllegalArgumentException("messageConfigurer must not be null");
        }
        MailMessage message = new MailMessage();
        messageConfigurer.accept(message);
        sendAfterCommit(message);
    }

    /**
     * 事务提交后异步发信（当且仅当当前 Spring 物理事务 Commit 成功后在受管线程池中触发）
     *
     * @param message 邮件消息模型
     */
    public void sendAsyncAfterCommit(MailMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("MailMessage must not be null");
        }
        template.sendAsyncAfterCommitWithAccount(account, message);
    }

    /**
     * 事务提交后异步发信（函数式 DSL 方式）
     *
     * @param messageConfigurer 消息配置器
     */
    public void sendAsyncAfterCommit(Consumer<MailMessage> messageConfigurer) {
        if (messageConfigurer == null) {
            throw new IllegalArgumentException("messageConfigurer must not be null");
        }
        MailMessage message = new MailMessage();
        messageConfigurer.accept(message);
        sendAsyncAfterCommit(message);
    }

    public void sendTextAfterCommit(String to, String subject, String text) {
        sendTextAfterCommit(to != null ? List.of(to) : List.of(), subject, text);
    }

    public void sendTextAfterCommit(Collection<String> to, String subject, String text) {
        MailMessage message = new MailMessage()
                .to(to != null ? new ArrayList<>(to) : new ArrayList<>())
                .subject(subject)
                .text(text);
        sendAfterCommit(message);
    }

    public void sendHtmlAfterCommit(String to, String subject, String html) {
        sendHtmlAfterCommit(to != null ? List.of(to) : List.of(), subject, html);
    }

    public void sendHtmlAfterCommit(Collection<String> to, String subject, String html) {
        MailMessage message = new MailMessage()
                .to(to != null ? new ArrayList<>(to) : new ArrayList<>())
                .subject(subject)
                .html(html);
        sendAfterCommit(message);
    }

    public void sendTemplateAfterCommit(String to, String subject, String templateName, Map<String, Object> model) {
        sendTemplateAfterCommit(to != null ? List.of(to) : List.of(), subject, templateName, model);
    }

    public void sendTemplateAfterCommit(Collection<String> to, String subject, String templateName, Map<String, Object> model) {
        String renderedHtml = template.getTemplateRenderer().render(templateName, model);
        sendHtmlAfterCommit(to, subject, renderedHtml);
    }

    public void sendTextAsyncAfterCommit(String to, String subject, String text) {
        sendTextAsyncAfterCommit(to != null ? List.of(to) : List.of(), subject, text);
    }

    public void sendTextAsyncAfterCommit(Collection<String> to, String subject, String text) {
        MailMessage message = new MailMessage()
                .to(to != null ? new ArrayList<>(to) : new ArrayList<>())
                .subject(subject)
                .text(text);
        sendAsyncAfterCommit(message);
    }

    public void sendHtmlAsyncAfterCommit(String to, String subject, String html) {
        sendHtmlAsyncAfterCommit(to != null ? List.of(to) : List.of(), subject, html);
    }

    public void sendHtmlAsyncAfterCommit(Collection<String> to, String subject, String html) {
        MailMessage message = new MailMessage()
                .to(to != null ? new ArrayList<>(to) : new ArrayList<>())
                .subject(subject)
                .html(html);
        sendAsyncAfterCommit(message);
    }

    public void sendTemplateAsyncAfterCommit(String to, String subject, String templateName, Map<String, Object> model) {
        sendTemplateAsyncAfterCommit(to != null ? List.of(to) : List.of(), subject, templateName, model);
    }

    public void sendTemplateAsyncAfterCommit(Collection<String> to, String subject, String templateName, Map<String, Object> model) {
        String renderedHtml = template.getTemplateRenderer().render(templateName, model);
        sendHtmlAsyncAfterCommit(to, subject, renderedHtml);
    }
}

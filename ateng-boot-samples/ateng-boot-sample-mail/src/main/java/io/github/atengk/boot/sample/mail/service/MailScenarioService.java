package io.github.atengk.boot.sample.mail.service;

import io.github.atengk.boot.mail.event.MailAccountEvictEvent;
import io.github.atengk.boot.mail.model.BatchMailSendResult;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailAttachment;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.model.MailPriority;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.mail.template.MailTemplate;
import io.github.atengk.boot.sample.mail.dto.SendMailRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 邮件场景化业务服务
 * <p>
 * 演示 {@link MailTemplate} 的文本发信、多账号路由、动态模板渲染、流式附件挂载、
 * 受管异步发信与事务感知发信（{@code sendAfterCommit}）等多种业务场景。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Service
@RequiredArgsConstructor
public class MailScenarioService {

    private static final Logger log = LoggerFactory.getLogger(MailScenarioService.class);

    private final MailTemplate mailTemplate;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 发送简单纯文本邮件（默认通道）
     *
     * @param to      收件人
     * @param subject 主题
     * @param text    正文
     * @return 发信结果
     */
    public MailSendResult sendSimpleText(String to, String subject, String text) {
        log.info("Sending simple text email to [{}]", to);
        return mailTemplate.sendText(to, subject, text);
    }

    /**
     * 发送多账号路由邮件（切换指定运维 ops 通道）
     *
     * @param to      收件人
     * @param subject 主题
     * @param text    正文
     * @return 发信结果
     */
    public MailSendResult sendMultiAccountOps(String to, String subject, String text) {
        log.info("Sending ops channel email to [{}]", to);
        return mailTemplate.sendText("ops", to, subject, text);
    }

    /**
     * 发送动态 Thymeleaf 模板邮件
     *
     * @param to         收件人
     * @param subject    主题
     * @param username   用户名
     * @param verifyCode 动态验证码
     * @return 发信结果
     */
    public MailSendResult sendHtmlTemplate(String to, String subject, String username, String verifyCode) {
        log.info("Sending html template email to [{}]", to);
        Map<String, Object> model = new HashMap<>();
        model.put("username", username);
        model.put("code", verifyCode);
        return mailTemplate.sendTemplate(to, subject, "mail/verify-code", model);
    }

    /**
     * 发送带附件的邮件
     *
     * @param to       收件人
     * @param subject  主题
     * @param text     正文
     * @param fileName 附件文件名
     * @param content  附件字节数据
     * @return 发信结果
     */
    public MailSendResult sendWithAttachment(String to, String subject, String text, String fileName, byte[] content) {
        log.info("Sending email with attachment [{}] to [{}]", fileName, to);
        MailMessage message = new MailMessage()
                .to(to)
                .subject(subject)
                .text(text)
                .attachment(MailAttachment.of(fileName, content));
        return mailTemplate.send(message);
    }

    /**
     * 受管异步发信（依托 Core 线程池与全链路四维上下文自动透传）
     *
     * @param to      收件人
     * @param subject 主题
     * @param text    正文
     * @return 异步回执 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendAsync(String to, String subject, String text) {
        log.info("Sending async email to [{}]", to);
        return mailTemplate.sendTextAsync(to, subject, text);
    }

    /**
     * 本地事务感知发信（事务提交后同步触发，事务回滚则防幽灵取消）
     *
     * @param to      收件人
     * @param subject 主题
     * @param text    正文
     */
    @Transactional(rollbackFor = Exception.class)
    public void sendTransactional(String to, String subject, String text, boolean shouldRollback) {
        log.info("Triggering sendTransactional to [{}], will rollback: {}", to, shouldRollback);
        MailMessage message = new MailMessage()
                .to(to)
                .subject(subject)
                .text(text);
        mailTemplate.sendAfterCommit(message);

        if (shouldRollback) {
            throw new IllegalStateException("Simulated business transaction rollback");
        }
    }

    /**
     * 编程式动态临时账号发信（演示 LRU 物理池化复用与 DynamicMailSession 门面）
     *
     * @param account 动态账号模型
     * @param to      收件人
     * @param subject 主题
     * @param text    正文
     * @return 发信结果
     */
    public MailSendResult sendWithDynamicAccount(MailAccount account, String to, String subject, String text) {
        log.info("Sending email via dynamic account [{}] to [{}]", account.getName(), to);
        return mailTemplate.forDynamicAccount(account).sendText(to, subject, text);
    }

    /**
     * 指定发信请求模型发送邮件（结构化入参收敛，杜绝长参数数据泥团）
     *
     * @param request 发信请求参数
     * @return 发信结果
     */
    public MailSendResult sendByAccount(SendMailRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("SendMailRequest must not be null");
        }
        return sendByAccount(request.getAccount(), request.getTo(), request.getSubject(),
                request.getContent(), request.getFrom(), request.getFromName());
    }

    /**
     * 指定发信账号并支持动态覆盖发件人别名发送邮件（演示外部介质懒加载与双轨防张冠李戴）
     *
     * @param accountName 账号逻辑名称
     * @param to          收件人
     * @param subject     主题
     * @param text        正文
     * @param from        动态发件人邮箱（可为 null）
     * @param fromName    动态发件人中文昵称（可为 null）
     * @return 发信结果
     */
    public MailSendResult sendByAccount(String accountName, String to, String subject, String text, String from, String fromName) {
        log.info("Sending email via accountName [{}] to [{}], customFrom: [{}], customFromName: [{}]", accountName, to, from, fromName);
        MailMessage message = new MailMessage()
                .account(accountName)
                .to(to)
                .subject(subject)
                .text(text)
                .from(from)
                .fromName(fromName);
        return mailTemplate.send(message);
    }

    /**
     * 通过门面精准驱逐指定动态账号缓存
     *
     * @param accountName 账号逻辑名称
     * @return 被移除的动态账号模型，若不存在返回 null
     */
    public MailAccount evictDynamicAccount(String accountName) {
        log.info("Evicting dynamic account cache for [{}]", accountName);
        return mailTemplate.evictDynamicAccount(accountName);
    }

    /**
     * 通过门面清空全部动态账号缓存
     */
    public void clearDynamicAccounts() {
        log.info("Clearing all dynamic account caches");
        mailTemplate.clearDynamicAccounts();
    }

    /**
     * 广播发布 Spring 原生 MailAccountEvictEvent 领域事件（单机或分布式集群热刷新）
     *
     * @param accountName 待驱逐的账号名称（若为 null 则清空全部）
     */
    public void publishEvictEvent(String accountName) {
        log.info("Publishing MailAccountEvictEvent for accountName: [{}]", accountName);
        eventPublisher.publishEvent(new MailAccountEvictEvent(this, accountName));
    }

    /**
     * 批量个性化发送对账单/工资条通知（演示 sendBatch 高并发分片并发与 Fail-Safe 容错聚合）
     *
     * @param messages 邮件消息列表
     * @return 批量聚合发信回执
     */
    public BatchMailSendResult sendBatchPersonalized(List<MailMessage> messages) {
        log.info("Sending batch personalized emails: total={}", messages != null ? messages.size() : 0);
        return mailTemplate.sendBatch(messages);
    }

    /**
     * 发送多语言国际化验证码模板邮件
     *
     * @param to       目标邮箱
     * @param username 用户名
     * @param code     验证码
     * @param locale   目标多语言区域（如 Locale.US, Locale.SIMPLIFIED_CHINESE）
     * @return 发信结果
     */
    public MailSendResult sendLocalizedVerifyCode(String to, String username, String code, Locale locale) {
        log.info("Sending localized template email to [{}], locale: {}", to, locale);
        Map<String, Object> model = Map.of("username", username, "code", code);
        return mailTemplate.sendTemplate(to, "Verification Notice", "mail/i18n-verify-code", model, locale);
    }

    /**
     * 管理端 SMTP 物理连通性一键测试探针
     *
     * @param accountName 账号逻辑名称
     * @return 快速布尔测试结果
     */
    public boolean testConnection(String accountName) {
        log.info("Testing SMTP physical connection for account: [{}]", accountName);
        return mailTemplate.testConnection(accountName);
    }

    /**
     * 管理端 SMTP 物理连通性异常型强测试探针
     *
     * @param accountName 账号逻辑名称
     */
    public void testConnectionOrThrow(String accountName) {
        log.info("Testing SMTP physical connection or throw for account: [{}]", accountName);
        mailTemplate.testConnectionOrThrow(accountName);
    }

    /**
     * 发送高优先级紧急线上告警邮件（支持强类型 MailPriority、阅读回执与合规归档密送）
     *
     * @param to                 接收人
     * @param subject            主题
     * @param content            正文
     * @param priority           强类型优先级
     * @param requestReadReceipt 是否请求阅读回执
     * @param archiveBcc         合规归档密送（若指定）
     * @return 发信结果
     */
    public MailSendResult sendUrgentAlert(String to, String subject, String content,
                                         MailPriority priority, boolean requestReadReceipt, String archiveBcc) {
        log.info("Sending urgent alert email to [{}], priority: {}, requestReadReceipt: {}", to, priority, requestReadReceipt);
        MailMessage message = new MailMessage()
                .to(to)
                .subject(subject)
                .text(content)
                .priority(priority)
                .requestReadReceipt(requestReadReceipt);
        if (archiveBcc != null && !archiveBcc.isBlank()) {
            message.bcc(archiveBcc);
        }
        return mailTemplate.send(message);
    }
}

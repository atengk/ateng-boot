package io.github.atengk.boot.mail.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.core.io.InputStreamSource;
import org.springframework.core.io.Resource;

import java.io.File;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 邮件领域消息强类型模型
 * <p>
 * 结构化封装发件账号路由标识、发件人、收件人列表（To/Cc/Bcc）、主题、纯文本/HTML 正文、
 * 流式附件集合（{@link MailAttachment}）、正交内联资源（{@link MailInlineResource}）及自定义邮件标头，
 * 支持链式 Fluent 与函数式 DSL 构造，并实现 {@link AutoCloseable} 以保障流式资源的绝对清理。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailMessage implements AutoCloseable, Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 发信账号路由标识（若为空则使用默认发信账号）
     */
    private String account;

    /**
     * 发件人邮箱地址（若为空则使用所选发信账号的默认发件地址）
     */
    private String from;

    /**
     * 发件人中文友好昵称
     */
    private String fromName;

    /**
     * 目标收件人邮箱列表 (To)
     */
    @Builder.Default
    private List<String> to = new ArrayList<>();

    /**
     * 抄送人邮箱列表 (Cc)
     */
    @Builder.Default
    private List<String> cc = new ArrayList<>();

    /**
     * 密送人邮箱列表 (Bcc)
     */
    @Builder.Default
    private List<String> bcc = new ArrayList<>();

    /**
     * 回复邮箱地址 (Reply-To)
     */
    private String replyTo;

    /**
     * 邮件主题
     */
    private String subject;

    /**
     * 邮件正文（纯文本或 HTML 内容）
     */
    private String text;

    /**
     * 正文是否为 HTML 格式
     */
    private boolean html;

    /**
     * 自定义邮件标头键值对
     */
    @Builder.Default
    private Map<String, String> headers = new LinkedHashMap<>();

    /**
     * 流式附件集合
     */
    @Builder.Default
    private List<MailAttachment> attachments = new ArrayList<>();

    /**
     * 正交内联资源集合 (Inline CID)
     */
    @Builder.Default
    private List<MailInlineResource> inlines = new ArrayList<>();

    // ==================== 链式 Fluent / DSL 构造辅助方法 ====================

    /**
     * 指定发信账号
     *
     * @param account 账号逻辑名称
     * @return 当前消息构建对象
     */
    public MailMessage account(String account) {
        this.account = account;
        return this;
    }

    /**
     * 指定发件人邮箱
     *
     * @param from 发件人邮箱
     * @return 当前消息构建对象
     */
    public MailMessage from(String from) {
        this.from = from;
        return this;
    }

    /**
     * 指定发件人邮箱与发件人昵称
     *
     * @param from     发件人邮箱
     * @param fromName 发件人昵称
     * @return 当前消息构建对象
     */
    public MailMessage from(String from, String fromName) {
        this.from = from;
        this.fromName = fromName;
        return this;
    }

    /**
     * 指定发件人昵称
     *
     * @param fromName 发件人昵称
     * @return 当前消息构建对象
     */
    public MailMessage fromName(String fromName) {
        this.fromName = fromName;
        return this;
    }

    /**
     * 添加收件人
     *
     * @param to 收件人邮箱（可变参）
     * @return 当前消息构建对象
     */
    public MailMessage to(String... to) {
        if (to != null) {
            this.to.addAll(Arrays.stream(to).filter(Objects::nonNull).toList());
        }
        return this;
    }

    /**
     * 添加收件人集合
     *
     * @param to 收件人集合
     * @return 当前消息构建对象
     */
    public MailMessage to(Collection<String> to) {
        if (to != null) {
            this.to.addAll(to.stream().filter(Objects::nonNull).toList());
        }
        return this;
    }

    /**
     * 添加抄送人
     *
     * @param cc 抄送人邮箱（可变参）
     * @return 当前消息构建对象
     */
    public MailMessage cc(String... cc) {
        if (cc != null) {
            this.cc.addAll(Arrays.stream(cc).filter(Objects::nonNull).toList());
        }
        return this;
    }

    /**
     * 添加抄送人集合
     *
     * @param cc 抄送人集合
     * @return 当前消息构建对象
     */
    public MailMessage cc(Collection<String> cc) {
        if (cc != null) {
            this.cc.addAll(cc.stream().filter(Objects::nonNull).toList());
        }
        return this;
    }

    /**
     * 添加密送人
     *
     * @param bcc 密送人邮箱（可变参）
     * @return 当前消息构建对象
     */
    public MailMessage bcc(String... bcc) {
        if (bcc != null) {
            this.bcc.addAll(Arrays.stream(bcc).filter(Objects::nonNull).toList());
        }
        return this;
    }

    /**
     * 添加密送人集合
     *
     * @param bcc 密送人集合
     * @return 当前消息构建对象
     */
    public MailMessage bcc(Collection<String> bcc) {
        if (bcc != null) {
            this.bcc.addAll(bcc.stream().filter(Objects::nonNull).toList());
        }
        return this;
    }

    /**
     * 指定回复邮箱
     *
     * @param replyTo 回复邮箱
     * @return 当前消息构建对象
     */
    public MailMessage replyTo(String replyTo) {
        this.replyTo = replyTo;
        return this;
    }

    /**
     * 指定邮件主题
     *
     * @param subject 邮件主题
     * @return 当前消息构建对象
     */
    public MailMessage subject(String subject) {
        this.subject = subject;
        return this;
    }

    /**
     * 指定纯文本邮件正文
     *
     * @param text 文本正文
     * @return 当前消息构建对象
     */
    public MailMessage text(String text) {
        this.text = text;
        this.html = false;
        return this;
    }

    /**
     * 指定 HTML 富文本邮件正文
     *
     * @param html HTML 内容
     * @return 当前消息构建对象
     */
    public MailMessage html(String html) {
        this.text = html;
        this.html = true;
        return this;
    }

    /**
     * 添加自定义邮件标头
     *
     * @param name  标头名称
     * @param value 标头值
     * @return 当前消息构建对象
     */
    public MailMessage header(String name, String value) {
        if (name != null && !name.isBlank() && value != null) {
            this.headers.put(name, value);
        }
        return this;
    }

    /**
     * 批量添加邮件标头
     *
     * @param headers 标头映射
     * @return 当前消息构建对象
     */
    public MailMessage headers(Map<String, String> headers) {
        if (headers != null) {
            this.headers.putAll(headers);
        }
        return this;
    }

    /**
     * 添加流式附件
     *
     * @param attachment 附件对象
     * @return 当前消息构建对象
     */
    public MailMessage attach(MailAttachment attachment) {
        if (attachment != null) {
            this.attachments.add(attachment);
        }
        return this;
    }

    /**
     * 添加流式附件（attach 别名门面）
     *
     * @param attachment 附件对象
     * @return 当前消息构建对象
     */
    public MailMessage attachment(MailAttachment attachment) {
        return attach(attachment);
    }

    /**
     * 基于本地文件添加附件
     *
     * @param filename 显示文件名
     * @param file     本地文件
     * @return 当前消息构建对象
     */
    public MailMessage attach(String filename, File file) {
        return attach(MailAttachment.of(filename, file));
    }

    /**
     * 基于字节数组添加附件
     *
     * @param filename 显示文件名
     * @param bytes    字节数据
     * @return 当前消息构建对象
     */
    public MailMessage attach(String filename, byte[] bytes) {
        return attach(MailAttachment.of(filename, bytes));
    }

    /**
     * 基于字节数组与指定 MIME 类型添加附件
     *
     * @param filename    显示文件名
     * @param bytes       字节数据
     * @param contentType MIME 类型
     * @return 当前消息构建对象
     */
    public MailMessage attach(String filename, byte[] bytes, String contentType) {
        return attach(MailAttachment.of(filename, bytes, contentType));
    }

    /**
     * 基于流式数据源添加附件
     *
     * @param filename 显示文件名
     * @param source   流式数据源
     * @return 当前消息构建对象
     */
    public MailMessage attach(String filename, InputStreamSource source) {
        return attach(MailAttachment.of(filename, source));
    }

    /**
     * 基于流式数据源与指定 MIME 类型添加附件
     *
     * @param filename    显示文件名
     * @param source      流式数据源
     * @param contentType MIME 类型
     * @return 当前消息构建对象
     */
    public MailMessage attach(String filename, InputStreamSource source, String contentType) {
        return attach(MailAttachment.of(filename, source, contentType));
    }

    /**
     * 基于 Spring Resource 添加附件
     *
     * @param filename 显示文件名
     * @param resource 资源对象
     * @return 当前消息构建对象
     */
    public MailMessage attach(String filename, Resource resource) {
        return attach(MailAttachment.of(filename, resource));
    }

    /**
     * 基于原始 InputStream 添加附件
     *
     * @param filename    显示文件名
     * @param inputStream 输入流
     * @return 当前消息构建对象
     */
    public MailMessage attach(String filename, InputStream inputStream) {
        return attach(MailAttachment.of(filename, inputStream));
    }

    /**
     * 添加正交内联资源 (Inline CID)
     *
     * @param inlineResource 内联资源对象
     * @return 当前消息构建对象
     */
    public MailMessage inline(MailInlineResource inlineResource) {
        if (inlineResource != null) {
            this.inlines.add(inlineResource);
        }
        return this;
    }

    /**
     * 基于本地文件添加内联资源
     *
     * @param contentId Content-ID（HTML 中通过 cid:xxx 引用）
     * @param file      本地文件
     * @return 当前消息构建对象
     */
    public MailMessage inline(String contentId, File file) {
        return inline(MailInlineResource.of(contentId, file));
    }

    /**
     * 基于字节数组添加内联资源
     *
     * @param contentId   Content-ID
     * @param bytes       字节数据
     * @param contentType MIME 类型
     * @return 当前消息构建对象
     */
    public MailMessage inline(String contentId, byte[] bytes, String contentType) {
        return inline(MailInlineResource.of(contentId, bytes, contentType));
    }

    /**
     * 基于流式数据源添加内联资源
     *
     * @param contentId   Content-ID
     * @param source      流式数据源
     * @param contentType MIME 类型
     * @return 当前消息构建对象
     */
    public MailMessage inline(String contentId, InputStreamSource source, String contentType) {
        return inline(MailInlineResource.of(contentId, source, contentType));
    }

    /**
     * 基于 Spring Resource 添加内联资源
     *
     * @param contentId Content-ID
     * @param resource  资源对象
     * @return 当前消息构建对象
     */
    public MailMessage inline(String contentId, Resource resource) {
        return inline(MailInlineResource.of(contentId, resource));
    }

    /**
     * 是否挂载了附件
     *
     * @return true 若包含附件
     */
    public boolean hasAttachments() {
        return attachments != null && !attachments.isEmpty();
    }

    /**
     * 是否挂载了内联资源
     *
     * @return true 若包含内联资源
     */
    public boolean hasInlines() {
        return inlines != null && !inlines.isEmpty();
    }

    /**
     * 执行确定性资源清理，可靠释放所有附件与内联资源占用的句柄与临时流
     */
    @Override
    public void close() {
        if (attachments != null) {
            for (MailAttachment attachment : attachments) {
                if (attachment != null) {
                    try {
                        attachment.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            attachments.clear();
        }
        if (inlines != null) {
            for (MailInlineResource inline : inlines) {
                if (inline != null) {
                    try {
                        inline.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            inlines.clear();
        }
    }
}

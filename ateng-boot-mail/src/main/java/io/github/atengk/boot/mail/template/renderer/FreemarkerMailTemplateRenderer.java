package io.github.atengk.boot.mail.template.renderer;

import freemarker.template.Configuration;
import freemarker.template.Template;
import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;

import java.io.StringWriter;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Freemarker 邮件模板动态渲染适配器
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class FreemarkerMailTemplateRenderer implements MailTemplateRenderer {

    private final Configuration configuration;

    public FreemarkerMailTemplateRenderer(Configuration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
    }

    @Override
    public String render(String templateName, Map<String, Object> model) {
        return render(templateName, model, null);
    }

    @Override
    public String render(String templateName, Map<String, Object> model, Locale locale) {
        if (templateName == null || templateName.isBlank()) {
            throw new MailSendException(MailResultCode.MAIL_TEMPLATE_RENDER_ERROR, "模板名称不能为空");
        }

        try {
            String tplName = (templateName.endsWith(".ftl") || templateName.endsWith(".html") || templateName.endsWith(".ftlh"))
                    ? templateName
                    : templateName + ".ftl";
            Template template = (locale != null)
                    ? configuration.getTemplate(tplName, locale)
                    : configuration.getTemplate(tplName);
            StringWriter writer = new StringWriter();
            template.process(model, writer);
            return writer.toString();
        } catch (Exception e) {
            throw new MailSendException(MailResultCode.MAIL_TEMPLATE_RENDER_ERROR,
                    "Freemarker 邮件模板渲染失败: [" + templateName + "]: " + e.getMessage(), e);
        }
    }
}

package io.github.atengk.boot.mail.template.renderer;

import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Thymeleaf 邮件模板动态渲染适配器
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class ThymeleafMailTemplateRenderer implements MailTemplateRenderer {

    private final SpringTemplateEngine templateEngine;

    public ThymeleafMailTemplateRenderer(SpringTemplateEngine templateEngine) {
        this.templateEngine = Objects.requireNonNull(templateEngine, "templateEngine must not be null");
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
            Context context = (locale != null) ? new Context(locale) : new Context();
            if (model != null) {
                context.setVariables(model);
            }
            return templateEngine.process(templateName, context);
        } catch (Exception e) {
            throw new MailSendException(MailResultCode.MAIL_TEMPLATE_RENDER_ERROR,
                    "Thymeleaf 邮件模板渲染失败: [" + templateName + "]: " + e.getMessage(), e);
        }
    }
}

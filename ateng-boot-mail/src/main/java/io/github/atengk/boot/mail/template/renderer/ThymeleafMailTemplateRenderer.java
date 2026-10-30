package io.github.atengk.boot.mail.template.renderer;

import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import org.springframework.core.io.ClassPathResource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.ArrayList;
import java.util.List;
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
            String resolvedTemplate = resolveTemplateName(templateName, locale);
            return templateEngine.process(resolvedTemplate, context);
        } catch (Exception e) {
            throw new MailSendException(MailResultCode.MAIL_TEMPLATE_RENDER_ERROR,
                    "Thymeleaf 邮件模板渲染失败: [" + templateName + "]: " + e.getMessage(), e);
        }
    }

    private String resolveTemplateName(String templateName, Locale locale) {
        if (locale == null) {
            return templateName;
        }

        String base = templateName;
        String ext = "";
        if (templateName.endsWith(".html")) {
            base = templateName.substring(0, templateName.length() - 5);
            ext = ".html";
        }

        String lang = locale.getLanguage();
        String country = locale.getCountry();

        List<String> candidates = new ArrayList<>();
        if (country != null && !country.isBlank()) {
            candidates.add(base + "_" + lang + "_" + country + ext);
        }
        if (lang != null && !lang.isBlank()) {
            candidates.add(base + "_" + lang + ext);
        }

        for (String candidate : candidates) {
            if (templateExists(candidate)) {
                return candidate;
            }
        }
        return templateName;
    }

    private boolean templateExists(String templateName) {
        String path = templateName.endsWith(".html") ? templateName : templateName + ".html";
        String[] probePaths = new String[]{
                path,
                "templates/" + path,
                "templates/mail/" + path,
                "mail/" + path
        };
        for (String probe : probePaths) {
            if (new ClassPathResource(probe).exists()) {
                return true;
            }
        }
        return false;
    }
}

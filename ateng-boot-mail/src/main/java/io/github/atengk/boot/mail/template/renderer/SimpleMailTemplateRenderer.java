package io.github.atengk.boot.mail.template.renderer;

import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯原生零外部依赖的轻量占位符模板渲染器
 * 作为默认保底实现，支持 ${variable} 与 ${user.name} 嵌套路径变量替换
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class SimpleMailTemplateRenderer implements MailTemplateRenderer {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{\\s*([a-zA-Z0-9_.-]+)\\s*\\}");

    @Override
    public String render(String templateName, Map<String, Object> model) {
        return render(templateName, model, null);
    }

    @Override
    public String render(String templateName, Map<String, Object> model, Locale locale) {
        if (templateName == null || templateName.isBlank()) {
            throw new MailSendException(MailResultCode.MAIL_TEMPLATE_RENDER_ERROR, "模板名称不能为空");
        }

        String templateContent = loadTemplate(templateName, locale);
        if (model == null || model.isEmpty()) {
            return templateContent;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(templateContent);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            Object value = resolveValue(placeholder, model);
            String replacement = value != null ? Matcher.quoteReplacement(String.valueOf(value)) : "";
            matcher.appendReplacement(sb, replacement);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String loadTemplate(String templateName, Locale locale) {
        // 1. 若字符串直接包含 HTML 标签、换行符或 ${ 占位符且不以常见模板文件后缀结尾，可直接作为内联模板内容解析
        if ((templateName.contains("${") || templateName.contains("<") || templateName.contains("\n"))
                && !templateName.endsWith(".html") && !templateName.endsWith(".htm") && !templateName.endsWith(".ftl")) {
            return templateName;
        }

        // 2. 依次尝试查找类路径下的模板资源（支持国际化级联探测：template_en_US.html -> template_en.html -> template.html）
        String base = templateName;
        String ext = ".html";
        if (templateName.endsWith(".html")) {
            base = templateName.substring(0, templateName.length() - 5);
            ext = ".html";
        } else if (templateName.endsWith(".htm")) {
            base = templateName.substring(0, templateName.length() - 4);
            ext = ".htm";
        } else if (templateName.endsWith(".ftl")) {
            base = templateName.substring(0, templateName.length() - 4);
            ext = ".ftl";
        }

        List<String> candidateNames = new ArrayList<>();
        if (locale != null) {
            if (locale.getCountry() != null && !locale.getCountry().isBlank()) {
                candidateNames.add(base + "_" + locale.getLanguage() + "_" + locale.getCountry() + ext);
            }
            if (locale.getLanguage() != null && !locale.getLanguage().isBlank()) {
                candidateNames.add(base + "_" + locale.getLanguage() + ext);
            }
        }
        candidateNames.add(base + ext);
        if (!candidateNames.contains(templateName)) {
            candidateNames.add(templateName);
        }

        for (String candidate : candidateNames) {
            String[] candidatePaths = new String[]{
                    candidate,
                    "templates/mail/" + candidate,
                    "templates/" + candidate,
                    "mail/" + candidate
            };

            for (String path : candidatePaths) {
                Resource resource = new ClassPathResource(path);
                if (resource.exists()) {
                    try (InputStream in = resource.getInputStream()) {
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        throw new MailSendException(MailResultCode.MAIL_TEMPLATE_RENDER_ERROR,
                                "读取类路径邮件模板失败: [" + path + "]", e);
                    }
                }
            }
        }

        // 3. 若类路径未找到但包含占位符，支持纯字符串模板
        if (templateName.contains("${")) {
            return templateName;
        }

        throw new MailSendException(MailResultCode.MAIL_TEMPLATE_RENDER_ERROR,
                "未找到邮件模板: [" + templateName + "]，请检查 templates/mail/ 或 mail/ 路径下是否存在对应文件");
    }

    private Object resolveValue(String key, Map<String, Object> model) {
        if (model.containsKey(key)) {
            return model.get(key);
        }
        if (!key.contains(".")) {
            return null;
        }

        String[] parts = key.split("\\.");
        Object current = model;
        for (String part : parts) {
            if (current == null) {
                return null;
            }
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                current = getProperty(current, part);
            }
        }
        return current;
    }

    private Object getProperty(Object bean, String propertyName) {
        try {
            String getterName = "get" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
            return bean.getClass().getMethod(getterName).invoke(bean);
        } catch (Exception ignored) {
            try {
                String isName = "is" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
                return bean.getClass().getMethod(isName).invoke(bean);
            } catch (Exception ex) {
                return null;
            }
        }
    }
}

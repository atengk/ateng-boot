package io.github.atengk.boot.mail.template.renderer;

import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
        if (templateName == null || templateName.isBlank()) {
            throw new MailSendException(MailResultCode.MAIL_TEMPLATE_RENDER_ERROR, "模板名称不能为空");
        }

        String templateContent = loadTemplate(templateName);
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

    private String loadTemplate(String templateName) {
        // 1. 若字符串直接包含 HTML 标签、换行符或 ${ 占位符且不以常见模板文件后缀结尾，可直接作为内联模板内容解析
        if ((templateName.contains("${") || templateName.contains("<") || templateName.contains("\n"))
                && !templateName.endsWith(".html") && !templateName.endsWith(".htm") && !templateName.endsWith(".ftl")) {
            return templateName;
        }

        // 2. 依次尝试查找类路径下的模板资源
        String suffix = templateName.endsWith(".html") || templateName.endsWith(".htm") ? "" : ".html";
        String[] candidatePaths = new String[]{
                templateName,
                templateName + suffix,
                "templates/mail/" + templateName + suffix,
                "templates/" + templateName + suffix,
                "mail/" + templateName + suffix
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

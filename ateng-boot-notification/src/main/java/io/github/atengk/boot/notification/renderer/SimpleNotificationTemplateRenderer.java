package io.github.atengk.boot.notification.renderer;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯原生轻量占位符插值渲染器
 * 支持 ${variable} 格式的极简文本安全替换，杜绝第三方重型模板依赖
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class SimpleNotificationTemplateRenderer implements NotificationTemplateRenderer {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([a-zA-Z0-9_.-]+)\\}");

    @Override
    public String render(String templateContent, Map<String, Object> variables) {
        if (templateContent == null || templateContent.isBlank() || variables == null || variables.isEmpty()) {
            return templateContent;
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(templateContent);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (variables.containsKey(key)) {
                Object val = variables.get(key);
                String replacement = (val != null) ? Matcher.quoteReplacement(String.valueOf(val)) : "";
                matcher.appendReplacement(sb, replacement);
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}

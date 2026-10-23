package io.github.atengk.boot.document.pdf;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF 防泄密水印动态宏变量解析器
 * <p>
 * 支持 #{user.nickname}、#{user.username}、#{user.phone}（自动手机号掩码脱敏）、
 * #{traceId}、#{now('yyyy-MM-dd')} 以及带有 !'默认值' 的语法表达式。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public final class WatermarkMacroParser {

    private static final Pattern MACRO_PATTERN = Pattern.compile("#\\{([^}]+)\\}");
    private static final Pattern NOW_PATTERN = Pattern.compile("^now(?:\\(['\"]?([^'\"]*)['\"]?\\))?$");

    private WatermarkMacroParser() {
    }

    /**
     * 解析并替换水印模板中的动态宏变量
     *
     * @param templateString 水印模版字符串
     * @param variables      外部传入的变量字典（可为 null）
     * @return 替换解析后的实际渲染文本
     */
    public static String parse(String templateString, Map<String, Object> variables) {
        if (templateString == null || templateString.isEmpty()) {
            return "";
        }

        Matcher matcher = MACRO_PATTERN.matcher(templateString);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String rawExpr = matcher.group(1).trim();
            String resolvedValue = resolveExpression(rawExpr, variables);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(resolvedValue != null ? resolvedValue : ""));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String resolveExpression(String rawExpr, Map<String, Object> variables) {
        String expr = rawExpr;
        String defaultValue = "";

        // 解析默认值语法，例如: user.nickname!'未知' 或 user.nickname!''
        int defaultIdx = rawExpr.indexOf('!');
        if (defaultIdx >= 0) {
            expr = rawExpr.substring(0, defaultIdx).trim();
            String rawDefault = rawExpr.substring(defaultIdx + 1).trim();
            if ((rawDefault.startsWith("'") && rawDefault.endsWith("'")) ||
                    (rawDefault.startsWith("\"") && rawDefault.endsWith("\""))) {
                defaultValue = rawDefault.substring(1, rawDefault.length() - 1);
            } else {
                defaultValue = rawDefault;
            }
        }

        // 1. 处理时间函数 #{now('pattern')}
        Matcher nowMatcher = NOW_PATTERN.matcher(expr);
        if (nowMatcher.matches()) {
            String pattern = nowMatcher.group(1);
            if (pattern == null || pattern.trim().isEmpty()) {
                pattern = "yyyy-MM-dd";
            }
            try {
                return LocalDateTime.now().format(DateTimeFormatter.ofPattern(pattern.trim()));
            } catch (Exception e) {
                return defaultValue;
            }
        }

        // 2. 处理 traceId
        if ("traceId".equals(expr) || "trace_id".equals(expr)) {
            Object val = getVariableValue("traceId", variables);
            if (val == null) {
                val = getVariableValue("trace_id", variables);
            }
            if (val == null) {
                val = MDC.get("traceId");
            }
            if (val == null) {
                val = MDC.get("trace_id");
            }
            return val != null ? String.valueOf(val) : defaultValue;
        }

        // 3. 处理 user.* 变量
        if (expr.startsWith("user.")) {
            String attr = expr.substring(5).trim();
            Object userObj = getVariableValue("user", variables);
            String attrVal = extractUserAttribute(userObj, attr);

            // 若上下文无 userMap，保底从 UserContextHolder 中获取
            if ((attrVal == null || attrVal.isEmpty()) && ("username".equals(attr) || "nickname".equals(attr))) {
                String ctxUser = UserContextHolder.get();
                if (ctxUser != null && !ctxUser.isBlank()) {
                    attrVal = ctxUser;
                }
            }

            if (attrVal != null && !attrVal.isEmpty()) {
                // 若属性为手机号，自动执行脱敏掩码
                if ("phone".equalsIgnoreCase(attr) || "mobile".equalsIgnoreCase(attr)) {
                    return DesensitizeUtil.mobilePhone(attrVal);
                }
                return attrVal;
            }
            return defaultValue;
        }

        // 4. 处理自定义普通变量
        Object customVal = getVariableValue(expr, variables);
        return customVal != null ? String.valueOf(customVal) : defaultValue;
    }

    private static Object getVariableValue(String key, Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            return null;
        }
        return variables.get(key);
    }

    @SuppressWarnings("unchecked")
    private static String extractUserAttribute(Object userObj, String attr) {
        if (userObj == null) {
            return null;
        }
        if (userObj instanceof Map<?, ?> map) {
            Object val = map.get(attr);
            return val != null ? String.valueOf(val) : null;
        }
        try {
            // 反射尝试获取 getter 方法
            String getterName = "get" + Character.toUpperCase(attr.charAt(0)) + attr.substring(1);
            var method = userObj.getClass().getMethod(getterName);
            Object res = method.invoke(userObj);
            return res != null ? String.valueOf(res) : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}

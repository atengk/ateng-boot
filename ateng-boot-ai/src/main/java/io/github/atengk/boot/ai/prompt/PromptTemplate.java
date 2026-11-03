package io.github.atengk.boot.ai.prompt;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 声明式轻量参数插值提示词模板
 * 支持 {{var}} 形式的动态参数安全替换，纯原生高效正则实现，零额外第三方模板引擎依赖
 *
 * @author Ateng
 * @since 2026-11-03
 */
public final class PromptTemplate {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*\\}\\}");

    private final String template;
    private final Set<String> variableNames;

    private PromptTemplate(String template) {
        if (!StringUtils.hasText(template)) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "提示词模板内容不能为空");
        }
        this.template = template;
        this.variableNames = extractVariableNames(template);
    }

    /**
     * 静态工厂：创建提示词模板实例
     *
     * @param template 包含 {{var}} 占位符的模板字符串
     * @return PromptTemplate 实例
     */
    public static PromptTemplate of(String template) {
        return new PromptTemplate(template);
    }

    /**
     * 根据键值对变量集合渲染提示词
     *
     * @param variables 变量键值映射
     * @return 渲染完成的提示词字符串
     */
    public String render(Map<String, ?> variables) {
        if (variables == null || variables.isEmpty()) {
            return template;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String varName = matcher.group(1);
            if (variables.containsKey(varName)) {
                Object val = variables.get(varName);
                String replacement = val != null ? Matcher.quoteReplacement(String.valueOf(val)) : "";
                matcher.appendReplacement(sb, replacement);
            } else {
                matcher.appendReplacement(sb, matcher.group(0));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 变长参数渲染语法糖 (key, value, key, value, ...)
     *
     * @param keyValues 偶数个键值对参数
     * @return 渲染完成的提示词字符串
     */
    public String render(Object... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            return template;
        }
        if (keyValues.length % 2 != 0) {
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "变长参数键值对必须成对提供，当前参数个数: " + keyValues.length);
        }

        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = String.valueOf(keyValues[i]);
            Object value = keyValues[i + 1];
            map.put(key, value);
        }
        return render(map);
    }

    /**
     * 获取原始模板内容
     *
     * @return 模板字符串
     */
    public String getTemplate() {
        return template;
    }

    /**
     * 获取模板中解析出的所有占位符变量名集合
     *
     * @return 只读变量名集合
     */
    public Set<String> getVariableNames() {
        return Collections.unmodifiableSet(variableNames);
    }

    private static Set<String> extractVariableNames(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    @Override
    public String toString() {
        return template;
    }
}

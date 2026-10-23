package io.github.atengk.boot.mqtt.routing;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MQTT 主题模式编译器与 REST 风格路径变量解析器
 * 将形如 {@code device/{deviceId}/metric/{metricName}} 的声明式模式编译为标准 MQTT 订阅通配符，
 * 并支持从物理推送主题中快速提取路径变量映射
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class MqttTopicPattern {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("^\\{([a-zA-Z0-9_-]+)\\}$");

    private final String rawPattern;
    private final String mqttFilter;
    private final String[] patternSegments;
    private final boolean endsWithMultiWildcard;
    private final List<VariableEntry> variableEntries;

    private record VariableEntry(int segmentIndex, String variableName) {}

    public MqttTopicPattern(String rawPattern) {
        this.rawPattern = Objects.requireNonNull(rawPattern, "rawPattern must not be null").trim();
        if (!StringUtils.hasText(this.rawPattern)) {
            throw new IllegalArgumentException("MQTT topic pattern must not be blank");
        }

        this.patternSegments = this.rawPattern.split("/", -1);
        this.endsWithMultiWildcard = patternSegments.length > 0 && "#".equals(patternSegments[patternSegments.length - 1]);

        StringBuilder mqttFilterBuilder = new StringBuilder();
        List<VariableEntry> entries = new ArrayList<>();

        for (int i = 0; i < patternSegments.length; i++) {
            String segment = patternSegments[i];
            if (i > 0) {
                mqttFilterBuilder.append("/");
            }
            Matcher matcher = VARIABLE_PATTERN.matcher(segment);
            if (matcher.matches()) {
                String varName = matcher.group(1);
                entries.add(new VariableEntry(i, varName));
                mqttFilterBuilder.append("+");
            } else {
                mqttFilterBuilder.append(segment);
            }
        }

        this.mqttFilter = mqttFilterBuilder.toString();
        this.variableEntries = Collections.unmodifiableList(entries);
    }

    /**
     * 获取用户声明的原始主题模式字符串
     *
     * @return 原始模式
     */
    public String getRawPattern() {
        return rawPattern;
    }

    /**
     * 获取编译后的标准 MQTT 订阅主题过滤器（占位符已替换为 {@code +}）
     *
     * @return 标准 MQTT 订阅主题过滤器
     */
    public String getMqttFilter() {
        return mqttFilter;
    }

    /**
     * 获取带有共享订阅前缀的标准 MQTT 订阅主题过滤器
     *
     * @param sharedGroup 共享订阅组名，若为空则直接返回普通过滤器
     * @return 共享订阅主题过滤器
     */
    public String getSharedMqttFilter(String sharedGroup) {
        if (StringUtils.hasText(sharedGroup)) {
            return "$share/" + sharedGroup.trim() + "/" + mqttFilter;
        }
        return mqttFilter;
    }

    /**
     * 判断实际主题是否与当前模式匹配，若匹配则提取所有路径变量
     *
     * @param actualTopic 实际接收到的物理主题
     * @return 提取的变量键值对映射，若不匹配则返回 null
     */
    public Map<String, String> matchAndExtract(String actualTopic) {
        if (!StringUtils.hasText(actualTopic)) {
            return null;
        }
        String[] actualSegments = actualTopic.split("/", -1);

        if (!endsWithMultiWildcard && actualSegments.length != patternSegments.length) {
            return null;
        }
        if (endsWithMultiWildcard && actualSegments.length < patternSegments.length - 1) {
            return null;
        }

        int checkLength = endsWithMultiWildcard ? patternSegments.length - 1 : patternSegments.length;
        for (int i = 0; i < checkLength; i++) {
            String pSeg = patternSegments[i];
            String aSeg = actualSegments[i];

            if ("+".equals(pSeg)) {
                continue;
            }
            if (VARIABLE_PATTERN.matcher(pSeg).matches()) {
                continue;
            }
            if (!pSeg.equals(aSeg)) {
                return null;
            }
        }

        if (variableEntries.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> extracted = new HashMap<>(variableEntries.size());
        for (VariableEntry entry : variableEntries) {
            if (entry.segmentIndex < actualSegments.length) {
                extracted.put(entry.variableName, actualSegments[entry.segmentIndex]);
            }
        }
        return extracted;
    }

    /**
     * 判定实际主题是否匹配当前模式
     *
     * @param actualTopic 实际接收到的物理主题
     * @return true 若匹配
     */
    public boolean matches(String actualTopic) {
        return matchAndExtract(actualTopic) != null;
    }

    /**
     * 静态快捷判定两个主题过滤器是否匹配
     *
     * @param pattern     包含通配符的过滤器模式
     * @param actualTopic 实际主题
     * @return true 若匹配
     */
    public static boolean isMatch(String pattern, String actualTopic) {
        return new MqttTopicPattern(pattern).matches(actualTopic);
    }
}

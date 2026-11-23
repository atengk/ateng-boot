package io.github.atengk.boot.ai.context;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体单次方法调用上下文持有者
 * 用于在当前线程中透传复杂参数的 JSON 替换规则，驱动底层模型调用时将 toString 输出转为标准 JSON 提示词。
 *
 * @author Ateng
 * @since 2026-11-03
 */
public final class AgentCallContext {

    private static final ThreadLocal<Map<String, String>> REPLACEMENTS_HOLDER = new ThreadLocal<>();

    private AgentCallContext() {
        // 工具类私有构造
    }

    /**
     * 开启调用作用域，在 try-with-resources 块中自动清理并支持多层嵌套栈帧安全回退
     *
     * @param replacements 待替换的形参映射 (toString() -> JSON)
     * @return 作用域句柄
     */
    public static Scope openScope(Map<String, String> replacements) {
        Map<String, String> previous = REPLACEMENTS_HOLDER.get();
        if (replacements != null && !replacements.isEmpty()) {
            Map<String, String> current = new HashMap<>();
            if (previous != null) {
                current.putAll(previous);
            }
            current.putAll(replacements);
            REPLACEMENTS_HOLDER.set(current);
        } else {
            if (previous != null) {
                REPLACEMENTS_HOLDER.set(new HashMap<>(previous));
            } else {
                REPLACEMENTS_HOLDER.remove();
            }
        }
        return new Scope(previous);
    }

    /**
     * 对输入文本中的参数 toString 标记进行替换
     * 按照键长度降序替换，避免短字符串优先命中导致长特征标记被提前截断
     *
     * @param text 原始提示词文本
     * @return 替换后的纯净提示词
     */
    public static String applyReplacements(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Map<String, String> map = REPLACEMENTS_HOLDER.get();
        if (map == null || map.isEmpty()) {
            return text;
        }
        String result = text;
        List<Map.Entry<String, String>> sortedEntries = new ArrayList<>(map.entrySet());
        sortedEntries.sort((e1, e2) -> Integer.compare(
                e2.getKey() != null ? e2.getKey().length() : 0,
                e1.getKey() != null ? e1.getKey().length() : 0
        ));
        for (Map.Entry<String, String> entry : sortedEntries) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (StringUtils.hasText(key) && value != null && result.contains(key)) {
                result = result.replace(key, value);
            }
        }
        return result;
    }

    /**
     * 获取当前注册的参数替换表（只读）
     *
     * @return 替换映射表
     */
    public static Map<String, String> getReplacements() {
        Map<String, String> map = REPLACEMENTS_HOLDER.get();
        return map != null ? Collections.unmodifiableMap(map) : Collections.emptyMap();
    }

    /**
     * 清理当前线程上下文
     */
    public static void clear() {
        REPLACEMENTS_HOLDER.remove();
    }

    /**
     * 上下文作用域自动关闭器，退出作用域时精准恢复上层上下文栈帧
     */
    public static class Scope implements AutoCloseable {
        private final Map<String, String> previous;

        private Scope(Map<String, String> previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous != null) {
                REPLACEMENTS_HOLDER.set(previous);
            } else {
                REPLACEMENTS_HOLDER.remove();
            }
        }
    }
}

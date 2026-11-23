package io.github.atengk.boot.liteflow.core;

import com.yomahub.liteflow.flow.FlowBus;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LiteFlow EL 表达式静态词法引用分析器
 * <p>
 * 负责在不编译/不注册流程链的前提下，安全剥离注释、字符串字面量（如 {@code .tag("...")}、{@code .data("...")}）
 * 与 EL 保留关键字/链式修饰符，提取表达式引用的组件节点、子流程链及未注册缺失标识清单。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
final class ElExpressionAnalyzer {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("(\\.?[a-zA-Z_][a-zA-Z0-9_-]*)");

    private static final Pattern ASSIGNED_VAR_PATTERN =
            Pattern.compile("(?<![=.!<>])\\b([a-zA-Z_][a-zA-Z0-9_-]*)\\s*=\\s*(?!=)");

    private static final Set<String> EL_KEYWORDS = Set.of(
            "THEN", "SER", "WHEN", "PAR", "SWITCH", "IF", "ELIF", "ELSE",
            "FOR", "WHILE", "ITERATOR", "DO", "BREAK", "DEFAULT",
            "PRE", "FINALLY", "CATCH", "AND", "OR", "NOT",
            "TO", "ANY", "MUST", "ID", "TAG", "DATA",
            "THREADPOOL", "MAXWAITSECONDS", "MAXWAITMILLISECONDS",
            "IGNOREERROR", "RETRY", "BIND", "NODE", "CHAIN",
            "TRUE", "FALSE", "NULL", "BOOLEAN"
    );

    private ElExpressionAnalyzer() {
    }

    /**
     * 对 EL 表达式执行静态词法引用分析，并与 {@link FlowBus} 实时比对
     *
     * @param el 原始 EL 表达式
     * @return 结构化引用分析快照
     */
    static AnalysisResult analyze(String el) {
        if (el == null || el.isBlank()) {
            return AnalysisResult.EMPTY;
        }
        String sanitized = stripCommentsAndQuotedLiterals(el);
        Set<String> localVariables = extractLocalVariables(sanitized);
        Set<String> referencedNodes = new LinkedHashSet<>();
        Set<String> referencedChains = new LinkedHashSet<>();
        Set<String> missingComponents = new LinkedHashSet<>();

        Matcher matcher = IDENTIFIER_PATTERN.matcher(sanitized);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token.startsWith(".")) {
                // 链式调用修饰方法（如 .tag / .data / .to / .maxWaitSeconds），非组件标识
                continue;
            }
            if (localVariables.contains(token) || EL_KEYWORDS.contains(token.toUpperCase(Locale.ROOT))) {
                continue;
            }
            if (FlowBus.containNode(token)) {
                referencedNodes.add(token);
            } else if (FlowBus.containChain(token)) {
                referencedChains.add(token);
            } else {
                missingComponents.add(token);
            }
        }
        return new AnalysisResult(
                Collections.unmodifiableSet(referencedNodes),
                Collections.unmodifiableSet(referencedChains),
                Collections.unmodifiableSet(missingComponents)
        );
    }

    /**
     * 提取 EL 表达式中通过 {@code var = ...} 定义的局部子编排变量名，避免误判为缺失组件
     */
    private static Set<String> extractLocalVariables(String sanitized) {
        Set<String> vars = new LinkedHashSet<>();
        Matcher matcher = ASSIGNED_VAR_PATTERN.matcher(sanitized);
        while (matcher.find()) {
            vars.add(matcher.group(1));
        }
        return vars;
    }

    /**
     * 剥离行注释、块注释以及单/双引号字符串字面量，避免字面量内容干扰标识符识别
     */
    static String stripCommentsAndQuotedLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int len = source.length();
        int i = 0;
        while (i < len) {
            char c = source.charAt(i);
            char next = (i + 1 < len) ? source.charAt(i + 1) : '\0';

            // 1. 行注释 // ...
            if (c == '/' && next == '/') {
                i += 2;
                while (i < len && source.charAt(i) != '\n' && source.charAt(i) != '\r') {
                    i++;
                }
                out.append(' ');
                continue;
            }
            // 2. 块注释 /* ... */
            if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < len && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, len);
                out.append(' ');
                continue;
            }
            // 3. 单引号 / 双引号字面量（支持反斜杠转义）
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < len) {
                    char ch = source.charAt(i);
                    if (ch == '\\') {
                        i += 2;
                        continue;
                    }
                    if (ch == quote) {
                        i++;
                        break;
                    }
                    i++;
                }
                out.append(' ');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    record AnalysisResult(
            Set<String> referencedNodeIds,
            Set<String> referencedChainIds,
            Set<String> missingComponentIds
    ) {
        static final AnalysisResult EMPTY = new AnalysisResult(
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet()
        );

        boolean hasMissingComponents() {
            return !missingComponentIds.isEmpty();
        }
    }
}

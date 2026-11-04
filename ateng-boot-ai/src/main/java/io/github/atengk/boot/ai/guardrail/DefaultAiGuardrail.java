package io.github.atengk.boot.ai.guardrail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 双向内容安全护栏默认规则引擎实现
 * <p>
 * 基于纯原生高效 Trie 树实现敏感词 $O(L)$ 极速探测，结合正则表达式特征库防御越狱与提示词注入攻击，
 * 严格秉持零外部三方依赖 (Zero-Bloat) 与线程安全无状态契约。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-04
 */
public class DefaultAiGuardrail implements AiGuardrail {

    private static final Logger log = LoggerFactory.getLogger(DefaultAiGuardrail.class);

    private static final List<String> BUILTIN_INJECTION_PATTERNS = List.of(
            // 英文忽略前序指令
            "(?is).*(?:ignore|disregard|forget|bypass|override)\\s+(?:all\\s+)?(?:previous|prior|above|system|existing)\\s+(?:instructions?|prompts?|rules?|directives?).*",
            // 英文 DAN/越狱模式
            "(?is).*(?:you\\s+are\\s+now|act\\s+as|pretend\\s+to\\s+be|switch\\s+to|enter)\\s+.*(?:DAN|jailbreak|unrestricted|god\\s+mode).*",
            // 英文开发者模式限制绕过
            "(?is).*(?:act\\s+as|enter|switch\\s+to)\\s+.*developer\\s+mode.*(?:unrestricted|ignore|bypass|no\\s+filter|jailbreak).*",
            // 英文窃取/探测系统提示词
            "(?is).*(?:output|print|display|reveal|show|dump|repeat|leak)\\s+(?:your|the)?\\s*(?:system\\s+prompt|initial\\s+prompt|base\\s+instructions?).*",
            // 英文 Do Anything Now
            "(?is).*(?:do\\s+anything\\s+now|jailbreak\\s+prompt).*",
            // 中文忽略前序指令
            "(?is).*(?:忽略|无视|忘记|丢弃|绕过).*(?:之前|以上|所有|历史|原先).*(?:指令|提示词|提示|规则|设定|约束).*",
            // 中文获取系统提示词
            "(?is).*(?:输出|展示|泄露|打印|复述|显示|获取|读取).*(?:系统提示词|系统预设|系统指令|System\\s*Prompt).*",
            // 中文越狱/DAN/无限制模式
            "(?is).*(?:进入|切换|开启|扮演).*(?:DAN|越狱|无限制|上帝)模式.*",
            // 中文绕过安全审查
            "(?is).*(?:绕过|解除|无视).*(?:安全审查|合规审查|安全护栏|道德限制|内容过滤).*"
    );

    private final AiGuardrailProperties properties;
    private final List<Pattern> injectionPatterns = new CopyOnWriteArrayList<>();
    private final Set<String> whitelistSet = new CopyOnWriteArraySet<>();

    /**
     * Trie 树根节点
     */
    private final TrieNode root = new TrieNode();

    /**
     * 树构建互斥锁，保障动态增量写入与并发检索安全
     */
    private final Object trieLock = new Object();

    public DefaultAiGuardrail() {
        this(new AiGuardrailProperties());
    }

    public DefaultAiGuardrail(AiGuardrailProperties properties) {
        this.properties = properties != null ? properties : new AiGuardrailProperties();
        initBuiltinPatterns();
        initProperties();
    }

    private void initBuiltinPatterns() {
        for (String patternStr : BUILTIN_INJECTION_PATTERNS) {
            try {
                this.injectionPatterns.add(Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.UNICODE_CASE));
            } catch (Exception e) {
                log.warn("编译内置越狱检测正则表达式失败: {}", patternStr, e);
            }
        }
    }

    private void initProperties() {
        if (properties.getInjectionPatterns() != null) {
            for (String customPattern : properties.getInjectionPatterns()) {
                if (StringUtils.hasText(customPattern)) {
                    try {
                        this.injectionPatterns.add(Pattern.compile(customPattern.trim(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.UNICODE_CASE));
                    } catch (Exception e) {
                        log.warn("编译自定义越狱检测正则表达式失败: {}", customPattern, e);
                    }
                }
            }
        }
        if (properties.getWhitelist() != null) {
            for (String white : properties.getWhitelist()) {
                if (StringUtils.hasText(white)) {
                    this.whitelistSet.add(white.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (properties.getBannedKeywords() != null && !properties.getBannedKeywords().isEmpty()) {
            addBannedKeywords(properties.getBannedKeywords());
        }
    }

    /**
     * 动态批量追加敏感违禁词
     *
     * @param words 违禁词集合
     */
    public void addBannedKeywords(Collection<String> words) {
        if (words == null || words.isEmpty()) {
            return;
        }
        synchronized (trieLock) {
            for (String word : words) {
                if (!StringUtils.hasText(word)) {
                    continue;
                }
                String normalized = word.trim().toLowerCase(Locale.ROOT);
                TrieNode current = root;
                for (int i = 0; i < normalized.length(); i++) {
                    char c = normalized.charAt(i);
                    current = current.children.computeIfAbsent(c, k -> new TrieNode());
                }
                current.isEnd = true;
                current.word = word.trim();
            }
        }
    }

    /**
     * 动态批量追加免检白名单词汇
     *
     * @param words 白名单词汇集合
     */
    public void addWhitelist(Collection<String> words) {
        if (words == null || words.isEmpty()) {
            return;
        }
        for (String word : words) {
            if (StringUtils.hasText(word)) {
                this.whitelistSet.add(word.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    /**
     * 动态批量追加越狱注入正则表达式
     *
     * @param patterns 正则表达式集合
     */
    public void addInjectionPatterns(Collection<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return;
        }
        for (String patternStr : patterns) {
            if (StringUtils.hasText(patternStr)) {
                try {
                    this.injectionPatterns.add(Pattern.compile(patternStr.trim(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.UNICODE_CASE));
                } catch (Exception e) {
                    log.warn("动态编译越狱检测正则表达式失败: {}", patternStr, e);
                }
            }
        }
    }

    @Override
    public GuardrailResult inspectPrompt(String prompt) {
        return inspectPrompt(prompt, Collections.emptyMap());
    }

    @Override
    public GuardrailResult inspectPrompt(String prompt, Map<String, Object> context) {
        if (!properties.isEnabled() || !StringUtils.hasText(prompt)) {
            return GuardrailResult.allow(prompt, AiGuardrailPhase.PRE_PROMPT);
        }

        // 1. 检查提示词最大长度限制
        int maxLength = properties.getMaxPromptLength();
        if (maxLength > 0 && prompt.length() > maxLength) {
            String rule = "MAX_LENGTH_EXCEEDED";
            String reason = "提示词字符长度 [" + prompt.length() + "] 超过系统允许的最大长度阈值 [" + maxLength + "]";
            log.warn("AI 前置护栏拦截 (提示词超长): length={}, max={}, context={}", prompt.length(), maxLength, context);
            return handleViolation(AiGuardrailPhase.PRE_PROMPT, rule, reason);
        }

        // 2. 检查越狱与提示词注入攻击 (Regex Patterns)
        for (Pattern pattern : injectionPatterns) {
            Matcher matcher = pattern.matcher(prompt);
            if (matcher.find()) {
                String rule = "PROMPT_INJECTION:" + pattern.pattern();
                String reason = "检测到潜在的提示词越狱或注入攻击指令";
                log.warn("AI 前置护栏拦截 (提示词注入/越狱): pattern={}, context={}", pattern.pattern(), context);
                return handleViolation(AiGuardrailPhase.PRE_PROMPT, rule, reason);
            }
        }

        // 3. 检查敏感违禁词 (Trie Tree)
        String bannedWord = matchBannedKeyword(prompt);
        if (bannedWord != null) {
            String rule = "SENSITIVE_KEYWORD:" + bannedWord;
            String reason = "提示词包含违规敏感词: [" + bannedWord + "]";
            log.warn("AI 前置护栏拦截 (敏感词匹配): bannedWord={}, context={}", bannedWord, context);
            return handleViolation(AiGuardrailPhase.PRE_PROMPT, rule, reason);
        }

        return GuardrailResult.allow(prompt, AiGuardrailPhase.PRE_PROMPT);
    }

    @Override
    public GuardrailResult inspectResponse(String responseText) {
        return inspectResponse(responseText, Collections.emptyMap());
    }

    @Override
    public GuardrailResult inspectResponse(String responseText, Map<String, Object> context) {
        if (!properties.isEnabled() || !StringUtils.hasText(responseText)) {
            return GuardrailResult.allow(responseText, AiGuardrailPhase.POST_RESPONSE);
        }

        // 1. 检查大模型回复文本中的敏感违禁词 (Trie Tree)
        String bannedWord = matchBannedKeyword(responseText);
        if (bannedWord != null) {
            String rule = "SENSITIVE_KEYWORD:" + bannedWord;
            String reason = "大模型回复内容包含违规敏感词: [" + bannedWord + "]";
            log.warn("AI 后置护栏拦截 (敏感词匹配): bannedWord={}, context={}", bannedWord, context);
            return handleViolation(AiGuardrailPhase.POST_RESPONSE, rule, reason);
        }

        return GuardrailResult.allow(responseText, AiGuardrailPhase.POST_RESPONSE);
    }

    private GuardrailResult handleViolation(AiGuardrailPhase phase, String matchedRule, String reason) {
        if (properties.getMode() == GuardrailMode.REPLACE) {
            String safeContent = properties.getBlockResponseText();
            return GuardrailResult.replace(safeContent, phase, matchedRule, reason);
        }
        return GuardrailResult.block(phase, matchedRule, reason);
    }

    /**
     * 基于前缀 Trie 树高效检索文本中是否包含违禁词
     *
     * @param text 待审查文本
     * @return 命中的第一个违规词，未命中返回 null
     */
    private String matchBannedKeyword(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        int n = normalized.length();

        synchronized (trieLock) {
            if (root.children.isEmpty()) {
                return null;
            }
            for (int i = 0; i < n; i++) {
                TrieNode node = root;
                for (int j = i; j < n; j++) {
                    char c = normalized.charAt(j);
                    node = node.children.get(c);
                    if (node == null) {
                        break;
                    }
                    if (node.isEnd) {
                        String matched = node.word;
                        if (!isWhitelisted(matched, text, i, j + 1)) {
                            return matched;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 判定命中的敏感词是否被免检白名单覆盖
     */
    private boolean isWhitelisted(String matched, String fullText, int start, int end) {
        if (whitelistSet.isEmpty()) {
            return false;
        }
        String normalizedMatched = matched.toLowerCase(Locale.ROOT);
        if (whitelistSet.contains(normalizedMatched)) {
            return true;
        }
        String normalizedFull = fullText.toLowerCase(Locale.ROOT);
        for (String white : whitelistSet) {
            if (white.contains(normalizedMatched)) {
                int idx = normalizedFull.indexOf(white);
                while (idx >= 0) {
                    int whiteEnd = idx + white.length();
                    if (idx <= start && whiteEnd >= end) {
                        return true;
                    }
                    idx = normalizedFull.indexOf(white, idx + 1);
                }
            }
        }
        return false;
    }

    /**
     * 获取配置属性
     */
    public AiGuardrailProperties getProperties() {
        return properties;
    }

    /**
     * Trie 树节点静态结构
     */
    private static class TrieNode {
        final Map<Character, TrieNode> children = new HashMap<>();
        boolean isEnd = false;
        String word;
    }
}

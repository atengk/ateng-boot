package io.github.atengk.boot.web.xss;

import org.springframework.web.util.HtmlUtils;

import java.util.regex.Pattern;

/**
 * XSS 跨站脚本安全清洗器
 * 提供双轨无害化清洗能力：实体转义（ESCAPE）与标签剔除（STRIP）
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class XssCleaner {

    private static final Pattern SCRIPT_BLOCK_PATTERN = Pattern.compile(
            "<script[^>]*>.*?</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern STYLE_BLOCK_PATTERN = Pattern.compile(
            "<style[^>]*>.*?</style>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern HTML_COMMENT_PATTERN = Pattern.compile(
            "<!--.*?-->",
            Pattern.DOTALL
    );

    private static final Pattern HTML_TAG_PATTERN = Pattern.compile(
            "</?[a-zA-Z!][^>]*>",
            Pattern.DOTALL
    );

    private final XssMode mode;

    public XssCleaner() {
        this(XssMode.ESCAPE);
    }

    public XssCleaner(XssMode mode) {
        this.mode = mode != null ? mode : XssMode.ESCAPE;
    }

    /**
     * 根据配置模式执行 XSS 安全清洗
     *
     * @param value 待清洗的原始字符串
     * @return 清洗后的安全字符串
     */
    public String clean(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (mode == XssMode.STRIP) {
            return strip(value);
        }
        return escape(value);
    }

    /**
     * 实体转义模式：将 HTML 关键特殊字符转义为 HTML 实体
     *
     * @param value 待转义的原始字符串
     * @return 转义后的安全字符串
     */
    public String escape(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return HtmlUtils.htmlEscape(value);
    }

    /**
     * 标签剔除模式：彻底剥离 script/style 块与注释并移除所有 HTML 标签
     *
     * @param value 待剔除标签的原始字符串
     * @return 剔除标签后的纯文本字符串
     */
    public String strip(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String result = value;
        String prev;
        // 循环清除嵌套/畸形构造标签，直到收敛
        do {
            prev = result;
            result = SCRIPT_BLOCK_PATTERN.matcher(result).replaceAll("");
            result = STYLE_BLOCK_PATTERN.matcher(result).replaceAll("");
            result = HTML_COMMENT_PATTERN.matcher(result).replaceAll("");
            result = HTML_TAG_PATTERN.matcher(result).replaceAll("");
        } while (!result.equals(prev));
        return result;
    }

    public XssMode getMode() {
        return mode;
    }
}

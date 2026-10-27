package io.github.atengk.boot.web.xss;

/**
 * XSS 跨站脚本安全清洗模式枚举
 *
 * @author Ateng
 * @since 2026-10-27
 */
public enum XssMode {

    /**
     * 实体转义模式（默认）：将 HTML 特殊字符（如 &lt;, &gt;, &quot;, &#39;, &amp; 等）转义为 HTML 实体
     */
    ESCAPE,

    /**
     * 标签剔除模式：彻底移除 HTML 标签与潜在恶意脚本片段（如 &lt;script&gt;、&lt;style&gt; 等）
     */
    STRIP
}

package io.github.atengk.boot.social.support;

import io.github.atengk.boot.social.exception.SocialRedirectUriInvalidException;

import java.net.URI;
import java.util.Collection;
import java.util.Locale;

/**
 * 社交登录 OAuth2 重定向地址安全校验器
 * <p>
 * 提供开放重定向（Open Redirect）双层防卫：
 * 1. 无条件拦截 javascript:、data:、vbscript: 危险伪协议及 // 协议相对路径；
 * 2. 默认放行站内相对路径（单斜杠 / 开头）；
 * 3. 当配置允许的域名白名单列表时，校验绝对 URL 的主机名（Host）是否匹配精确域名或 *.example.com 泛域名。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-07
 */
public final class SocialRedirectUriValidator {

    private SocialRedirectUriValidator() {
    }

    /**
     * 校验重定向地址安全性
     *
     * @param redirectUrl    待校验的目标重定向地址
     * @param allowedDomains 允许的域名白名单集合（为空或 null 表示不限制域名）
     * @throws SocialRedirectUriInvalidException 当重定向地址非法或未在白名单时抛出
     */
    public static void validate(String redirectUrl, Collection<String> allowedDomains) {
        if (redirectUrl == null || redirectUrl.isBlank()) {
            return;
        }

        String trimmed = redirectUrl.trim();

        // 1. 防御 CRLF 注入 / 换行控制符
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t' || c < 0x20) {
                throw new SocialRedirectUriInvalidException("重定向地址包含非法控制字符: " + redirectUrl);
            }
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);

        // 2. 无条件防御伪协议
        if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("vbscript:")) {
            throw new SocialRedirectUriInvalidException("重定向地址包含危险伪协议: " + redirectUrl);
        }

        // 3. 防御协议相对路径及异常斜杠构造
        if (lower.startsWith("//") || lower.startsWith("/\\") || lower.startsWith("\\\\") || lower.startsWith("\\")) {
            throw new SocialRedirectUriInvalidException("禁止使用协议相对路径或异常斜杠格式重定向地址: " + redirectUrl);
        }

        // 4. 单斜杠站内相对路径直接安全放行
        if (lower.startsWith("/")) {
            if (lower.length() > 1 && (lower.charAt(1) == '/' || lower.charAt(1) == '\\')) {
                throw new SocialRedirectUriInvalidException("禁止使用协议相对路径重定向地址: " + redirectUrl);
            }
            return;
        }

        // 5. 绝对 URL 解析与协议校验
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (Exception ex) {
            throw new SocialRedirectUriInvalidException("重定向地址 URI 格式非法: " + redirectUrl, ex);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new SocialRedirectUriInvalidException("重定向地址必须使用 http 或 https 协议: " + redirectUrl);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SocialRedirectUriInvalidException("重定向地址缺少有效主机名 (Host): " + redirectUrl);
        }

        // 6. 白名单域名匹配（若配置了白名单）
        if (allowedDomains != null && !allowedDomains.isEmpty()) {
            boolean matched = false;
            String hostLower = host.toLowerCase(Locale.ROOT);
            for (String domainPattern : allowedDomains) {
                if (domainPattern == null || domainPattern.isBlank()) {
                    continue;
                }
                String pattern = domainPattern.trim().toLowerCase(Locale.ROOT);
                if (pattern.startsWith("*.")) {
                    String baseDomain = pattern.substring(2);
                    if (hostLower.equals(baseDomain) || hostLower.endsWith("." + baseDomain)) {
                        matched = true;
                        break;
                    }
                } else {
                    if (hostLower.equals(pattern)) {
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched) {
                throw new SocialRedirectUriInvalidException("重定向地址域名 [" + host + "] 不在允许的白名单列表中: " + redirectUrl);
            }
        }
    }
}

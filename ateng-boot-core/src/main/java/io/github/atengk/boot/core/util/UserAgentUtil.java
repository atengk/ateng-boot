package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.model.UserAgentInfo;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 纯 JDK 17 零外部规则库依赖的轻量级 User-Agent 解析与净化工具门面
 * 采用有界线性扫描匹配常见浏览器、操作系统与设备类型，彻底避免正则回溯导致的 ReDoS 风险
 *
 * @author Ateng
 * @since 2026-11-22
 */
public final class UserAgentUtil {

    /**
     * 默认最大采集与解析长度（字符数）
     */
    public static final int DEFAULT_MAX_LENGTH = 512;

    /**
     * 单次解析允许的硬性长度上限，防止畸形超长请求头消耗过多 CPU
     */
    public static final int MAX_PARSE_LENGTH = 2048;

    private static final int MAX_VERSION_LENGTH = 32;

    private static final List<UaRule> BROWSER_RULES = List.of(
            new UaRule("micromessenger/", "WeChat", "micromessenger/"),
            new UaRule("dingtalk/", "DingTalk", "dingtalk/"),
            new UaRule("edg/", "Edge", "edg/"),
            new UaRule("edga/", "Edge", "edga/"),
            new UaRule("edgios/", "Edge", "edgios/"),
            new UaRule("edge/", "Edge", "edge/"),
            new UaRule("opr/", "Opera", "opr/"),
            new UaRule("opera/", "Opera", "opera/"),
            new UaRule("samsungbrowser/", "Samsung Browser", "samsungbrowser/"),
            new UaRule("mqqbrowser/", "QQ Browser", "mqqbrowser/"),
            new UaRule("qqbrowser/", "QQ Browser", "qqbrowser/"),
            new UaRule("ucbrowser/", "UC Browser", "ucbrowser/"),
            new UaRule("firefox/", "Firefox", "firefox/"),
            new UaRule("fxios/", "Firefox", "fxios/"),
            new UaRule("chrome/", "Chrome", "chrome/"),
            new UaRule("crios/", "Chrome", "crios/"),
            new UaRule("msie ", "IE", "msie "),
            new UaRule("trident/", "IE", "rv:"),
            new UaRule("curl/", "curl", "curl/"),
            new UaRule("postmanruntime/", "Postman", "postmanruntime/"),
            new UaRule("okhttp/", "OkHttp", "okhttp/"),
            new UaRule("apache-httpclient/", "Apache-HttpClient", "apache-httpclient/"),
            new UaRule("java/", "Java", "java/"),
            new UaRule("python-requests/", "Python-Requests", "python-requests/")
    );

    private static final List<String> CLI_BROWSERS = List.of(
            "curl", "Postman", "OkHttp", "Apache-HttpClient", "Java", "Python-Requests"
    );

    private static final List<String> BOT_TOKENS = List.of(
            "googlebot", "bingbot", "baiduspider", "bytespider", "crawler", "spider"
    );

    private static final List<String> LINUX_TOKENS = List.of(
            "linux", "ubuntu", "debian", "centos", "fedora", "alpine"
    );

    private static final Map<String, String> WINDOWS_NT_VERSIONS = Map.of(
            "10.0", "10/11",
            "6.3", "8.1",
            "6.2", "8",
            "6.1", "7",
            "6.0", "Vista",
            "5.1", "XP"
    );

    private UserAgentUtil() {
    }

    /**
     * 净化并按最大长度截断 User-Agent 请求头，过滤 ASCII 控制字符（含 \r、\n、\t 等）防日志注入
     *
     * @param userAgent 原始 User-Agent 请求头字符串
     * @param maxLength 最大允许保留字符数（小于等于 0 时返回空字符串）
     * @return 净化截断后的字符串；当入参为 null 时返回 null
     */
    public static String sanitize(String userAgent, int maxLength) {
        if (userAgent == null) {
            return null;
        }
        if (maxLength <= 0) {
            return "";
        }
        int len = userAgent.length();
        StringBuilder sb = new StringBuilder(Math.min(len, maxLength));
        for (int i = 0; i < len && sb.length() < maxLength; i++) {
            char c = userAgent.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                continue;
            }
            if (sb.isEmpty() && Character.isWhitespace(c)) {
                continue;
            }
            sb.append(c);
        }
        int end = sb.length();
        while (end > 0 && Character.isWhitespace(sb.charAt(end - 1))) {
            end--;
        }
        return sb.substring(0, end);
    }

    /**
     * 使用默认最大长度（512）解析 User-Agent 字符串
     *
     * @param userAgent 原始 User-Agent 请求头
     * @return 不可变解析结果 {@link UserAgentInfo}
     */
    public static UserAgentInfo parse(String userAgent) {
        return parse(userAgent, DEFAULT_MAX_LENGTH);
    }

    /**
     * 按指定最大长度截断并解析 User-Agent 字符串
     *
     * @param userAgent 原始 User-Agent 请求头
     * @param maxLength 最大保留长度
     * @return 不可变解析结果 {@link UserAgentInfo}
     */
    public static UserAgentInfo parse(String userAgent, int maxLength) {
        int boundedLength = maxLength <= 0 ? 0 : Math.min(maxLength, MAX_PARSE_LENGTH);
        String sanitized = sanitize(userAgent, boundedLength);
        if (sanitized == null || sanitized.isEmpty()) {
            return UserAgentInfo.unknown(sanitized);
        }

        String lower = sanitized.toLowerCase(Locale.ROOT);
        NameVersion browser = detectBrowser(sanitized, lower);
        NameVersion os = detectOs(sanitized, lower);
        boolean isCli = CLI_BROWSERS.contains(browser.name());
        boolean isBot = UserAgentInfo.DEVICE_BOT.equals(browser.name()) || containsAnyToken(lower, BOT_TOKENS);
        String deviceType = detectDeviceType(lower, os.name(), isCli, isBot);
        boolean mobile = UserAgentInfo.DEVICE_MOBILE.equals(deviceType)
                || UserAgentInfo.DEVICE_TABLET.equals(deviceType);

        return new UserAgentInfo(
                browser.name(),
                browser.version(),
                os.name(),
                os.version(),
                deviceType,
                mobile,
                sanitized
        );
    }

    /**
     * 快捷解析浏览器或客户端名称
     *
     * @param userAgent 原始 User-Agent 请求头
     * @return 浏览器名称；无法识别或为空时返回 "Unknown"
     */
    public static String parseBrowser(String userAgent) {
        return parse(userAgent).browser();
    }

    /**
     * 快捷解析操作系统名称
     *
     * @param userAgent 原始 User-Agent 请求头
     * @return 操作系统名称；无法识别或为空时返回 "Unknown"
     */
    public static String parseOs(String userAgent) {
        return parse(userAgent).os();
    }

    /**
     * 判断是否为移动端或平板设备
     *
     * @param userAgent 原始 User-Agent 请求头
     * @return 若识别为 Mobile 或 Tablet 设备返回 true，否则返回 false
     */
    public static boolean isMobile(String userAgent) {
        return parse(userAgent).mobile();
    }

    private static NameVersion detectBrowser(String ua, String lower) {
        if (containsAnyToken(lower, BOT_TOKENS)) {
            return new NameVersion(UserAgentInfo.DEVICE_BOT, UserAgentInfo.UNKNOWN);
        }
        for (UaRule rule : BROWSER_RULES) {
            if (lower.contains(rule.token())) {
                return new NameVersion(rule.name(), extractVersion(ua, lower, rule.versionToken()));
            }
        }
        if (lower.contains("safari/") && (lower.contains("version/") || lower.contains("applewebkit/"))) {
            String ver = lower.contains("version/")
                    ? extractVersion(ua, lower, "version/")
                    : extractVersion(ua, lower, "safari/");
            return new NameVersion("Safari", ver);
        }
        return new NameVersion(UserAgentInfo.UNKNOWN, UserAgentInfo.UNKNOWN);
    }

    private static NameVersion detectOs(String ua, String lower) {
        if (lower.contains("openharmony") || lower.contains("harmonyos")) {
            String token = lower.contains("openharmony ") ? "openharmony " : "harmonyos ";
            return new NameVersion("HarmonyOS", extractVersion(ua, lower, token));
        }
        if (lower.contains("android")) {
            return new NameVersion("Android", extractVersion(ua, lower, "android "));
        }
        if (lower.contains("iphone") || lower.contains("ipad") || lower.contains("ipod")) {
            String token = lower.contains("iphone os ") ? "iphone os " : "cpu os ";
            return new NameVersion("iOS", extractVersion(ua, lower, token));
        }
        if (lower.contains("windows")) {
            String ntVer = extractVersion(ua, lower, "windows nt ");
            return new NameVersion("Windows", WINDOWS_NT_VERSIONS.getOrDefault(ntVer, ntVer));
        }
        if (lower.contains("mac os x") || lower.contains("macintosh")) {
            return new NameVersion("macOS", extractVersion(ua, lower, "mac os x "));
        }
        if (containsAnyToken(lower, LINUX_TOKENS)) {
            return new NameVersion("Linux", UserAgentInfo.UNKNOWN);
        }
        return new NameVersion(UserAgentInfo.UNKNOWN, UserAgentInfo.UNKNOWN);
    }

    private static String detectDeviceType(String lower, String osName, boolean isCli, boolean isBot) {
        if (isBot) {
            return UserAgentInfo.DEVICE_BOT;
        }
        if (isCli) {
            return UserAgentInfo.DEVICE_CLI;
        }
        if (lower.contains("ipad") || lower.contains("tablet") || (lower.contains("android") && !lower.contains("mobile"))) {
            return UserAgentInfo.DEVICE_TABLET;
        }
        if (lower.contains("iphone") || lower.contains("ipod") || lower.contains("mobile")
                || "Android".equals(osName) || "iOS".equals(osName) || "HarmonyOS".equals(osName)) {
            return UserAgentInfo.DEVICE_MOBILE;
        }
        if ("Windows".equals(osName) || "macOS".equals(osName) || "Linux".equals(osName)) {
            return UserAgentInfo.DEVICE_DESKTOP;
        }
        return UserAgentInfo.UNKNOWN;
    }

    private static boolean containsAnyToken(String lower, List<String> tokens) {
        for (String token : tokens) {
            if (lower.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String extractVersion(String ua, String lower, String token) {
        int idx = lower.indexOf(token);
        if (idx < 0) {
            return UserAgentInfo.UNKNOWN;
        }
        int start = idx + token.length();
        int len = ua.length();
        while (start < len && (ua.charAt(start) == ' ' || ua.charAt(start) == '/')) {
            start++;
        }
        StringBuilder version = new StringBuilder();
        for (int i = start; i < len && version.length() < MAX_VERSION_LENGTH; i++) {
            char c = ua.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.') {
                version.append(c);
            } else if (c == '_') {
                version.append('.');
            } else {
                break;
            }
        }
        if (version.isEmpty()) {
            return UserAgentInfo.UNKNOWN;
        }
        int end = version.length();
        while (end > 0 && version.charAt(end - 1) == '.') {
            end--;
        }
        return end == 0 ? UserAgentInfo.UNKNOWN : version.substring(0, end);
    }

    private record UaRule(String token, String name, String versionToken) {
    }

    private record NameVersion(String name, String version) {
    }
}

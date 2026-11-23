package io.github.atengk.boot.core.model;

import java.io.Serializable;

/**
 * 不可变客户端 User-Agent 解析结果契约 Record
 *
 * @param browser        浏览器或客户端名称（如 Chrome、Edge、Firefox、Safari、curl，未知为 Unknown）
 * @param browserVersion 浏览器或客户端版本号（未知为 Unknown）
 * @param os             操作系统名称（如 Windows、macOS、Android、iOS、Linux、HarmonyOS，未知为 Unknown）
 * @param osVersion      操作系统版本号（未知为 Unknown）
 * @param deviceType     设备类型（Desktop、Mobile、Tablet、CLI、Bot 或 Unknown）
 * @param mobile         是否为移动端或平板设备
 * @param rawUserAgent   经过控制字符净化与最大长度截断后的原始 User-Agent 字符串
 * @author Ateng
 * @since 2026-11-22
 */
public record UserAgentInfo(
        String browser,
        String browserVersion,
        String os,
        String osVersion,
        String deviceType,
        boolean mobile,
        String rawUserAgent
) implements Serializable {

    public static final String UNKNOWN = "Unknown";
    public static final String DEVICE_DESKTOP = "Desktop";
    public static final String DEVICE_MOBILE = "Mobile";
    public static final String DEVICE_TABLET = "Tablet";
    public static final String DEVICE_CLI = "CLI";
    public static final String DEVICE_BOT = "Bot";

    public UserAgentInfo {
        browser = normalizeOrUnknown(browser);
        browserVersion = normalizeOrUnknown(browserVersion);
        os = normalizeOrUnknown(os);
        osVersion = normalizeOrUnknown(osVersion);
        deviceType = normalizeOrUnknown(deviceType);
    }

    /**
     * 构建未知客户端保底解析结果
     *
     * @param rawUserAgent 净化后的原始 User-Agent 字符串
     * @return 保底 UserAgentInfo 实例
     */
    public static UserAgentInfo unknown(String rawUserAgent) {
        return new UserAgentInfo(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, false, rawUserAgent);
    }

    /**
     * 构建无输入时的默认未知客户端解析结果
     *
     * @return 保底 UserAgentInfo 实例
     */
    public static UserAgentInfo unknown() {
        return unknown(null);
    }

    private static String normalizeOrUnknown(String value) {
        return (value == null || value.isBlank()) ? UNKNOWN : value;
    }
}

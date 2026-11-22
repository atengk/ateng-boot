package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.model.UserAgentInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯原生轻量 User-Agent 解析工具单元测试
 *
 * @author Ateng
 * @since 2026-11-22
 */
@DisplayName("UserAgentUtil 解析与安全边界测试")
class UserAgentUtilTest {

    @Test
    @DisplayName("解析主流桌面端浏览器与操作系统")
    void shouldParseDesktopBrowsersAndOs() {
        String chromeWin = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
        UserAgentInfo chromeInfo = UserAgentUtil.parse(chromeWin);
        assertEquals("Chrome", chromeInfo.browser());
        assertEquals("126.0.0.0", chromeInfo.browserVersion());
        assertEquals("Windows", chromeInfo.os());
        assertEquals("10/11", chromeInfo.osVersion());
        assertEquals(UserAgentInfo.DEVICE_DESKTOP, chromeInfo.deviceType());
        assertFalse(chromeInfo.mobile());

        String edgeWin = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.2592.81";
        UserAgentInfo edgeInfo = UserAgentUtil.parse(edgeWin);
        assertEquals("Edge", edgeInfo.browser());
        assertEquals("126.0.2592.81", edgeInfo.browserVersion());
        assertEquals("Windows", edgeInfo.os());
        assertFalse(edgeInfo.mobile());

        String firefoxMac = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7; rv:128.0) Gecko/20100101 Firefox/128.0";
        UserAgentInfo firefoxInfo = UserAgentUtil.parse(firefoxMac);
        assertEquals("Firefox", firefoxInfo.browser());
        assertEquals("128.0", firefoxInfo.browserVersion());
        assertEquals("macOS", firefoxInfo.os());
        assertEquals("10.15.7", firefoxInfo.osVersion());
        assertEquals(UserAgentInfo.DEVICE_DESKTOP, firefoxInfo.deviceType());

        String safariMac = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15";
        UserAgentInfo safariInfo = UserAgentUtil.parse(safariMac);
        assertEquals("Safari", safariInfo.browser());
        assertEquals("17.5", safariInfo.browserVersion());
        assertEquals("macOS", safariInfo.os());
        assertEquals("14.5", safariInfo.osVersion());

        String operaLinux = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36 OPR/111.0.0.0";
        UserAgentInfo operaInfo = UserAgentUtil.parse(operaLinux);
        assertEquals("Opera", operaInfo.browser());
        assertEquals("111.0.0.0", operaInfo.browserVersion());
        assertEquals("Linux", operaInfo.os());
        assertEquals(UserAgentInfo.DEVICE_DESKTOP, operaInfo.deviceType());
    }

    @Test
    @DisplayName("解析移动端、平板、鸿蒙及内置 WebView 客户端")
    void shouldParseMobileTabletAndEmbeddedClients() {
        String iphone = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
        UserAgentInfo iphoneInfo = UserAgentUtil.parse(iphone);
        assertEquals("Safari", iphoneInfo.browser());
        assertEquals("iOS", iphoneInfo.os());
        assertEquals("17.5.1", iphoneInfo.osVersion());
        assertEquals(UserAgentInfo.DEVICE_MOBILE, iphoneInfo.deviceType());
        assertTrue(iphoneInfo.mobile());
        assertTrue(UserAgentUtil.isMobile(iphone));

        String ipad = "Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
        UserAgentInfo ipadInfo = UserAgentUtil.parse(ipad);
        assertEquals("Safari", ipadInfo.browser());
        assertEquals("iOS", ipadInfo.os());
        assertEquals(UserAgentInfo.DEVICE_TABLET, ipadInfo.deviceType());
        assertTrue(ipadInfo.mobile());

        String wechatAndroid = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP1A) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/122.0.6261.119 Mobile Safari/537.36 MicroMessenger/8.0.49.2600";
        UserAgentInfo wechatInfo = UserAgentUtil.parse(wechatAndroid);
        assertEquals("WeChat", wechatInfo.browser());
        assertEquals("8.0.49.2600", wechatInfo.browserVersion());
        assertEquals("Android", wechatInfo.os());
        assertEquals("14", wechatInfo.osVersion());
        assertEquals(UserAgentInfo.DEVICE_MOBILE, wechatInfo.deviceType());
        assertTrue(wechatInfo.mobile());

        String harmony = "Mozilla/5.0 (Phone; OpenHarmony 5.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 ArkWeb/4.1.6.1 Mobile";
        UserAgentInfo harmonyInfo = UserAgentUtil.parse(harmony);
        assertEquals("HarmonyOS", harmonyInfo.os());
        assertEquals(UserAgentInfo.DEVICE_MOBILE, harmonyInfo.deviceType());
        assertTrue(harmonyInfo.mobile());
    }

    @Test
    @DisplayName("解析 CLI 工具、HTTP 客户端与爬虫 Bot")
    void shouldParseCliAndBotClients() {
        UserAgentInfo curl = UserAgentUtil.parse("curl/8.5.0");
        assertEquals("curl", curl.browser());
        assertEquals("8.5.0", curl.browserVersion());
        assertEquals(UserAgentInfo.DEVICE_CLI, curl.deviceType());
        assertFalse(curl.mobile());

        UserAgentInfo postman = UserAgentUtil.parse("PostmanRuntime/7.39.0");
        assertEquals("Postman", postman.browser());
        assertEquals("7.39.0", postman.browserVersion());
        assertEquals(UserAgentInfo.DEVICE_CLI, postman.deviceType());

        UserAgentInfo bot = UserAgentUtil.parse("Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)");
        assertEquals("Bot", bot.browser());
        assertEquals(UserAgentInfo.DEVICE_BOT, bot.deviceType());
        assertFalse(bot.mobile());

        assertEquals("curl", UserAgentUtil.parseBrowser("curl/8.5.0"));
        assertEquals("Unknown", UserAgentUtil.parseOs("curl/8.5.0"));
    }

    @Test
    @DisplayName("对抗性边界：null、空白、控制字符注入及超长载荷防 ReDoS 截断")
    void shouldHandleNullMalformedControlCharsAndOverlengthSafely() {
        UserAgentInfo nullInfo = UserAgentUtil.parse(null);
        assertEquals(UserAgentInfo.UNKNOWN, nullInfo.browser());
        assertEquals(UserAgentInfo.UNKNOWN, nullInfo.browserVersion());
        assertEquals(UserAgentInfo.UNKNOWN, nullInfo.os());
        assertEquals(UserAgentInfo.UNKNOWN, nullInfo.osVersion());
        assertEquals(UserAgentInfo.UNKNOWN, nullInfo.deviceType());
        assertFalse(nullInfo.mobile());
        assertNull(nullInfo.rawUserAgent());

        UserAgentInfo blankInfo = UserAgentUtil.parse("   \r\n\t  ");
        assertEquals(UserAgentInfo.UNKNOWN, blankInfo.browser());
        assertEquals(UserAgentInfo.UNKNOWN, blankInfo.os());
        assertEquals("", blankInfo.rawUserAgent());

        // 控制字符注入防御
        String injected = "curl/8.5.0\r\nX-Fake: 1\t\u0000";
        String sanitized = UserAgentUtil.sanitize(injected, 512);
        assertNotNull(sanitized);
        assertFalse(sanitized.contains("\r"));
        assertFalse(sanitized.contains("\n"));
        assertFalse(sanitized.contains("\t"));
        assertFalse(sanitized.contains("\u0000"));
        assertEquals("curl/8.5.0X-Fake: 1", sanitized);

        // 超长防 ReDoS 截断
        String huge = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " + "x".repeat(5000) + " Chrome/126.0.0.0";
        UserAgentInfo hugeInfo = UserAgentUtil.parse(huge, 128);
        assertNotNull(hugeInfo.rawUserAgent());
        assertEquals(128, hugeInfo.rawUserAgent().length());
        assertEquals("Windows", hugeInfo.os());

        assertNull(UserAgentUtil.sanitize(null, 512));
        assertEquals("", UserAgentUtil.sanitize("curl/8.5.0", 0));
    }
}

package io.github.atengk.boot.core.util;

/**
 * 敏感数据通用脱敏工具类
 * 基于 Zero-Bloat 原则提供高性能、纯 JDK 实现的全场景数据动态掩码能力
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class DesensitizeUtil {

    private static final char DEFAULT_MASK_CHAR = '*';
    private static final String DEFAULT_PASSWORD_MASK = "******";

    private DesensitizeUtil() {
    }

    /**
     * 通用字符串指定区间字符掩码
     *
     * @param str          待脱敏原始字符串
     * @param startInclude 掩码起始索引（包含，从 0 开始；小于 0 则自动按 0 处理）
     * @param endExclude   掩码结束索引（不包含；大于字符串长度则自动按长度处理）
     * @param maskChar     用于替换的掩码字符（如 '*'）
     * @return 脱敏后的字符串；若原始字符串为 null 则返回 null，若为空串返回空串
     */
    public static String mask(String str, int startInclude, int endExclude, char maskChar) {
        if (str == null) {
            return null;
        }
        int len = str.length();
        if (len == 0) {
            return "";
        }

        int start = Math.max(0, startInclude);
        int end = Math.min(len, endExclude);
        if (start >= end || start >= len) {
            return str;
        }

        char[] chars = str.toCharArray();
        for (int i = start; i < end; i++) {
            chars[i] = maskChar;
        }
        return new String(chars);
    }

    /**
     * 通用字符串指定区间字符掩码（默认使用 '*' 掩码字符）
     *
     * @param str          待脱敏原始字符串
     * @param startInclude 掩码起始索引（包含）
     * @param endExclude   掩码结束索引（不包含）
     * @return 脱敏后的字符串
     */
    public static String mask(String str, int startInclude, int endExclude) {
        return mask(str, startInclude, endExclude, DEFAULT_MASK_CHAR);
    }

    /**
     * 中文姓名脱敏
     * 规则：单字姓名不脱敏；两字姓名脱敏后一字（如“张*”）；三字及以上保留首尾字，中间全部打码（如“李*四”、“诸**明”）
     *
     * @param name 中文姓名
     * @return 脱敏后的姓名
     */
    public static String chineseName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        int len = name.length();
        if (len <= 1) {
            return name;
        }
        if (len == 2) {
            return mask(name, 1, 2);
        }
        return mask(name, 1, len - 1);
    }

    /**
     * 手机号码脱敏（保留前 3 位与后 4 位，中间打码）
     * 例如："13812345678" -> "138****5678"
     *
     * @param phone 手机号码
     * @return 脱敏后的手机号码
     */
    public static String mobilePhone(String phone) {
        if (phone == null || phone.isEmpty()) {
            return phone;
        }
        int len = phone.length();
        if (len <= 7) {
            return mask(phone, Math.min(1, len), Math.max(1, len - 1));
        }
        return mask(phone, 3, len - 4);
    }

    /**
     * 固定电话脱敏（有分号时保留分号及之前区号，保留后 2 位，中间打码；无分号时保留前 4 位区号与后 2 位，中间打码）
     * 例如："010-12345678" -> "010-******78"，"0755-88889999" -> "0755-******99"
     *
     * @param phone 固定电话
     * @return 脱敏后的固定电话
     */
    public static String fixedPhone(String phone) {
        if (phone == null || phone.isEmpty()) {
            return phone;
        }
        int len = phone.length();
        int hyphenIndex = phone.indexOf('-');
        int start = hyphenIndex > 0 ? hyphenIndex + 1 : Math.min(4, len);
        int end = Math.max(start, len - 2);
        return mask(phone, start, end);
    }

    /**
     * 身份证号码脱敏（保留前 6 位与后 4 位，中间打码）
     * 例如："110101199003072345" -> "110101********2345"
     *
     * @param idCard 身份证号码
     * @return 脱敏后的身份证号码
     */
    public static String idCard(String idCard) {
        if (idCard == null || idCard.isEmpty()) {
            return idCard;
        }
        int len = idCard.length();
        if (len <= 10) {
            return mask(idCard, Math.min(2, len), Math.max(2, len - 2));
        }
        return mask(idCard, 6, len - 4);
    }

    /**
     * 电子邮箱脱敏
     * 规则：保留前 1 位和 '@' 前最后 1 位，中间打码；若用户名较短则做防御性打码
     * 例如："zhangsan@example.com" -> "z******n@example.com"
     *
     * @param email 电子邮箱
     * @return 脱敏后的电子邮箱
     */
    public static String email(String email) {
        if (email == null || email.isEmpty()) {
            return email;
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) {
            return email;
        }
        if (atIndex == 2) {
            return mask(email, 1, 2);
        }
        return mask(email, 1, atIndex - 1);
    }

    /**
     * 银行卡号脱敏（保留前 6 位与后 4 位，中间打码）
     * 例如："6222021234567890123" -> "622202*********0123"
     *
     * @param card 银行卡号
     * @return 脱敏后的银行卡号
     */
    public static String bankCard(String card) {
        if (card == null || card.isEmpty()) {
            return card;
        }
        int len = card.length();
        if (len <= 10) {
            return mask(card, Math.min(2, len), Math.max(2, len - 2));
        }
        return mask(card, 6, len - 4);
    }

    /**
     * 密码脱敏（返回固定 6 位 '*'，防止暴露密码实际字符长度）
     *
     * @param password 原始密码
     * @return 脱敏后的密码占位符（默认 "******"）
     */
    public static String password(String password) {
        return password(password, DEFAULT_PASSWORD_MASK);
    }

    /**
     * 密码自定义占位符脱敏
     *
     * @param password 原始密码
     * @param mask     自定义密码掩码占位符（如 "######"）；若为 null 则使用默认 "******"
     * @return 脱敏后的密码占位符
     */
    public static String password(String password, String mask) {
        if (password == null) {
            return null;
        }
        if (password.isEmpty()) {
            return "";
        }
        return mask != null ? mask : DEFAULT_PASSWORD_MASK;
    }

    /**
     * 国内地址脱敏
     * 规则：保留前 6 位省市区信息，后续详细门牌号等长替换为 '*'；对于 <= 6 且 > 2 字符的短地址保留前 2 位；<= 2 字符则原样防御返回
     *
     * @param address 原始地址字符串
     * @return 脱敏后的地址字符串
     */
    public static String address(String address) {
        if (address == null || address.isEmpty()) {
            return address;
        }
        int len = address.length();
        if (len <= 2) {
            return address;
        }
        if (len <= 6) {
            return mask(address, 2, len);
        }
        return mask(address, 6, len);
    }

    /**
     * 车牌号码脱敏
     * 规则：保留前 2 位（省份与代号，如“京A”）与后 2 位，中间字符等长替换为 '*'；穿透保留点号或短横线等间隔符；兼容 7 位传统车牌与 8 位新能源车牌
     *
     * @param carLicense 车牌号码
     * @return 脱敏后的车牌号码
     */
    public static String carLicense(String carLicense) {
        if (carLicense == null || carLicense.isEmpty()) {
            return carLicense;
        }
        int len = carLicense.length();
        if (len <= 4) {
            return carLicense;
        }
        char[] chars = carLicense.toCharArray();
        int start = 2;
        int end = len - 2;
        for (int i = start; i < end; i++) {
            char c = chars[i];
            if (c != '·' && c != '-' && c != ' ' && c != '.') {
                chars[i] = DEFAULT_MASK_CHAR;
            }
        }
        return new String(chars);
    }

    /**
     * IPv4 地址脱敏（默认掩码末位段）
     * 例如："192.168.1.100" -> "192.168.1.*"
     *
     * @param ip IPv4 地址字符串
     * @return 脱敏后的 IPv4 地址
     */
    public static String ipv4(String ip) {
        return ipv4(ip, false);
    }

    /**
     * IPv4 地址脱敏
     *
     * @param ip              IPv4 地址字符串
     * @param maskTwoSegments 是否掩码最后两段（true 为 192.168.*.*，false 为 192.168.1.*）
     * @return 脱敏后的 IPv4 地址
     */
    public static String ipv4(String ip, boolean maskTwoSegments) {
        if (ip == null || ip.isEmpty()) {
            return ip;
        }
        int dotCount = 0;
        int firstDot = -1;
        int secondDot = -1;
        int thirdDot = -1;
        int len = ip.length();
        for (int i = 0; i < len; i++) {
            if (ip.charAt(i) == '.') {
                dotCount++;
                if (firstDot < 0) {
                    firstDot = i;
                } else if (secondDot < 0) {
                    secondDot = i;
                } else if (thirdDot < 0) {
                    thirdDot = i;
                }
            }
        }
        if (dotCount != 3 || firstDot == 0 || thirdDot == len - 1) {
            return ip;
        }
        if (!maskTwoSegments) {
            return ip.substring(0, thirdDot + 1) + "*";
        }
        return ip.substring(0, secondDot + 1) + "*.*";
    }

    /**
     * IPv6 地址脱敏
     * 规则：保留首段与尾段，中间所有段压缩替换为 '****'
     * 例如："2001:0db8:85a3:0000:0000:8a2e:0370:7334" -> "2001:****:7334"，"fe80::1" -> "fe80:****:1"
     *
     * @param ip IPv6 地址字符串
     * @return 脱敏后的 IPv6 地址
     */
    public static String ipv6(String ip) {
        if (ip == null || ip.isEmpty()) {
            return ip;
        }
        int firstColon = ip.indexOf(':');
        int lastColon = ip.lastIndexOf(':');
        if (firstColon <= 0 || lastColon <= firstColon) {
            return ip;
        }
        String first = ip.substring(0, firstColon);
        String last = ip.substring(lastColon + 1);
        return first + ":****:" + last;
    }

    /**
     * 统一社会信用代码脱敏
     * 规则：标准 18 位代码保留前 2 位登记管理部门与机构类别代码、后 4 位校验码，中间 12 位等长替换为 '*'；非 18 位代码做防御性首尾掩码
     * 例如："91110000MA001ABC12" -> "91************BC12"
     *
     * @param code 统一社会信用代码
     * @return 脱敏后的信用代码
     */
    public static String socialCreditCode(String code) {
        if (code == null || code.isEmpty()) {
            return code;
        }
        int len = code.length();
        if (len <= 6) {
            return code;
        }
        return mask(code, 2, len - 4);
    }

    /**
     * 统一社会信用代码脱敏（{@link #socialCreditCode(String)} 规范命名别名）
     * 规则：标准 18 位代码保留前 2 位登记管理部门与机构类别代码、后 4 位校验码，中间 12 位等长替换为 '*'
     * 例如："91110000MA001ABC12" -> "91************BC12"
     *
     * @param code 统一社会信用代码
     * @return 脱敏后的信用代码
     */
    public static String unifiedCreditCode(String code) {
        return socialCreditCode(code);
    }

    /**
     * 密钥 / AccessToken 脱敏
     * 规则：保留前 4 位与后 4 位，中间全部等长替换为 '*'；长度不足或等于 8 位时做全掩码
     * 例如："sk-proj-1234567890abcdef" -> "sk-p****************def"
     *
     * @param key 待脱敏的密钥明文
     * @return 脱敏后的密钥字符串
     */
    public static String secretKey(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        int len = key.length();
        if (len <= 8) {
            return mask(key, 0, len);
        }
        return mask(key, 4, len - 4);
    }

    /**
     * 护照号码脱敏
     * 规则：保留前 2 位与后 3 位，中间全部等长替换为 '*'；长度不足或等于 5 位时做防御性掩码
     * 例如："E12345678" -> "E1****678"
     *
     * @param passport 护照号码
     * @return 脱敏后的护照号码
     */
    public static String passport(String passport) {
        if (passport == null || passport.isEmpty()) {
            return passport;
        }
        int len = passport.length();
        if (len <= 5) {
            return mask(passport, 0, len);
        }
        return mask(passport, 2, len - 3);
    }

    /**
     * 字符串保留首尾指定位数，中间等长掩码
     * 契约：当保留字符数之和超过或等于原字符串长度（keepHead + keepTail >= length）时，原样防御返回
     *
     * @param str      原始字符串
     * @param keepHead 头部保留字符数（小于 0 按 0 处理）
     * @param keepTail 尾部保留字符数（小于 0 按 0 处理）
     * @param maskChar 用于替换的掩码字符
     * @return 脱敏后的字符串
     */
    public static String maskKeepHeadTail(String str, int keepHead, int keepTail, char maskChar) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        int head = Math.max(0, keepHead);
        int tail = Math.max(0, keepTail);
        int len = str.length();
        if (head + tail >= len) {
            return str;
        }
        return mask(str, head, len - tail, maskChar);
    }

    /**
     * 字符串保留首尾指定位数，中间使用默认 '*' 掩码
     *
     * @param str      原始字符串
     * @param keepHead 头部保留字符数
     * @param keepTail 尾部保留字符数
     * @return 脱敏后的字符串
     */
    public static String maskKeepHeadTail(String str, int keepHead, int keepTail) {
        return maskKeepHeadTail(str, keepHead, keepTail, DEFAULT_MASK_CHAR);
    }

    /**
     * 字符串保留首尾指定位数，中间使用默认 '*' 掩码（{@link #maskKeepHeadTail(String, int, int)} 别名）
     *
     * @param str      原始字符串
     * @param keepHead 头部保留字符数
     * @param keepTail 尾部保留字符数
     * @return 脱敏后的字符串
     */
    public static String keepHeadAndTail(String str, int keepHead, int keepTail) {
        return maskKeepHeadTail(str, keepHead, keepTail);
    }

    /**
     * 字符串保留首尾指定位数，中间使用指定掩码字符（{@link #maskKeepHeadTail(String, int, int, char)} 别名）
     *
     * @param str      原始字符串
     * @param keepHead 头部保留字符数
     * @param keepTail 尾部保留字符数
     * @param maskChar 自定义掩码字符
     * @return 脱敏后的字符串
     */
    public static String keepHeadAndTail(String str, int keepHead, int keepTail, char maskChar) {
        return maskKeepHeadTail(str, keepHead, keepTail, maskChar);
    }

    /**
     * 字符串保留头部指定位数，后部全部掩码
     *
     * @param str      原始字符串
     * @param keepHead 头部保留字符数
     * @return 脱敏后的字符串
     */
    public static String maskKeepHead(String str, int keepHead) {
        return maskKeepHeadTail(str, keepHead, 0);
    }

    /**
     * 字符串保留尾部指定位数，前部全部掩码
     *
     * @param str      原始字符串
     * @param keepTail 尾部保留字符数
     * @return 脱敏后的字符串
     */
    public static String maskKeepTail(String str, int keepTail) {
        return maskKeepHeadTail(str, 0, keepTail);
    }
}

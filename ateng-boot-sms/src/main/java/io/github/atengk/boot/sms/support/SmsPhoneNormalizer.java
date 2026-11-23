package io.github.atengk.boot.sms.support;

import io.github.atengk.boot.sms.model.SmsChannel;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 国际手机号 E.164 归一化与多云厂商出站号段协议适配工具
 * 避免无条件硬拼 +86 对跨境号码造成破坏，按厂商协议精确保留或剥离国际区号前缀
 *
 * @author Ateng
 * @since 2026-11-21
 */
public final class SmsPhoneNormalizer {

    private static final String DEFAULT_REGION_CODE = "+86";
    private static final int CHINA_LOCAL_LENGTH = 11;
    private static final int CHINA_WITH_CODE_LENGTH = 13;

    private SmsPhoneNormalizer() {
    }

    /**
     * 基于渠道国际区号配置将原始手机号归一化为标准 E.164 格式（例如 {@code +8613800138000}、{@code +85291234567}）
     *
     * @param phone   原始手机号
     * @param channel 目标短信渠道（可为 null）
     * @return E.164 格式手机号；空白输入返回空串
     */
    public static String toE164(String phone, SmsChannel channel) {
        String defaultRegion = resolveDefaultRegionCode(channel);
        return toE164(phone, defaultRegion);
    }

    /**
     * 基于显式默认国家区号将原始手机号归一化为标准 E.164 格式
     *
     * @param phone             原始手机号
     * @param defaultRegionCode 默认国家区号（如 +86、+852、+1）
     * @return E.164 格式手机号；空白输入返回空串
     */
    public static String toE164(String phone, String defaultRegionCode) {
        String clean = stripDelimiters(phone);
        if (clean.isEmpty()) {
            return "";
        }
        if (clean.startsWith("+")) {
            return clean;
        }
        if (clean.startsWith("00")) {
            return "+" + clean.substring(2);
        }

        String normalizedRegion = normalizeRegionCode(defaultRegionCode);
        if (DEFAULT_REGION_CODE.equals(normalizedRegion)
                && clean.startsWith("86")
                && clean.length() == CHINA_WITH_CODE_LENGTH) {
            return "+" + clean;
        }
        return normalizedRegion + clean;
    }

    /**
     * 转换为阿里云 Dysmsapi 协议要求手机号格式
     * 国内号段（+86 且为 11 位）输出纯 11 位国内号码（如 {@code 13800138000}）；
     * 国际/港澳台号段输出“国际区号 + 号码”且严格不带 {@code +} 或 {@code 00} 前缀（如 {@code 85291234567}）
     *
     * @param phone   原始手机号
     * @param channel 目标短信渠道
     * @return 阿里云协议格式手机号
     */
    public static String toAliyunFormat(String phone, SmsChannel channel) {
        String stripped = stripDomestic86Prefix(toE164(phone, channel));
        if (stripped.isEmpty()) {
            return "";
        }
        return stripped.startsWith("+") ? stripped.substring(1) : stripped;
    }

    /**
     * 转换为国内直连或国际标准 E.164 格式（适用于华为云、云片等国内 11 位免前缀、国际带 + 的网关）
     * 国内号段（+86 且为 11 位）输出 {@code 13800138000}；国际号段输出完整 E.164 {@code +85291234567}
     *
     * @param phone   原始手机号
     * @param channel 目标短信渠道
     * @return 适配后的手机号
     */
    public static String toDomesticOrE164(String phone, SmsChannel channel) {
        return stripDomestic86Prefix(toE164(phone, channel));
    }

    /**
     * 批量将手机号列表转换为国内直连或国际标准 E.164 格式（适用于阿里外的国内 11 位免前缀/国际 E.164 网关）
     *
     * @param phones  原始手机号列表
     * @param channel 目标短信渠道
     * @return 格式化后的手机号列表（永不返回 null）
     */
    public static List<String> toDomesticOrE164List(List<String> phones, SmsChannel channel) {
        if (phones == null || phones.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(phones.size());
        for (String phone : phones) {
            result.add(toDomesticOrE164(phone, channel));
        }
        return result;
    }

    /**
     * 批量将手机号列表统一转换为标准 E.164 格式
     *
     * @param phones  原始手机号列表
     * @param channel 目标短信渠道
     * @return 标准 E.164 手机号列表（永不返回 null）
     */
    public static List<String> toE164List(List<String> phones, SmsChannel channel) {
        if (phones == null || phones.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(phones.size());
        for (String phone : phones) {
            result.add(toE164(phone, channel));
        }
        return result;
    }

    private static String stripDomestic86Prefix(String e164) {
        if (e164 == null || e164.isEmpty()) {
            return "";
        }
        if (e164.startsWith("+86") && e164.length() == CHINA_LOCAL_LENGTH + 3) {
            return e164.substring(3);
        }
        return e164;
    }

    private static String resolveDefaultRegionCode(SmsChannel channel) {
        if (channel == null || !channel.isInternationalEnabled() || !StringUtils.hasText(channel.getDefaultRegionCode())) {
            return DEFAULT_REGION_CODE;
        }
        return normalizeRegionCode(channel.getDefaultRegionCode());
    }

    private static String normalizeRegionCode(String regionCode) {
        if (!StringUtils.hasText(regionCode)) {
            return DEFAULT_REGION_CODE;
        }
        String clean = stripDelimiters(regionCode);
        if (clean.startsWith("+")) {
            return clean;
        }
        if (clean.startsWith("00")) {
            return "+" + clean.substring(2);
        }
        return "+" + clean;
    }

    private static String stripDelimiters(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim()
                .replace("-", "")
                .replace(" ", "")
                .replace("(", "")
                .replace(")", "");
    }
}

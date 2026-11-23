package io.github.atengk.boot.sms.support;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.sms.model.BatchSmsRequest;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsParam;
import io.github.atengk.boot.sms.model.SmsRequest;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 短信内容与敏感变量脱敏支撑工具
 * <p>
 * 保障业务对象内存与发往云厂商 HTTP 报文 100% 保持真明文，
 * 仅在日志输出及审计事件快照中按配置执行纯文本正文与敏感变量掩码遮罩。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
public final class SmsDesensitizeSupport {

    /**
     * 默认敏感参数关键词集合
     */
    public static final List<String> DEFAULT_SENSITIVE_PARAM_KEYS = List.of(
            "code", "captcha", "pwd", "password", "pin", "token", "secret"
    );

    private static final String MASKED_VALUE = "******";

    private SmsDesensitizeSupport() {
    }

    /**
     * 对纯文本短信正文执行首尾保留的中间全掩码脱敏
     *
     * @param content 原始纯文本正文
     * @return 脱敏后的正文文本
     */
    public static String maskContent(String content) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        String trimmed = content.trim();
        int len = trimmed.length();
        if (len <= 6) {
            return MASKED_VALUE;
        }
        return DesensitizeUtil.maskKeepHeadTail(trimmed, 2, 2);
    }

    /**
     * 对手机号集合进行脱敏拼接（仅用于日志输出）
     *
     * @param phones 手机号列表
     * @return 脱敏后的手机号摘要字符串
     */
    public static String maskPhones(List<String> phones) {
        if (phones == null || phones.isEmpty()) {
            return "[]";
        }
        return phones.stream()
                .map(DesensitizeUtil::mobilePhone)
                .toList()
                .toString();
    }

    /**
     * 判断参数键名是否匹配敏感关键词规则（不区分大小写，支持包含匹配）
     *
     * @param key                参数键名
     * @param sensitiveParamKeys 敏感关键词列表
     * @return true 表示命中敏感参数
     */
    public static boolean isSensitiveKey(String key, List<String> sensitiveParamKeys) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        List<String> keys = (sensitiveParamKeys != null && !sensitiveParamKeys.isEmpty())
                ? sensitiveParamKeys
                : DEFAULT_SENSITIVE_PARAM_KEYS;
        String lowerKey = key.trim().toLowerCase(Locale.ROOT);
        for (String sensitive : keys) {
            if (StringUtils.hasText(sensitive) && lowerKey.contains(sensitive.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对短信模板参数中的敏感变量值执行脱敏，返回全新快照实例（绝不污染原始对象内存明文）
     *
     * @param params             原始模板参数模型
     * @param paramOrder         模板参数顺序声明（用于数组模式按位识别敏感键名，可为 null）
     * @param sensitiveParamKeys 敏感参数键名列表
     * @return 脱敏后的 SmsParam 新实例
     */
    public static SmsParam maskSensitiveParams(SmsParam params,
                                               List<String> paramOrder,
                                               List<String> sensitiveParamKeys) {
        if (params == null || params.isEmpty()) {
            return params;
        }
        if (!params.isArrayMode()) {
            Map<String, Object> sourceMap = params.getMapParams();
            Map<String, Object> maskedMap = new LinkedHashMap<>(sourceMap.size());
            sourceMap.forEach((k, v) -> {
                if (v != null && isSensitiveKey(k, sensitiveParamKeys)) {
                    maskedMap.put(k, DesensitizeUtil.password(String.valueOf(v)));
                } else {
                    maskedMap.put(k, v);
                }
            });
            return SmsParam.ofMap(maskedMap);
        }

        List<String> arrayValues = params.getArrayParams();
        List<String> maskedArray = new ArrayList<>(arrayValues.size());
        for (int i = 0; i < arrayValues.size(); i++) {
            String value = arrayValues.get(i);
            String mappedKey = (paramOrder != null && i < paramOrder.size()) ? paramOrder.get(i) : null;
            if (value != null && mappedKey != null && isSensitiveKey(mappedKey, sensitiveParamKeys)) {
                maskedArray.add(DesensitizeUtil.password(value));
            } else {
                maskedArray.add(value);
            }
        }
        return SmsParam.ofArray(maskedArray);
    }

    /**
     * 构造用于审计事件快照的脱敏单条请求副本（不修改原始请求对象）
     *
     * @param request            原始请求
     * @param channel            目标渠道（用于解析模板参数顺序，可为 null）
     * @param sensitiveParamKeys 敏感参数关键词集合
     * @return 脱敏后的 SmsRequest 快照
     */
    public static SmsRequest sanitizeForAudit(SmsRequest request,
                                              SmsChannel channel,
                                              List<String> sensitiveParamKeys) {
        if (request == null) {
            return null;
        }
        List<String> paramOrder = resolveParamOrder(channel, request.getTemplateId());
        SmsParam sanitizedParams = maskSensitiveParams(request.getParams(), paramOrder, sensitiveParamKeys);
        String sanitizedContent = maskContent(request.getContent());
        return SmsRequest.builder()
                .phone(request.getPhone())
                .phones(request.getPhones())
                .templateId(request.getTemplateId())
                .params(sanitizedParams)
                .signName(request.getSignName())
                .channel(request.getChannel())
                .bizId(request.getBizId())
                .urgent(request.isUrgent())
                .content(sanitizedContent)
                .build();
    }

    /**
     * 构造用于审计事件快照的脱敏批量请求副本（不修改原始批量请求对象）
     *
     * @param request            原始批量请求
     * @param channel            目标渠道（可为 null）
     * @param sensitiveParamKeys 敏感参数关键词集合
     * @return 脱敏后的 BatchSmsRequest 快照
     */
    public static BatchSmsRequest sanitizeForAudit(BatchSmsRequest request,
                                                   SmsChannel channel,
                                                   List<String> sensitiveParamKeys) {
        if (request == null) {
            return null;
        }
        List<String> paramOrder = resolveParamOrder(channel, request.getTemplateId());
        SmsParam sanitizedParams = maskSensitiveParams(request.getParams(), paramOrder, sensitiveParamKeys);
        Map<String, SmsParam> sanitizedPhoneParams = Collections.emptyMap();
        if (request.isPersonalized()) {
            Map<String, SmsParam> map = new LinkedHashMap<>(request.getPhoneParams().size());
            request.getPhoneParams().forEach((phone, param) ->
                    map.put(phone, maskSensitiveParams(param, paramOrder, sensitiveParamKeys)));
            sanitizedPhoneParams = map;
        }
        String sanitizedContent = maskContent(request.getContent());
        return BatchSmsRequest.builder()
                .phones(request.getPhones())
                .templateId(request.getTemplateId())
                .params(sanitizedParams)
                .phoneParams(sanitizedPhoneParams)
                .signName(request.getSignName())
                .channel(request.getChannel())
                .bizId(request.getBizId())
                .urgent(request.isUrgent())
                .content(sanitizedContent)
                .build();
    }

    private static List<String> resolveParamOrder(SmsChannel channel, String templateId) {
        if (channel == null) {
            return Collections.emptyList();
        }
        String effectiveId = channel.resolveTemplateId(templateId);
        List<String> order = channel.getParamOrder(effectiveId);
        if (order.isEmpty() && StringUtils.hasText(templateId)) {
            order = channel.getParamOrder(templateId);
        }
        return order;
    }
}

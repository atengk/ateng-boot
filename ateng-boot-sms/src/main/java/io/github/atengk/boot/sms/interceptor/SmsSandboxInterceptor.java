package io.github.atengk.boot.sms.interceptor;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.sms.exception.SmsResultCode;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.model.BatchSmsRequest;
import io.github.atengk.boot.sms.model.BatchSmsResult;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsParam;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.properties.SmsGuardProperties;
import io.github.atengk.boot.sms.properties.SmsSandboxProperties;
import io.github.atengk.boot.sms.support.SmsDesensitizeSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.util.PatternMatchUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 短信测试安全沙箱内置高优先级拦截器
 * <p>
 * 默认设定为框架最高优先级 ({@link Ordered#HIGHEST_PRECEDENCE})，在非生产环境提供三重保护：
 * 1. {@code log-only=true}：零外部网络调用，打印脱敏模拟日志并返回模拟成功回执；
 * 2. {@code allowed-phones}：支持通配符（如 {@code 13800138*}）白名单精确或前缀穿透放行；
 * 3. {@code redirect-to}：非白名单号码重定向至测试手机，批量含非白名单号码时自动合并去重仅投递 1 次防轰炸；
 * 若存在非白名单号码且未配置 {@code redirect-to}，立即 Fail-Fast 阻断并抛出异常告警。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class SmsSandboxInterceptor implements SmsSendInterceptor {

    private final SmsSandboxProperties properties;
    private final SmsGuardProperties guardProperties;

    public SmsSandboxInterceptor(SmsSandboxProperties properties, SmsGuardProperties guardProperties) {
        this.properties = properties != null ? properties : new SmsSandboxProperties();
        this.guardProperties = guardProperties != null ? guardProperties : new SmsGuardProperties();
    }

    public SmsSandboxInterceptor(SmsSandboxProperties properties) {
        this(properties, new SmsGuardProperties());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public boolean preSend(SmsSendContext context) {
        if (!properties.isEnabled() || context == null) {
            return true;
        }

        SmsChannel channel = context.getChannel();
        String channelName = channel != null ? channel.getName() : "mock";
        List<String> phones = context.getPhones();

        // 1. 纯日志模拟发信模式：零外发并注入模拟成功回执
        if (properties.isLogOnly()) {
            logDesensitizedSimulation(context, channelName, phones);
            String bizId = "SANDBOX_LOG_" + IdUtil.fastSimpleUUID();
            String requestId = "SANDBOX_REQ_" + IdUtil.fastSimpleUUID();
            SmsResult mockResult = SmsResult.mock(channelName, bizId, requestId);
            if (context.isBatch()) {
                context.setShortCircuitBatchResult(BatchSmsResult.of(
                        phones.size(), phones.size(), Collections.emptyList(), Collections.emptyList(), List.of(mockResult)
                ));
            } else {
                context.setShortCircuitResult(mockResult);
            }
            return false;
        }

        // 2. 通配符白名单校验：全部号码均匹配白名单时无损穿透放行
        List<String> nonAllowedPhones = new ArrayList<>();
        List<String> allowedPhones = new ArrayList<>();
        for (String phone : phones) {
            if (isPhoneAllowed(phone)) {
                allowedPhones.add(phone);
            } else {
                nonAllowedPhones.add(phone);
            }
        }
        if (nonAllowedPhones.isEmpty()) {
            return true;
        }

        // 3. 含非白名单号码时检查重定向目标
        String redirectTo = properties.getRedirectTo();
        if (!StringUtils.hasText(redirectTo)) {
            log.error("[SMS-SANDBOX] 沙箱已阻断非白名单短信外发（未配置 ateng.boot.sms.sandbox.redirect-to）-> 渠道: {}, 拦截号码: {}",
                    channelName, SmsDesensitizeSupport.maskPhones(nonAllowedPhones));
            throw new SmsSendException(channelName, "SANDBOX_BLOCKED",
                    "短信测试沙箱已拦截非白名单手机号，未配置 redirect-to",
                    SmsResultCode.SMS_SEND_ERROR,
                    "短信测试沙箱已拦截非白名单手机号，但未配置 ateng.boot.sms.sandbox.redirect-to 重定向手机号");
        }

        String target = redirectTo.trim();
        if (!context.isBatch()) {
            SmsRequest req = context.getRequest();
            if (allowedPhones.isEmpty()) {
                log.warn("[SMS-SANDBOX-REDIRECT] 非白名单单条短信已重定向 -> 原号码: {}, 测试目标: {}, 渠道: {}",
                        DesensitizeUtil.mobilePhone(req.getPhone()), DesensitizeUtil.mobilePhone(target), channelName);
                context.setRequest(SmsRequest.builder()
                        .phone(target)
                        .templateId(req.getTemplateId())
                        .params(req.getParams())
                        .signName(req.getSignName())
                        .channel(req.getChannel())
                        .bizId(req.getBizId())
                        .urgent(req.isUrgent())
                        .content(req.getContent())
                        .skipRateLimit(req.isSkipRateLimit())
                        .build());
                return true;
            }
            Set<String> deduplicatedSingle = new LinkedHashSet<>(allowedPhones);
            deduplicatedSingle.add(target);
            List<String> finalSinglePhones = new ArrayList<>(deduplicatedSingle);
            log.warn("[SMS-SANDBOX-REDIRECT] 混合号码单发短信已保留白名单并合并重定向 -> 原号码数: {}, 非白名单数: {}, 重定向后号码数: {}, 测试目标: {}, 渠道: {}",
                    phones.size(), nonAllowedPhones.size(), finalSinglePhones.size(), DesensitizeUtil.mobilePhone(target), channelName);
            context.setRequest(SmsRequest.builder()
                    .phones(finalSinglePhones)
                    .templateId(req.getTemplateId())
                    .params(req.getParams())
                    .signName(req.getSignName())
                    .channel(req.getChannel())
                    .bizId(req.getBizId())
                    .urgent(req.isUrgent())
                    .content(req.getContent())
                    .skipRateLimit(req.isSkipRateLimit())
                    .build());
            return true;
        }

        // 批量群发含非白名单号码时合并去重，非白名单部分仅向测试手机投递 1 次防轰炸
        BatchSmsRequest batchReq = context.getBatchRequest();
        Set<String> deduplicated = new LinkedHashSet<>(allowedPhones);
        deduplicated.add(target);
        List<String> finalPhones = new ArrayList<>(deduplicated);

        Map<String, SmsParam> finalPhoneParams = Collections.emptyMap();
        if (batchReq.isPersonalized()) {
            Map<String, SmsParam> rebuilt = new LinkedHashMap<>();
            for (String allowed : allowedPhones) {
                rebuilt.put(allowed, batchReq.getPhoneParams().get(allowed));
            }
            if (!rebuilt.containsKey(target)) {
                SmsParam sampleParam = batchReq.getPhoneParams().get(nonAllowedPhones.get(0));
                rebuilt.put(target, sampleParam != null ? sampleParam : batchReq.getParams());
            }
            finalPhoneParams = rebuilt;
        }

        log.warn("[SMS-SANDBOX-REDIRECT] 非白名单批量短信已合并去重重定向 -> 原号码数: {}, 非白名单数: {}, 重定向后号码数: {}, 测试目标: {}, 渠道: {}",
                phones.size(), nonAllowedPhones.size(), finalPhones.size(), DesensitizeUtil.mobilePhone(target), channelName);

        context.setBatchRequest(BatchSmsRequest.builder()
                .phones(finalPhones)
                .templateId(batchReq.getTemplateId())
                .params(batchReq.getParams())
                .phoneParams(finalPhoneParams)
                .signName(batchReq.getSignName())
                .channel(batchReq.getChannel())
                .bizId(batchReq.getBizId())
                .urgent(batchReq.isUrgent())
                .content(batchReq.getContent())
                .build());
        return true;
    }

    /**
     * 校验指定手机号是否命中沙箱白名单规则（支持精确与通配符匹配）
     *
     * @param phone 待校验手机号
     * @return true 表示命中白名单
     */
    public boolean isPhoneAllowed(String phone) {
        if (!StringUtils.hasText(phone)) {
            return false;
        }
        List<String> whitelist = properties.getAllowedPhones();
        if (whitelist == null || whitelist.isEmpty()) {
            return false;
        }
        String trimmed = phone.trim();
        for (String pattern : whitelist) {
            if (StringUtils.hasText(pattern) && PatternMatchUtils.simpleMatch(pattern.trim(), trimmed)) {
                return true;
            }
        }
        return false;
    }

    private void logDesensitizedSimulation(SmsSendContext context, String channelName, List<String> phones) {
        boolean desensitize = guardProperties.isLogDesensitizeContent();
        List<String> keys = guardProperties.getSensitiveParamKeys();
        String maskedPhones = SmsDesensitizeSupport.maskPhones(phones);
        String templateId = context.isBatch() ? context.getBatchRequest().getTemplateId() : context.getRequest().getTemplateId();
        String rawContent = context.isBatch() ? context.getBatchRequest().getContent() : context.getRequest().getContent();
        SmsParam rawParams = context.isBatch() ? context.getBatchRequest().getParams() : context.getRequest().getParams();

        String loggedContent = desensitize ? SmsDesensitizeSupport.maskContent(rawContent) : rawContent;
        SmsParam loggedParams = desensitize ? SmsDesensitizeSupport.maskSensitiveParams(rawParams, null, keys) : rawParams;
        log.info("[SMS-SANDBOX-LOG-ONLY] 模拟{}发信（零物理外发）-> 渠道: {}, 号码: {}, 模板: {}, 参数: {}, 正文: {}",
                context.isBatch() ? "批量" : "单条", channelName, maskedPhones, templateId, loggedParams, loggedContent);
    }
}

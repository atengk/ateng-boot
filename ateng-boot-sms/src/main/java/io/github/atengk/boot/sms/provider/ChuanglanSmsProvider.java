package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sms.exception.SmsResultCode;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.support.SmsPhoneNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * 创蓝 253 云通讯短信驱动实现 (Chuanglan 253)
 * 100% 纯原生支持账号密码 JSON 普通群发 ({@code /msg/v1/send/json}) 与变量模板群发 ({@code /msg/variable/json}) 双模协议
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class ChuanglanSmsProvider extends AbstractHttpSmsProvider {

    /**
     * 默认创蓝 253 官方公网接入端点
     */
    public static final String DEFAULT_ENDPOINT = "https://smssh1.253.com";
    private static final String PREFIX_MSG = "/msg";
    private static final String ACTION_PLAIN = "/v1/send/json";
    private static final String ACTION_VARIABLE = "/variable/json";

    public ChuanglanSmsProvider(RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
    }

    public ChuanglanSmsProvider() {
        this(RestClient.builder());
    }

    @Override
    public SmsSupplier getSupplier() {
        return SmsSupplier.CHUANGLAN;
    }

    @Override
    public SmsResult send(SmsRequest request, SmsChannel channel) {
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        BusinessAssert.notNull(channel, "短信渠道 SmsChannel 不能为空");
        BusinessAssert.hasText(channel.getAccessKeyId(), "创蓝 253 短信账号 account (accessKeyId) 不能为空");
        BusinessAssert.hasText(channel.getAccessKeySecret(), "创蓝 253 短信密码 password (accessKeySecret) 不能为空");
        BusinessAssert.hasText(request.getPhone(), "接收短信的目标手机号不能为空");

        boolean isPlainText = !StringUtils.hasText(request.getTemplateId()) && StringUtils.hasText(request.getContent());
        String signName = channel.resolveSignName(request.getTemplateId(), request.getSignName());

        List<String> formattedPhones = SmsPhoneNormalizer.toDomesticOrE164List(request.getPhones(), channel);

        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put("account", channel.getAccessKeyId().trim());
        bodyMap.put("password", channel.getAccessKeySecret().trim());
        String report = "false".equalsIgnoreCase(channel.getExtra("report")) ? "false" : "true";
        bodyMap.put("report", report);

        if (isPlainText) {
            bodyMap.put("msg", prependSignIfAbsent(signName, request.getContent()));
            bodyMap.put("phone", String.join(",", formattedPhones));
        } else {
            BusinessAssert.hasText(request.getTemplateId(), "创蓝 253 短信模板内容/编码或纯文本正文不能同时为空");
            bodyMap.put("msg", prependSignIfAbsent(signName, request.getTemplateId(),
                    "发送创蓝 253 变量模板短信时必须在模板内容携带【签名】或在渠道/请求中指定 signName"));
            List<String> orderedParams = request.getParams() != null
                    ? request.getParams().toOrderedArray(channel.getParamOrder(request.getTemplateId()))
                    : Collections.emptyList();
            String joinedVars = orderedParams.isEmpty() ? "" : "," + String.join(",", orderedParams);
            StringBuilder paramsBuilder = new StringBuilder();
            for (int i = 0; i < formattedPhones.size(); i++) {
                if (i > 0) {
                    paramsBuilder.append(';');
                }
                paramsBuilder.append(formattedPhones.get(i)).append(joinedVars);
            }
            bodyMap.put("params", paramsBuilder.toString());
        }

        if (StringUtils.hasText(channel.getExtCode())) {
            bodyMap.put("extend", channel.getExtCode().trim());
        }
        if (StringUtils.hasText(request.getBizId())) {
            bodyMap.put("uid", request.getBizId().trim());
        }

        String url = resolveActionUrlWithPrefix(
                normalizeEndpoint(channel.getEndpoint(), DEFAULT_ENDPOINT),
                PREFIX_MSG,
                isPlainText ? ACTION_PLAIN : ACTION_VARIABLE
        );
        String payloadJson = JsonUtil.toJsonString(bodyMap);

        String responseBody = executePostWithRetry(
                channel, "创蓝253", isPlainText ? "sendPlainSms" : "sendVariableSms",
                url, "application/json;charset=utf-8", null, payloadJson,
                httpEx -> throwJsonHttpErrorIfPresent(channel, "创蓝253", httpEx, "code", "errorMsg")
        );

        return parseResponse(channel.getName(), request.getPhone(),
                isPlainText ? "[PLAIN_TEXT]" : request.getTemplateId(), responseBody, formattedPhones.size());
    }

    private SmsResult parseResponse(String channelName, String phone, String templateLabel, String responseBody, int phoneCount) {
        Map<String, Object> root = parseJsonSafely(responseBody);
        String code = root.get("code") != null ? String.valueOf(root.get("code")) : "UNKNOWN";
        if (!"0".equals(code)) {
            String errMsg = root.get("errorMsg") != null ? String.valueOf(root.get("errorMsg")) : "Chuanglan SMS failed";
            throw new SmsSendException(channelName, code, errMsg, SmsResultCode.SMS_SEND_ERROR,
                    "创蓝253短信发送失败: [" + code + "] " + errMsg);
        }

        String msgId = root.get("msgId") != null ? String.valueOf(root.get("msgId")) : null;
        if (log.isInfoEnabled()) {
            log.info("[ChuanglanSmsProvider] 创蓝253发信成功 -> 渠道: {}, 目标号码: {}, 模板: {}, MsgId: {}",
                    channelName, DesensitizeUtil.mobilePhone(phone), templateLabel, msgId);
        }
        return SmsResult.success(channelName, SmsSupplier.CHUANGLAN, msgId, msgId, phoneCount);
    }
}

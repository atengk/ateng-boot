package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sms.exception.SmsResultCode;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.support.SmsPhoneNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 容联云短信服务驱动实现 (Cloopen TemplateSMS)
 * 100% 纯原生实现 {@code sig = MD5(AccountSid + AuthToken + timestamp).toUpperCase()} 与 Base64 {@code Authorization} 鉴权
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class CloopenSmsProvider extends AbstractHttpSmsProvider {

    /**
     * 默认容联云官方 REST 接入端点
     */
    public static final String DEFAULT_ENDPOINT = "https://app.cloopen.com:8883/2013-12-26";
    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(ZONE_SHANGHAI);

    public CloopenSmsProvider(RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
    }

    public CloopenSmsProvider() {
        this(RestClient.builder());
    }

    @Override
    public SmsSupplier getSupplier() {
        return SmsSupplier.CLOOPEN;
    }

    @Override
    public SmsResult send(SmsRequest request, SmsChannel channel) {
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        BusinessAssert.notNull(channel, "短信渠道 SmsChannel 不能为空");
        BusinessAssert.hasText(channel.getAccessKeyId(), "容联云短信 AccountSid (accessKeyId) 不能为空");
        BusinessAssert.hasText(channel.getAccessKeySecret(), "容联云短信 AuthToken (accessKeySecret) 不能为空");
        BusinessAssert.hasText(channel.getAppId(), "容联云短信 AppId (appId) 不能为空");
        BusinessAssert.hasText(request.getPhone(), "接收短信的目标手机号不能为空");
        BusinessAssert.hasText(request.getTemplateId(), "容联云短信模板 ID (templateId) 不能为空");

        String accountSid = channel.getAccessKeyId().trim();
        String authToken = channel.getAccessKeySecret().trim();
        String timestamp = TS_FORMATTER.format(Instant.now());
        String sig = DigestUtil.md5Hex(accountSid + authToken + timestamp).toUpperCase(Locale.ROOT);
        String authorization = Base64.getEncoder()
                .encodeToString((accountSid + ":" + timestamp).getBytes(StandardCharsets.UTF_8));

        List<String> formattedPhones = SmsPhoneNormalizer.toDomesticOrE164List(request.getPhones(), channel);
        List<String> datas = request.getParams() != null
                ? request.getParams().toOrderedArray(channel.getParamOrder(request.getTemplateId()))
                : Collections.emptyList();

        Map<String, Object> bodyMap = new LinkedHashMap<>(4);
        bodyMap.put("to", String.join(",", formattedPhones));
        bodyMap.put("appId", channel.getAppId().trim());
        bodyMap.put("templateId", request.getTemplateId().trim());
        bodyMap.put("datas", datas);
        String payloadJson = JsonUtil.toJsonString(bodyMap);

        String endpoint = normalizeEndpoint(channel.getEndpoint(), DEFAULT_ENDPOINT);
        String actionPath = "/Accounts/" + accountSid + "/SMS/TemplateSMS";
        String url = resolveActionUrl(endpoint, actionPath) + "?sig=" + sig;

        Map<String, String> headers = new LinkedHashMap<>(2);
        headers.put("Accept", "application/json");
        headers.put("Authorization", authorization);

        String responseBody = executePostWithRetry(
                channel, "容联云", "TemplateSMS", url, "application/json;charset=utf-8", headers, payloadJson,
                httpEx -> throwJsonHttpErrorIfPresent(channel, "容联云", httpEx, "statusCode", "statusMsg")
        );

        return parseResponse(channel.getName(), request.getPhone(), request.getTemplateId(), responseBody, formattedPhones.size());
    }

    @SuppressWarnings("unchecked")
    private SmsResult parseResponse(String channelName, String phone, String templateId, String responseBody, int phoneCount) {
        Map<String, Object> root = parseJsonSafely(responseBody);
        String statusCode = root.get("statusCode") != null ? String.valueOf(root.get("statusCode")) : "UNKNOWN";
        if (!"000000".equals(statusCode)) {
            String statusMsg = root.get("statusMsg") != null ? String.valueOf(root.get("statusMsg")) : "Cloopen SMS failed";
            throw new SmsSendException(channelName, statusCode, statusMsg, SmsResultCode.SMS_SEND_ERROR,
                    "容联云短信发送失败: [" + statusCode + "] " + statusMsg);
        }

        String smsMessageSid = null;
        Object templateSmsObj = root.get("templateSMS");
        if (templateSmsObj instanceof Map<?, ?> tplMap) {
            Object sid = ((Map<String, Object>) tplMap).get("smsMessageSid");
            if (sid != null) {
                smsMessageSid = String.valueOf(sid);
            }
        }

        if (log.isInfoEnabled()) {
            log.info("[CloopenSmsProvider] 容联云发信成功 -> 渠道: {}, 目标号码: {}, 模板ID: {}, SmsMessageSid: {}",
                    channelName, DesensitizeUtil.mobilePhone(phone), templateId, smsMessageSid);
        }
        return SmsResult.success(channelName, SmsSupplier.CLOOPEN, smsMessageSid, smsMessageSid, phoneCount);
    }
}

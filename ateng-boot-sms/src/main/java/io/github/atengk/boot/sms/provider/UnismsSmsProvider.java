package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.HmacUtil;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sms.exception.SmsResultCode;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsParam;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.support.SmsPhoneNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * 合一短信服务驱动实现 (UniSMS)
 * 100% 纯原生无状态实现 {@code HMAC-SHA256} 请求参数字典序签名与 {@code extras.simpleMode} 简易鉴权模式
 * 严格遵循单实例完全无状态原则，避免静态全局客户端状态污染与并发同步锁开销
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class UnismsSmsProvider extends AbstractHttpSmsProvider {

    /**
     * 默认合一短信官方接入端点
     */
    public static final String DEFAULT_ENDPOINT = "https://uni.apistd.com";
    private static final String ACTION_SEND = "sms.message.send";

    public UnismsSmsProvider(RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
    }

    public UnismsSmsProvider() {
        this(RestClient.builder());
    }

    @Override
    public SmsSupplier getSupplier() {
        return SmsSupplier.UNISMS;
    }

    @Override
    public SmsResult send(SmsRequest request, SmsChannel channel) {
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        BusinessAssert.notNull(channel, "短信渠道 SmsChannel 不能为空");
        BusinessAssert.hasText(channel.getAccessKeyId(), "合一短信 AccessKeyId 不能为空");
        BusinessAssert.hasText(request.getPhone(), "接收短信的目标手机号不能为空");
        BusinessAssert.hasText(request.getTemplateId(), "合一短信模板 ID (templateId) 不能为空");

        boolean simpleMode = "true".equalsIgnoreCase(channel.getExtra("simpleMode"));
        if (!simpleMode) {
            BusinessAssert.hasText(channel.getAccessKeySecret(),
                    "合一短信 HMAC 签名模式下 AccessKeySecret 不能为空（若需简易模式请设置 extras.simpleMode=true）");
        }

        String signName = channel.resolveSignName(request.getTemplateId(), request.getSignName());
        BusinessAssert.hasText(signName, "合一短信签名 (signature) 不能为空");

        List<String> formattedPhones = SmsPhoneNormalizer.toE164List(request.getPhones(), channel);
        Map<String, Object> templateData = SmsParam.toMap(
                request.getParams(), channel.getParamOrder(request.getTemplateId()));

        Map<String, Object> bodyMap = new LinkedHashMap<>(4);
        bodyMap.put("to", formattedPhones);
        bodyMap.put("signature", signName);
        bodyMap.put("templateId", request.getTemplateId().trim());
        bodyMap.put("templateData", templateData);
        String payloadJson = JsonUtil.toJsonString(bodyMap);

        String endpoint = normalizeEndpoint(channel.getEndpoint(), DEFAULT_ENDPOINT);
        String url = buildSignedUrl(endpoint, channel.getAccessKeyId().trim(),
                channel.getAccessKeySecret(), simpleMode);

        Map<String, String> headers = Map.of("Accept", "application/json");
        String responseBody = executePostWithRetry(
                channel, "合一短信", ACTION_SEND, url, "application/json;charset=utf-8", headers, payloadJson,
                httpEx -> throwJsonHttpErrorIfPresent(channel, "合一短信", httpEx, "code", "message")
        );

        return parseResponse(channel.getName(), request.getPhone(), request.getTemplateId(), responseBody, formattedPhones.size());
    }

    /**
     * 构造合一短信带认证查询参数的请求 URL（HMAC 模式下全参数字典序加签）
     *
     * @param endpoint        规范化端点
     * @param accessKeyId     AccessKey ID
     * @param accessKeySecret AccessKey Secret（simpleMode 时可为 null）
     * @param simpleMode      是否简易模式
     * @return 完整请求 URL
     */
    public static String buildSignedUrl(String endpoint, String accessKeyId, String accessKeySecret, boolean simpleMode) {
        BusinessAssert.hasText(endpoint, "合一短信 endpoint 不能为空");
        BusinessAssert.hasText(accessKeyId, "合一短信 accessKeyId 不能为空");
        if (!simpleMode) {
            BusinessAssert.hasText(accessKeySecret, "合一短信 HMAC 签名模式下 accessKeySecret 不能为空");
        }

        TreeMap<String, String> query = new TreeMap<>();
        query.put("action", ACTION_SEND);
        query.put("accessKeyId", accessKeyId);

        if (!simpleMode) {
            query.put("algorithm", "hmac-sha256");
            query.put("timestamp", String.valueOf(System.currentTimeMillis()));
            query.put("nonce", IdUtil.fastSimpleUUID());
            String stringToSign = encodeFormOrQuery(query, false, false);
            query.put("signature", HmacUtil.hmacSha256Base64(stringToSign, accessKeySecret.trim()));
        }

        String sep = endpoint.contains("?") ? "&" : "?";
        return endpoint + sep + encodeFormOrQuery(query, false, true);
    }

    @SuppressWarnings("unchecked")
    private SmsResult parseResponse(String channelName, String phone, String templateId, String responseBody, int phoneCount) {
        Map<String, Object> root = parseJsonSafely(responseBody);
        String code = root.get("code") != null ? String.valueOf(root.get("code")) : "UNKNOWN";
        if (!"0".equals(code)) {
            String message = root.get("message") != null ? String.valueOf(root.get("message")) : "UniSMS failed";
            throw new SmsSendException(channelName, code, message, SmsResultCode.SMS_SEND_ERROR,
                    "合一短信发送失败: [" + code + "] " + message);
        }

        String bizId = null;
        if (root.get("data") instanceof Map<?, ?> dataMap
                && dataMap.get("messages") instanceof List<?> messages
                && !messages.isEmpty()
                && messages.get(0) instanceof Map<?, ?> firstMsg) {
            Object id = ((Map<String, Object>) firstMsg).get("id");
            if (id != null) {
                bizId = String.valueOf(id);
            }
        }

        if (log.isInfoEnabled()) {
            log.info("[UnismsSmsProvider] 合一短信发信成功 -> 渠道: {}, 目标号码: {}, 模板ID: {}, MsgId: {}",
                    channelName, DesensitizeUtil.mobilePhone(phone), templateId, bizId);
        }
        return SmsResult.success(channelName, SmsSupplier.UNISMS, bizId, bizId, phoneCount);
    }
}

package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.core.util.IdUtil;
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

import java.time.Instant;
import java.util.*;

/**
 * 网易云信短信服务驱动实现 (Netease IM SMS)
 * 100% 纯原生实现 {@code AppKey + Nonce + CurTime + CheckSum(SHA1)} 表单接口认证，单批上限 100，支持 {@code extras.needUp}
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class NeteaseSmsProvider extends AbstractHttpSmsProvider {

    /**
     * 默认网易云信短信官方公网接入端点
     */
    public static final String DEFAULT_ENDPOINT = "https://api.netease.im";
    private static final String SEND_TEMPLATE_PATH = "/sms/sendtemplate.action";

    public NeteaseSmsProvider(RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
    }

    public NeteaseSmsProvider() {
        this(RestClient.builder());
    }

    @Override
    public SmsSupplier getSupplier() {
        return SmsSupplier.NETEASE;
    }

    @Override
    public SmsResult send(SmsRequest request, SmsChannel channel) {
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        BusinessAssert.notNull(channel, "短信渠道 SmsChannel 不能为空");
        BusinessAssert.hasText(channel.getAccessKeyId(), "网易云信短信 AppKey 不能为空");
        BusinessAssert.hasText(channel.getAccessKeySecret(), "网易云信短信 AppSecret 不能为空");
        BusinessAssert.hasText(request.getPhone(), "接收短信的目标手机号不能为空");
        BusinessAssert.hasText(request.getTemplateId(), "网易云信短信模板 ID (templateid) 不能为空");

        List<String> formattedPhones = SmsPhoneNormalizer.toDomesticOrE164List(request.getPhones(), channel);
        List<String> orderedParams = request.getParams() != null
                ? request.getParams().toOrderedArray(channel.getParamOrder(request.getTemplateId()))
                : Collections.emptyList();

        Map<String, String> formFields = new LinkedHashMap<>(4);
        formFields.put("templateid", request.getTemplateId().trim());
        formFields.put("mobiles", JsonUtil.toJsonString(formattedPhones));
        if (!orderedParams.isEmpty()) {
            formFields.put("params", JsonUtil.toJsonString(orderedParams));
        }
        String needUp = "true".equalsIgnoreCase(channel.getExtra("needUp")) ? "true" : "false";
        formFields.put("needUp", needUp);

        String formBody = encodeFormOrQuery(formFields, true, true);
        String nonce = IdUtil.fastSimpleUUID();
        String curTime = String.valueOf(Instant.now().getEpochSecond());
        String checkSum = DigestUtil.sha1Hex(channel.getAccessKeySecret().trim() + nonce + curTime);

        Map<String, String> headers = new LinkedHashMap<>(4);
        headers.put("AppKey", channel.getAccessKeyId().trim());
        headers.put("Nonce", nonce);
        headers.put("CurTime", curTime);
        headers.put("CheckSum", checkSum);

        String endpoint = normalizeEndpoint(channel.getEndpoint(), DEFAULT_ENDPOINT);
        String url = resolveActionUrl(endpoint, SEND_TEMPLATE_PATH);

        String responseBody = executePostWithRetry(
                channel, "网易云信", "sendtemplate", url,
                "application/x-www-form-urlencoded;charset=utf-8",
                headers, formBody,
                httpEx -> throwJsonHttpErrorIfPresent(channel, "网易云信", httpEx, "code", "msg", "desc")
        );

        return parseResponse(channel.getName(), request.getPhone(), request.getTemplateId(), responseBody, formattedPhones.size());
    }

    private SmsResult parseResponse(String channelName, String phone, String templateId, String responseBody, int phoneCount) {
        Map<String, Object> root = parseJsonSafely(responseBody);
        String code = root.get("code") != null ? String.valueOf(root.get("code")) : "UNKNOWN";
        if (!"200".equals(code)) {
            String msg = root.get("msg") != null ? String.valueOf(root.get("msg")) : "Netease SMS failed";
            throw new SmsSendException(channelName, code, msg, SmsResultCode.SMS_SEND_ERROR,
                    "网易云信短信发送失败: [" + code + "] " + msg);
        }

        String sendId = root.get("obj") != null ? String.valueOf(root.get("obj")) : null;
        if (log.isInfoEnabled()) {
            log.info("[NeteaseSmsProvider] 网易云信发信成功 -> 渠道: {}, 目标号码: {}, 模板ID: {}, SendId: {}",
                    channelName, DesensitizeUtil.mobilePhone(phone), templateId, sendId);
        }
        return SmsResult.success(channelName, SmsSupplier.NETEASE, sendId, sendId, phoneCount);
    }
}

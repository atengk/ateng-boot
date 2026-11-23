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
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 中国移动云 MAS 短信服务驱动实现 (China Mobile Cloud MAS)
 * 100% 纯原生实现 Base64 请求体 + MD5 MAC 校验，支持 {@code extras.ecName}、{@code appId} ({@code apId})、
 * {@code extCode} ({@code addSerial}) 及 {@code tmpsubmit} / {@code norsubmit} / {@code submit} 双模切换
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class MasSmsProvider extends AbstractHttpSmsProvider {

    /**
     * 默认中国移动云 MAS 网关接入基础地址（实际生产部署通常按集团分配地址覆盖 endpoint）
     */
    public static final String DEFAULT_ENDPOINT = "https://112.35.1.155:1992/sms";
    private static final String PREFIX_SMS = "/sms";
    private static final String ACTION_TEMPLATE = "tmpsubmit";
    private static final String ACTION_NORMAL = "norsubmit";
    private static final String ACTION_SUBMIT = "submit";

    public MasSmsProvider(RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
    }

    public MasSmsProvider() {
        this(RestClient.builder());
    }

    @Override
    public SmsSupplier getSupplier() {
        return SmsSupplier.MAS;
    }

    @Override
    public SmsResult send(SmsRequest request, SmsChannel channel) {
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        BusinessAssert.notNull(channel, "短信渠道 SmsChannel 不能为空");
        String ecName = channel.getExtra("ecName");
        BusinessAssert.hasText(ecName, "移动云 MAS 集团名称 (extras.ecName) 不能为空");
        BusinessAssert.hasText(channel.getAppId(), "移动云 MAS 接口账号 apId (appId) 不能为空");
        BusinessAssert.hasText(channel.getAccessKeySecret(), "移动云 MAS 接口密钥 secretKey (accessKeySecret) 不能为空");
        BusinessAssert.hasText(request.getPhone(), "接收短信的目标手机号不能为空");

        String sign = channel.resolveSignIdOrName(request.getTemplateId(), request.getSignName());
        BusinessAssert.hasText(sign, "移动云 MAS 签名编码 sign (signId / signName) 不能为空");

        boolean isPlainText = !StringUtils.hasText(request.getTemplateId()) && StringUtils.hasText(request.getContent());
        String action = resolveAction(channel, isPlainText);

        String apId = channel.getAppId().trim();
        String secretKey = channel.getAccessKeySecret().trim();
        String addSerial = channel.getExtCode() != null ? channel.getExtCode().trim() : "";

        List<String> formattedPhones = SmsPhoneNormalizer.toDomesticOrE164List(request.getPhones(), channel);
        String mobiles = String.join(",", formattedPhones);

        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put("ecName", ecName);
        bodyMap.put("apId", apId);
        bodyMap.put("secretKey", secretKey);

        String macSource;
        if (ACTION_TEMPLATE.equals(action)) {
            BusinessAssert.hasText(request.getTemplateId(), "移动云 MAS 模板 ID (templateId) 不能为空");
            String templateId = request.getTemplateId().trim();
            List<String> orderedParams = request.getParams() != null
                    ? request.getParams().toOrderedArray(channel.getParamOrder(request.getTemplateId()))
                    : Collections.emptyList();
            String paramsJson = JsonUtil.toJsonString(orderedParams.isEmpty() ? List.of("") : orderedParams);

            bodyMap.put("templateId", templateId);
            bodyMap.put("mobiles", mobiles);
            bodyMap.put("params", paramsJson);
            macSource = ecName + apId + secretKey + templateId + mobiles + paramsJson + sign + addSerial;
        } else {
            BusinessAssert.hasText(request.getContent(), "移动云 MAS 普通短信正文 (content) 不能为空");
            String content = request.getContent().trim();
            bodyMap.put("mobiles", mobiles);
            bodyMap.put("content", content);
            macSource = ecName + apId + secretKey + mobiles + content + sign + addSerial;
        }

        bodyMap.put("sign", sign);
        bodyMap.put("addSerial", addSerial);
        bodyMap.put("mac", DigestUtil.md5Hex(macSource));

        String encodedPayload = Base64.getEncoder()
                .encodeToString(JsonUtil.toJsonString(bodyMap).getBytes(StandardCharsets.UTF_8));
        String url = resolveActionUrlWithPrefix(
                normalizeEndpoint(channel.getEndpoint(), DEFAULT_ENDPOINT),
                PREFIX_SMS,
                "/" + action
        );

        String responseBody = executePostWithRetry(
                channel, "移动云MAS", action, url, "application/json", null, encodedPayload,
                httpEx -> throwJsonHttpErrorIfPresent(channel, "移动云MAS", httpEx, "rspcod", "message", "rspcod")
        );

        return parseResponse(channel.getName(), request.getPhone(),
                isPlainText ? "[PLAIN_TEXT]" : request.getTemplateId(), responseBody, formattedPhones.size());
    }

    private String resolveAction(SmsChannel channel, boolean isPlainText) {
        if (!isPlainText) {
            return ACTION_TEMPLATE;
        }
        String customAction = channel.getExtra("action");
        if (ACTION_SUBMIT.equalsIgnoreCase(customAction)) {
            return ACTION_SUBMIT;
        }
        return ACTION_NORMAL;
    }

    private SmsResult parseResponse(String channelName, String phone, String templateLabel, String responseBody, int phoneCount) {
        Map<String, Object> root = parseJsonSafely(responseBody);
        String rspcod = root.get("rspcod") != null ? String.valueOf(root.get("rspcod")) : "UNKNOWN";
        Object successVal = root.get("success");
        boolean flag = successVal == null || !"false".equalsIgnoreCase(String.valueOf(successVal));
        if (!"success".equalsIgnoreCase(rspcod) || !flag) {
            throw new SmsSendException(channelName, rspcod, "rspcod=" + rspcod, SmsResultCode.SMS_SEND_ERROR,
                    "移动云 MAS 短信发送失败: [" + rspcod + "]");
        }

        String msgGroup = root.get("msgGroup") != null ? String.valueOf(root.get("msgGroup")) : null;
        if (log.isInfoEnabled()) {
            log.info("[MasSmsProvider] 移动云 MAS 发信成功 -> 渠道: {}, 目标号码: {}, 模板: {}, MsgGroup: {}",
                    channelName, DesensitizeUtil.mobilePhone(phone), templateLabel, msgGroup);
        }
        return SmsResult.success(channelName, SmsSupplier.MAS, msgGroup, msgGroup, phoneCount);
    }
}

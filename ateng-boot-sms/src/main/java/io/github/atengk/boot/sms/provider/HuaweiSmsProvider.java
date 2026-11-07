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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 华为云消息&短信驱动实现 (MSGSMS)
 * 基于 Spring 6 原生 RestClient 与 JDK 17，原生实现 WSSE 规范 UsernameToken 摘要鉴权
 * 100% 纯原生零第三方 SDK 依赖，支持表单 POST 协议与模板参数智能投射
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class HuaweiSmsProvider extends AbstractHttpSmsProvider {

    /**
     * 默认华为云短信官方接入公网端点 (华北-北京四)
     */
    public static final String DEFAULT_ENDPOINT = "https://smsapi.cn-north-4.myhuaweicloud.com:443/sms/batchSendSms/v1";

    /**
     * WSSE Authorization 头部固定前缀
     */
    private static final String AUTH_HEADER_VALUE = "WSSE realm=\"SDP\",profile=\"UsernameToken\",type=\"Appkey\"";

    /**
     * WSSE X-WSSE 头部格式化模板
     */
    private static final String WSSE_HEADER_FORMAT = "UsernameToken Username=\"%s\",PasswordDigest=\"%s\",Nonce=\"%s\",Created=\"%s\"";

    /**
     * 华为云成功状态码
     */
    public static final String SUCCESS_CODE = "000000";

    /**
     * UTC 日期时间格式化器
     */
    private static final DateTimeFormatter UTC_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    public HuaweiSmsProvider(RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
    }

    public HuaweiSmsProvider() {
        this(RestClient.builder());
    }

    @Override
    public SmsSupplier getSupplier() {
        return SmsSupplier.HUAWEI;
    }

    @Override
    public SmsResult send(SmsRequest request, SmsChannel channel) {
        // 1. 前置卫语句防御
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        BusinessAssert.notNull(channel, "短信渠道 SmsChannel 不能为空");
        BusinessAssert.hasText(channel.getAccessKeyId(), "华为云短信 AppKey (AccessKeyId) 不能为空");
        BusinessAssert.hasText(channel.getAccessKeySecret(), "华为云短信 AppSecret (AccessKeySecret) 不能为空");
        BusinessAssert.hasText(channel.getAppId(), "华为云短信通道号 (from) 不能为空，请在渠道配置 appId 中指定通道号");
        BusinessAssert.hasText(request.getPhone(), "接收短信的目标手机号不能为空");
        BusinessAssert.hasText(request.getTemplateId(), "短信模板 ID 不能为空");

        // 2. 组装请求参数表单 (application/x-www-form-urlencoded)
        Map<String, String> formParams = new LinkedHashMap<>();
        // 发送方通道号
        formParams.put("from", channel.getAppId());
        // 接收方手机号（多个以英文逗号分隔）
        formParams.put("to", String.join(",", request.getPhones()));
        // 模板 ID
        formParams.put("templateId", request.getTemplateId());

        // 3. 模板参数智能推导为 JSON 数组字符串格式
        List<String> paramOrder = channel.getParamOrder(request.getTemplateId());
        List<String> templateParams = request.getParams() != null
                ? request.getParams().toOrderedArray(paramOrder)
                : Collections.emptyList();
        if (!templateParams.isEmpty()) {
            formParams.put("templateParas", JsonUtil.toJsonString(templateParams));
        }

        // 签名名称（使用国内通用模板时填写）
        String signName = StringUtils.hasText(request.getSignName()) ? request.getSignName() : channel.getSignName();
        if (StringUtils.hasText(signName)) {
            formParams.put("signature", signName);
        }

        // 业务流水与透传扩展字段 (extend)
        if (StringUtils.hasText(request.getBizId())) {
            formParams.put("extend", request.getBizId());
        }

        // 厂商状态报告回调地址
        if (StringUtils.hasText(channel.getStatusCallback())) {
            formParams.put("statusCallback", channel.getStatusCallback().trim());
        }

        // 4. 构建表单编码请求体字符串
        StringBuilder bodyBuilder = new StringBuilder();
        for (Map.Entry<String, String> entry : formParams.entrySet()) {
            if (bodyBuilder.length() > 0) {
                bodyBuilder.append("&");
            }
            bodyBuilder.append(urlEncode(entry.getKey()))
                    .append("=")
                    .append(urlEncode(entry.getValue()));
        }
        String requestBody = bodyBuilder.toString();

        // 5. 生成 WSSE UsernameToken 摘要认证请求头
        String created = UTC_FORMATTER.format(Instant.now());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String wsseHeader = buildWsseHeader(channel.getAccessKeyId(), channel.getAccessKeySecret(), nonce, created);

        // 6. 确定请求端点 URL 并执行网络请求
        String endpoint = resolveEndpoint(channel.getEndpoint());
        Duration timeout = channel.getTimeout() != null ? channel.getTimeout() : Duration.ofSeconds(10);
        RestClient client = buildRestClient(timeout);

        String responseBody;
        try {
            responseBody = executeWithRetry(channel, "SendSms", () -> client.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header("Authorization", AUTH_HEADER_VALUE)
                    .header("X-WSSE", wsseHeader)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class));
        } catch (RestClientResponseException e) {
            String errorBody = e.getResponseBodyAsString();
            parseAndThrowExceptionIfPossible(channel.getName(), errorBody, e);
            throw new SmsSendException(channel.getName(), "HTTP_" + e.getStatusCode().value(),
                    e.getStatusText(), SmsResultCode.SMS_SEND_ERROR, "华为云短信网关响应异常: " + e.getMessage(), e);
        } catch (SmsSendException e) {
            throw e;
        } catch (Exception e) {
            throw new SmsSendException(channel.getName(), "NETWORK_ERROR", e.getMessage(),
                    SmsResultCode.SMS_SEND_ERROR, "华为云短信网络通信异常: " + e.getMessage(), e);
        }

        // 7. 解析响应结果并转译因果异常
        return parseResponse(channel.getName(), request.getPhone(), request.getTemplateId(), responseBody);
    }

    /**
     * 生成华为云 WSSE UsernameToken 摘要请求头
     * 计算规则: PasswordDigest = Base64(SHA256(Nonce + Created + AppSecret))
     *
     * @param appKey    访问密钥 Key
     * @param appSecret 访问密钥 Secret
     * @param nonce     随机串
     * @param created   UTC 时间格式
     * @return X-WSSE 请求头字符串
     */
    public static String buildWsseHeader(String appKey, String appSecret, String nonce, String created) {
        BusinessAssert.hasText(appKey, "appKey 不能为空");
        BusinessAssert.hasText(appSecret, "appSecret 不能为空");
        BusinessAssert.hasText(nonce, "nonce 不能为空");
        BusinessAssert.hasText(created, "created 不能为空");

        String raw = nonce + created + appSecret;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            String passwordDigest = Base64.getEncoder().encodeToString(hash);
            return String.format(WSSE_HEADER_FORMAT, appKey, passwordDigest, nonce, created);
        } catch (Exception e) {
            throw new IllegalStateException("计算华为云短信 WSSE PasswordDigest 异常: " + e.getMessage(), e);
        }
    }

    /**
     * 解析华为云响应结果或抛出因果异常
     */
    @SuppressWarnings("unchecked")
    private SmsResult parseResponse(String channelName, String phone, String templateId, String responseBody) {
        Map<String, Object> respMap = parseJsonSafely(responseBody);

        String code = String.valueOf(respMap.get("code"));
        String description = String.valueOf(respMap.get("description"));

        // 1. 检查根状态码是否为 000000
        if (!SUCCESS_CODE.equals(code)) {
            throw new SmsSendException(channelName, code, description, SmsResultCode.SMS_SEND_ERROR,
                    "华为云短信发送失败: [" + code + "] " + description);
        }

        // 2. 检查 result 发送明细列表
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) respMap.get("result");
        if (resultList == null || resultList.isEmpty()) {
            throw new SmsSendException(channelName, "EMPTY_RESULT", "华为云响应缺少 result 明细条目",
                    SmsResultCode.SMS_SEND_ERROR, "华为云发信明细为空: " + responseBody);
        }

        int totalFee = 0;
        String firstSmsMsgId = null;

        for (Map<String, Object> item : resultList) {
            String itemStatus = String.valueOf(item.get("status"));
            if (!SUCCESS_CODE.equals(itemStatus)) {
                throw new SmsSendException(channelName, itemStatus, "明细条目投递失败", SmsResultCode.SMS_SEND_ERROR,
                        "华为云短信投递拒绝: [" + itemStatus + "]");
            }
            if (firstSmsMsgId == null && item.get("smsMsgId") != null) {
                firstSmsMsgId = String.valueOf(item.get("smsMsgId"));
            }
            Object totalObj = item.get("total");
            if (totalObj instanceof Number num) {
                totalFee += num.intValue();
            } else {
                totalFee += 1;
            }
        }

        log.info("[HuaweiSmsProvider] 华为云发信成功 -> 渠道: {}, 目标号码: {}, 模板ID: {}, smsMsgId: {}",
                channelName, DesensitizeUtil.mobilePhone(phone), templateId, firstSmsMsgId);

        return SmsResult.success(channelName, SmsSupplier.HUAWEI, firstSmsMsgId, firstSmsMsgId, totalFee > 0 ? totalFee : 1);
    }

    /**
     * 当收到 HTTP 非 200 网关错误时尝试结构化解析并抛出
     */
    private void parseAndThrowExceptionIfPossible(String channelName, String errorBody, Exception cause) {
        if (!StringUtils.hasText(errorBody)) {
            return;
        }
        try {
            Map<String, Object> respMap = parseJsonSafely(errorBody);
            String code = String.valueOf(respMap.get("code"));
            String description = String.valueOf(respMap.get("description"));
            if (StringUtils.hasText(code) && !"null".equals(code)) {
                throw new SmsSendException(channelName, code, description, SmsResultCode.SMS_SEND_ERROR,
                        "华为云短信网关拒绝: [" + code + "] " + description, cause);
            }
        } catch (SmsSendException e) {
            throw e;
        } catch (Exception ignored) {
            // 解析失败时外部抛出兜底网络异常
        }
    }

    private String resolveEndpoint(String endpoint) {
        if (!StringUtils.hasText(endpoint)) {
            return DEFAULT_ENDPOINT;
        }
        String clean = endpoint.trim();
        if (clean.contains("/sms/batchSendSms/v1")) {
            return clean;
        }
        return clean.endsWith("/") ? clean + "sms/batchSendSms/v1" : clean + "/sms/batchSendSms/v1";
    }

    private static String urlEncode(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

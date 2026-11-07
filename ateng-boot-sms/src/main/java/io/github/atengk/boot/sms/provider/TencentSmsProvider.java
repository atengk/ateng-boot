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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 腾讯云短信服务驱动实现 (SMS API 3.0)
 * 基于 Spring 6 原生 RestClient 与 JDK 17，原生派生 TC3-HMAC-SHA256 签名算法
 * 100% 纯原生零第三方 SDK 依赖，支持有序模板数组智能推导与 E.164 国际手机号规范化
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class TencentSmsProvider extends AbstractHttpSmsProvider {

    /**
     * 默认腾讯云短信官方接入公网端点
     */
    public static final String DEFAULT_ENDPOINT = "https://sms.tencentcloudapi.com";

    /**
     * TC3 签名算法标识
     */
    private static final String ALGORITHM = "TC3-HMAC-SHA256";

    /**
     * 短信接口操作名
     */
    private static final String ACTION = "SendSms";

    /**
     * 短信服务接口版本
     */
    private static final String API_VERSION = "2021-01-11";

    /**
     * 服务标识
     */
    private static final String SERVICE = "sms";

    /**
     * JCA 标准 HmacSHA256 算法名
     */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * UTC 日期格式化器
     */
    private static final DateTimeFormatter UTC_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);

    public TencentSmsProvider(RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
    }

    public TencentSmsProvider() {
        this(RestClient.builder());
    }

    @Override
    public SmsSupplier getSupplier() {
        return SmsSupplier.TENCENT;
    }

    @Override
    public SmsResult send(SmsRequest request, SmsChannel channel) {
        // 1. 前置卫语句防卫
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        BusinessAssert.notNull(channel, "短信渠道 SmsChannel 不能为空");
        BusinessAssert.hasText(channel.getAccessKeyId(), "腾讯云短信 SecretId (AccessKeyId) 不能为空");
        BusinessAssert.hasText(channel.getAccessKeySecret(), "腾讯云短信 SecretKey (AccessKeySecret) 不能为空");
        BusinessAssert.hasText(channel.getAppId(), "腾讯云短信 SmsSdkAppId 不能为空，请在渠道配置 appId 中指定");
        BusinessAssert.hasText(request.getPhone(), "接收短信的目标手机号不能为空");
        BusinessAssert.hasText(request.getTemplateId(), "短信模板 ID 不能为空");

        String signName = StringUtils.hasText(request.getSignName()) ? request.getSignName() : channel.getSignName();
        BusinessAssert.hasText(signName, "短信签名 (SignName) 不能为空，请在请求或渠道配置中显式指定");

        // 2. 手机号码标准化清洗为 E.164 格式
        List<String> rawPhones = request.getPhones();
        List<String> e164Phones = new ArrayList<>(rawPhones.size());
        for (String p : rawPhones) {
            e164Phones.add(formatE164(p));
        }

        // 3. 模板参数智能推导为有序字符串数组
        List<String> paramOrder = channel.getParamOrder(request.getTemplateId());
        List<String> templateParams = request.getParams() != null
                ? request.getParams().toOrderedArray(paramOrder)
                : Collections.emptyList();

        // 4. 构建请求体 JSON Payload
        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("PhoneNumberSet", e164Phones);
        payloadMap.put("SmsSdkAppId", channel.getAppId());
        payloadMap.put("SignName", signName);
        payloadMap.put("TemplateId", request.getTemplateId());
        if (!templateParams.isEmpty()) {
            payloadMap.put("TemplateParamSet", templateParams);
        }
        if (StringUtils.hasText(request.getBizId())) {
            payloadMap.put("SessionContext", request.getBizId());
        }
        String payloadJson = JsonUtil.toJsonString(payloadMap);

        // 5. 确定请求端点与 Host 头
        String endpoint = StringUtils.hasText(channel.getEndpoint()) ? channel.getEndpoint() : DEFAULT_ENDPOINT;
        URI uri = URI.create(endpoint);
        final String finalHost = (uri.getPort() > 0 && uri.getPort() != 80 && uri.getPort() != 443)
                ? uri.getHost() + ":" + uri.getPort()
                : uri.getHost();

        // 6. TC3-HMAC-SHA256 签名派生
        Instant now = Instant.now();
        long timestamp = now.getEpochSecond();
        String date = UTC_DATE_FORMATTER.format(now);
        String authorization = generateAuthorization(
                channel.getAccessKeyId(),
                channel.getAccessKeySecret(),
                finalHost,
                payloadJson,
                timestamp,
                date
        );

        // 7. 发送 HTTP POST 请求
        Duration timeout = channel.getTimeout() != null ? channel.getTimeout() : Duration.ofSeconds(10);
        RestClient client = buildRestClient(timeout);

        String region = StringUtils.hasText(channel.getRegion()) ? channel.getRegion() : "ap-guangzhou";

        String responseBody;
        try {
            responseBody = executeWithRetry(channel, "SendSms", () -> client.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Host", finalHost)
                    .header("X-TC-Action", ACTION)
                    .header("X-TC-Version", API_VERSION)
                    .header("X-TC-Timestamp", String.valueOf(timestamp))
                    .header("X-TC-Region", region)
                    .header("Authorization", authorization)
                    .body(payloadJson)
                    .retrieve()
                    .body(String.class));
        } catch (RestClientResponseException e) {
            String errorBody = e.getResponseBodyAsString();
            parseAndThrowExceptionIfPossible(channel.getName(), errorBody, e);
            throw new SmsSendException(channel.getName(), "HTTP_" + e.getStatusCode().value(),
                    e.getStatusText(), SmsResultCode.SMS_SEND_ERROR, "腾讯云短信网关响应异常: " + e.getMessage(), e);
        } catch (SmsSendException e) {
            throw e;
        } catch (Exception e) {
            throw new SmsSendException(channel.getName(), "NETWORK_ERROR", e.getMessage(),
                    SmsResultCode.SMS_SEND_ERROR, "腾讯云短信网络通信异常: " + e.getMessage(), e);
        }

        // 8. 解析响应结果并执行因果异常转译
        return parseResponse(channel.getName(), request.getPhone(), request.getTemplateId(), responseBody);
    }

    /**
     * 生成腾讯云 API 3.0 TC3-HMAC-SHA256 签名 Authorization 头部
     *
     * @param secretId  访问密钥 ID
     * @param secretKey 访问密钥 Key
     * @param host      目标主机（含端口，如适用）
     * @param payload   请求体 JSON 字符串
     * @param timestamp 秒级时间戳
     * @param date      UTC 日期 yyyy-MM-dd
     * @return Authorization 头部字符串
     */
    public static String generateAuthorization(String secretId, String secretKey, String host, String payload, long timestamp, String date) {
        BusinessAssert.hasText(secretId, "secretId 不能为空");
        BusinessAssert.hasText(secretKey, "secretKey 不能为空");
        BusinessAssert.hasText(host, "host 不能为空");

        // 1. 规范请求串 (CanonicalRequest)
        String httpRequestMethod = "POST";
        String canonicalUri = "/";
        String canonicalQueryString = "";
        String canonicalHeaders = "content-type:application/json\nhost:" + host.toLowerCase(Locale.ROOT) + "\n";
        String signedHeaders = "content-type;host";
        String hashedRequestPayload = sha256Hex(payload != null ? payload : "");
        String canonicalRequest = httpRequestMethod + "\n"
                + canonicalUri + "\n"
                + canonicalQueryString + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + hashedRequestPayload;

        // 2. 待签名字符串 (StringToSign)
        String credentialScope = date + "/" + SERVICE + "/tc3_request";
        String hashedCanonicalRequest = sha256Hex(canonicalRequest);
        String stringToSign = ALGORITHM + "\n"
                + timestamp + "\n"
                + credentialScope + "\n"
                + hashedCanonicalRequest;

        // 3. 级联派生计算签名 (Derived Signing Key)
        byte[] secretDate = hmac256(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmac256(secretDate, SERVICE);
        byte[] secretSigning = hmac256(secretService, "tc3_request");

        // 4. 最终签名
        String signature = bytesToHex(hmac256(secretSigning, stringToSign));

        // 5. 组装 Authorization
        return ALGORITHM + " Credential=" + secretId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
    }

    /**
     * 规范化手机号格式至 E.164 标准（国内普通号码自动前缀 +86）
     *
     * @param phone 原始号码
     * @return E.164 格式号码
     */
    public static String formatE164(String phone) {
        if (!StringUtils.hasText(phone)) {
            return "";
        }
        String clean = phone.trim().replace("-", "").replace(" ", "");
        if (clean.startsWith("+")) {
            return clean;
        }
        if (clean.startsWith("00")) {
            return "+" + clean.substring(2);
        }
        if (clean.startsWith("86") && clean.length() == 13) {
            return "+" + clean;
        }
        return "+86" + clean;
    }

    /**
     * 解析腾讯云响应或抛出因果异常
     */
    @SuppressWarnings("unchecked")
    private SmsResult parseResponse(String channelName, String phone, String templateId, String responseBody) {
        Map<String, Object> rootMap = parseJsonSafely(responseBody);
        Map<String, Object> responseMap = (Map<String, Object>) rootMap.get("Response");
        if (responseMap == null) {
            throw new SmsSendException(channelName, "INVALID_RESPONSE", "腾讯云响应未包含 Response 根节点",
                    SmsResultCode.SMS_SEND_ERROR, "腾讯云响应格式解析失败: " + responseBody);
        }

        // 1. 检查是否存在全局 Error
        if (responseMap.containsKey("Error")) {
            Map<String, Object> errorMap = (Map<String, Object>) responseMap.get("Error");
            String errCode = errorMap != null ? String.valueOf(errorMap.get("Code")) : "UNKNOWN_ERROR";
            String errMsg = errorMap != null ? String.valueOf(errorMap.get("Message")) : "未知错误";
            throw new SmsSendException(channelName, errCode, errMsg, SmsResultCode.SMS_SEND_ERROR,
                    "腾讯云短信发送失败: [" + errCode + "] " + errMsg);
        }

        String requestId = String.valueOf(responseMap.get("RequestId"));

        // 2. 检查 SendStatusSet 发送状态明细
        List<Map<String, Object>> statusSet = (List<Map<String, Object>>) responseMap.get("SendStatusSet");
        if (statusSet == null || statusSet.isEmpty()) {
            throw new SmsSendException(channelName, "EMPTY_STATUS_SET", "腾讯云响应未包含 SendStatusSet",
                    SmsResultCode.SMS_SEND_ERROR, "腾讯云发信明细为空");
        }

        int totalFee = 0;
        String firstSerialNo = null;

        for (Map<String, Object> statusItem : statusSet) {
            String code = String.valueOf(statusItem.get("Code"));
            String msg = String.valueOf(statusItem.get("Message"));
            if (!"Ok".equalsIgnoreCase(code)) {
                throw new SmsSendException(channelName, code, msg, SmsResultCode.SMS_SEND_ERROR,
                        "腾讯云短信投递失败: [" + code + "] " + msg);
            }
            if (firstSerialNo == null && statusItem.get("SerialNo") != null) {
                firstSerialNo = String.valueOf(statusItem.get("SerialNo"));
            }
            Object feeObj = statusItem.get("Fee");
            if (feeObj instanceof Number num) {
                totalFee += num.intValue();
            } else {
                totalFee += 1;
            }
        }

        log.info("[TencentSmsProvider] 腾讯云发信成功 -> 渠道: {}, 目标号码: {}, 模板ID: {}, SerialNo: {}, RequestId: {}",
                channelName, DesensitizeUtil.mobilePhone(phone), templateId, firstSerialNo, requestId);

        return SmsResult.success(channelName, SmsSupplier.TENCENT, firstSerialNo, requestId, totalFee > 0 ? totalFee : 1);
    }

    /**
     * 处理 HTTP 非 200 网关拒绝
     */
    @SuppressWarnings("unchecked")
    private void parseAndThrowExceptionIfPossible(String channelName, String errorBody, Exception cause) {
        if (!StringUtils.hasText(errorBody)) {
            return;
        }
        try {
            Map<String, Object> rootMap = parseJsonSafely(errorBody);
            Map<String, Object> responseMap = (Map<String, Object>) rootMap.get("Response");
            if (responseMap != null && responseMap.containsKey("Error")) {
                Map<String, Object> errorMap = (Map<String, Object>) responseMap.get("Error");
                String code = String.valueOf(errorMap.get("Code"));
                String message = String.valueOf(errorMap.get("Message"));
                throw new SmsSendException(channelName, code, message, SmsResultCode.SMS_SEND_ERROR,
                        "腾讯云短信网关拒绝: [" + code + "] " + message, cause);
            }
        } catch (SmsSendException e) {
            throw e;
        } catch (Exception ignored) {
            // 解析失败时由外部继续抛出兜底异常
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("计算 SHA-256 哈希异常: " + e.getMessage(), e);
        }
    }

    private static byte[] hmac256(byte[] key, String msg) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("计算 HMAC-SHA256 异常: " + e.getMessage(), e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}

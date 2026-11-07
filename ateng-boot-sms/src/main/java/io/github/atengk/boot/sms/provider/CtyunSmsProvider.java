package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.DesensitizeUtil;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 天翼云短信服务驱动实现 (CTYun)
 * 基于 Spring 6 原生 RestClient 与 JDK 17，原生实现天翼云 CT EOP 协议与多级级联 HMAC-SHA256 鉴权
 * 100% 纯原生零第三方 SDK 依赖，支持单条与批量模板短信投递、E.164 规范化与统一因果异常转译
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class CtyunSmsProvider extends AbstractHttpSmsProvider {

    /**
     * 默认天翼云官方公网接入端点
     */
    public static final String DEFAULT_ENDPOINT = "https://sms-global.ctapi.ctyun.cn/sms/api/v1";

    /**
     * 天翼云接口动作名称
     */
    private static final String DEFAULT_ACTION = "SendSms";

    /**
     * 签名算法标识
     */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * 中国标准时间时区 (Asia/Shanghai, GMT+8)
     */
    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");

    /**
     * 天翼云 Eop-date UTC 格式化器 (东八区时间)
     */
    private static final DateTimeFormatter EOP_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZONE_SHANGHAI);

    /**
     * 天翼云纯日期格式化器 (东八区时间)
     */
    private static final DateTimeFormatter PURE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZONE_SHANGHAI);

    public CtyunSmsProvider(RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
    }

    public CtyunSmsProvider() {
        this(RestClient.builder());
    }

    @Override
    public SmsSupplier getSupplier() {
        return SmsSupplier.CTYUN;
    }

    @Override
    public SmsResult send(SmsRequest request, SmsChannel channel) {
        // 1. 前置卫语句防卫
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        BusinessAssert.notNull(channel, "短信渠道 SmsChannel 不能为空");
        BusinessAssert.hasText(channel.getAccessKeyId(), "天翼云短信 AccessKeyId 不能为空");
        BusinessAssert.hasText(channel.getAccessKeySecret(), "天翼云短信 AccessKeySecret 不能为空");
        BusinessAssert.hasText(request.getPhone(), "接收短信的目标手机号不能为空");
        BusinessAssert.hasText(request.getTemplateId(), "天翼云短信模板 ID (templateCode) 不能为空");

        String signName = StringUtils.hasText(request.getSignName()) ? request.getSignName() : channel.getSignName();
        BusinessAssert.hasText(signName, "短信签名 (SignName) 不能为空，请在请求或渠道配置中显式指定");

        // 2. 手机号码标准化并清洗 (+86 前缀补充)
        List<String> rawPhones = request.getPhones();
        List<String> formattedPhones = new ArrayList<>(rawPhones.size());
        for (String p : rawPhones) {
            formattedPhones.add(formatPhone(p));
        }
        String phoneStr = String.join(",", formattedPhones);

        // 3. 构建请求体 JSON Payload
        Map<String, String> bodyMap = new LinkedHashMap<>();
        bodyMap.put("action", DEFAULT_ACTION);
        bodyMap.put("phoneNumber", phoneStr);
        bodyMap.put("signName", signName);
        bodyMap.put("templateCode", request.getTemplateId().trim());
        bodyMap.put("templateParam", request.getParams() != null ? request.getParams().toJsonString() : "{}");
        String payloadJson = io.github.atengk.boot.core.util.JsonUtil.toJsonString(bodyMap);

        // 4. CT EOP 协议三级级联签名派生
        Instant now = Instant.now();
        String signatureTime = EOP_DATE_FORMATTER.format(now);
        String signatureDate = PURE_DATE_FORMATTER.format(now);
        String requestId = UUID.randomUUID().toString();

        Map<String, String> authHeaders = generateAuthHeaders(
                channel.getAccessKeyId().trim(),
                channel.getAccessKeySecret().trim(),
                payloadJson,
                requestId,
                signatureTime,
                signatureDate
        );

        // 5. 确定请求端点并发送 HTTP POST 请求
        String endpoint = StringUtils.hasText(channel.getEndpoint()) ? channel.getEndpoint() : DEFAULT_ENDPOINT;
        Duration timeout = channel.getTimeout() != null ? channel.getTimeout() : Duration.ofSeconds(10);
        RestClient client = buildRestClient(timeout);

        String responseBody;
        try {
            responseBody = executeWithRetry(channel, DEFAULT_ACTION, () -> client.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("ctyun-eop-request-id", authHeaders.get("ctyun-eop-request-id"))
                    .header("Eop-date", authHeaders.get("Eop-date"))
                    .header("Eop-Authorization", authHeaders.get("Eop-Authorization"))
                    .body(payloadJson)
                    .retrieve()
                    .body(String.class));
        } catch (RestClientResponseException e) {
            String errorBody = e.getResponseBodyAsString();
            parseAndThrowExceptionIfPossible(channel.getName(), errorBody, e);
            throw new SmsSendException(channel.getName(), "HTTP_" + e.getStatusCode().value(),
                    e.getStatusText(), SmsResultCode.SMS_SEND_ERROR, "天翼云短信网关响应异常: " + e.getMessage(), e);
        } catch (SmsSendException e) {
            throw e;
        } catch (Exception e) {
            throw new SmsSendException(channel.getName(), "HTTP_CLIENT_ERROR", e.getMessage(),
                    SmsResultCode.SMS_SEND_ERROR, "天翼云短信网络调用异常: " + e.getMessage(), e);
        }

        // 6. 校验并解析云厂商回执报文
        return parseResponse(channel.getName(), request.getPhone(), request.getTemplateId(), responseBody, requestId, rawPhones.size());
    }

    /**
     * 规范化手机号格式至天翼云标准（国内号码自动前缀 +86）
     *
     * @param phone 原始号码
     * @return 格式化后的号码
     */
    public static String formatPhone(String phone) {
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
     * 生成天翼云 EOP 鉴权请求头集合
     *
     * @param accessKeyId     Access Key
     * @param accessKeySecret Secret Key
     * @param payloadJson     请求体 JSON 字符串
     * @param requestId       流水号 UUID
     * @param signatureTime   东八区时间戳 yyyyMMdd'T'HHmmss'Z'
     * @param signatureDate   东八区纯日期 yyyyMMdd
     * @return 鉴权请求头 Map
     */
    public static Map<String, String> generateAuthHeaders(String accessKeyId,
                                                          String accessKeySecret,
                                                          String payloadJson,
                                                          String requestId,
                                                          String signatureTime,
                                                          String signatureDate) {
        // 1. 报文原封不动进行 SHA-256 摘要
        String contentHash = sha256Hex(payloadJson != null ? payloadJson : "");

        // 2. 三级级联 HMAC-SHA256 派生签名密钥
        byte[] kTime = hmacSha256(signatureTime.getBytes(StandardCharsets.UTF_8), accessKeySecret.getBytes(StandardCharsets.UTF_8));
        byte[] kAk = hmacSha256(accessKeyId.getBytes(StandardCharsets.UTF_8), kTime);
        byte[] kDate = hmacSha256(signatureDate.getBytes(StandardCharsets.UTF_8), kAk);

        // 3. 构造待签名字符串
        String signatureStr = String.format("ctyun-eop-request-id:%s\neop-date:%s\n\n\n%s", requestId, signatureTime, contentHash);

        // 4. 计算最终 Base64 签名值
        String signature = Base64.getEncoder().encodeToString(hmacSha256(signatureStr.getBytes(StandardCharsets.UTF_8), kDate));
        String signHeader = String.format("%s Headers=ctyun-eop-request-id;eop-date Signature=%s", accessKeyId, signature);

        Map<String, String> headers = new HashMap<>(4);
        headers.put("ctyun-eop-request-id", requestId);
        headers.put("Eop-date", signatureTime);
        headers.put("Eop-Authorization", signHeader);
        return headers;
    }

    /**
     * 解析天翼云响应或抛出因果异常
     */
    private SmsResult parseResponse(String channelName, String phone, String templateId, String responseBody, String reqId, int phoneCount) {
        Map<String, Object> rootMap = parseJsonSafely(responseBody);
        String code = String.valueOf(rootMap.get("code"));
        String message = rootMap.get("message") != null ? String.valueOf(rootMap.get("message")) : "OK";

        if (!"OK".equalsIgnoreCase(code)) {
            throw new SmsSendException(channelName, code, message, SmsResultCode.SMS_SEND_ERROR,
                    "天翼云短信发送失败: [" + code + "] " + message);
        }

        String requestId = rootMap.get("requestId") != null ? String.valueOf(rootMap.get("requestId")) : reqId;
        String bizId = rootMap.get("bizId") != null ? String.valueOf(rootMap.get("bizId")) : null;

        log.info("[CtyunSmsProvider] 天翼云发信成功 -> 渠道: {}, 目标号码: {}, 模板ID: {}, BizId: {}, RequestId: {}",
                channelName, DesensitizeUtil.mobilePhone(phone), templateId, bizId, requestId);

        return SmsResult.success(channelName, SmsSupplier.CTYUN, bizId != null ? bizId : requestId, requestId, phoneCount);
    }

    /**
     * 处理 HTTP 非 200 网关拒绝
     */
    private void parseAndThrowExceptionIfPossible(String channelName, String errorBody, Exception cause) {
        if (!StringUtils.hasText(errorBody)) {
            return;
        }
        try {
            Map<String, Object> rootMap = parseJsonSafely(errorBody);
            if (rootMap.containsKey("code")) {
                String code = String.valueOf(rootMap.get("code"));
                String message = rootMap.get("message") != null ? String.valueOf(rootMap.get("message")) : "网关拒绝";
                throw new SmsSendException(channelName, code, message, SmsResultCode.SMS_SEND_ERROR,
                        "天翼云短信网关拒绝: [" + code + "] " + message, cause);
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
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("计算 SHA-256 异常: " + e.getMessage(), e);
        }
    }

    private static byte[] hmacSha256(byte[] data, byte[] key) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("计算 HMAC-SHA256 异常: " + e.getMessage(), e);
        }
    }
}

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 阿里云短信服务驱动实现 (Dysmsapi)
 * 基于 Spring 6 原生 RestClient 与 JDK 17，原生实现 POP RPC 规范与 HMAC-SHA1 签名算法
 * 100% 纯原生零第三方 SDK 依赖，支持出站请求参数规范化与统一因果异常转译
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class AliyunSmsProvider extends AbstractHttpSmsProvider {

    /**
     * 默认阿里云短信官方接入公网端点
     */
    public static final String DEFAULT_ENDPOINT = "https://dysmsapi.aliyuncs.com";

    /**
     * POP RPC 签名算法参数值
     */
    private static final String SIGNATURE_METHOD = "HMAC-SHA1";

    /**
     * Java JCA 标准 Mac 算法标识
     */
    private static final String MAC_ALGORITHM = "HmacSHA1";

    /**
     * POP RPC 接口版本
     */
    private static final String API_VERSION = "2017-05-25";

    /**
     * ISO 8601 UTC 时间格式化器
     */
    private static final DateTimeFormatter UTC_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    public AliyunSmsProvider(RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
    }

    public AliyunSmsProvider() {
        this(RestClient.builder());
    }

    @Override
    public SmsSupplier getSupplier() {
        return SmsSupplier.ALIYUN;
    }

    @Override
    public SmsResult send(SmsRequest request, SmsChannel channel) {
        // 1. 前置卫语句防御
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        BusinessAssert.notNull(channel, "短信渠道 SmsChannel 不能为空");
        BusinessAssert.hasText(channel.getAccessKeyId(), "阿里云短信 AccessKeyId 不能为空");
        BusinessAssert.hasText(channel.getAccessKeySecret(), "阿里云短信 AccessKeySecret 不能为空");
        BusinessAssert.hasText(request.getPhone(), "接收短信的目标手机号不能为空");
        BusinessAssert.hasText(request.getTemplateId(), "短信模板 ID (TemplateCode) 不能为空");

        String signName = StringUtils.hasText(request.getSignName()) ? request.getSignName() : channel.getSignName();
        BusinessAssert.hasText(signName, "短信签名 (SignName) 不能为空，请在请求或渠道配置中显式指定");

        // 2. 组装待发送业务与公共参数
        Map<String, String> params = new HashMap<>();
        params.put("Action", "SendSms");
        params.put("Version", API_VERSION);
        params.put("Format", "JSON");
        params.put("SignatureMethod", SIGNATURE_METHOD);
        params.put("SignatureVersion", "1.0");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("Timestamp", UTC_FORMATTER.format(Instant.now()));
        params.put("AccessKeyId", channel.getAccessKeyId());
        params.put("RegionId", StringUtils.hasText(channel.getRegion()) ? channel.getRegion() : "cn-hangzhou");

        // 手机号拼接（多手机号以英文逗号分隔）
        params.put("PhoneNumbers", String.join(",", request.getPhones()));
        params.put("SignName", signName);
        params.put("TemplateCode", request.getTemplateId());

        // 模板动态变量映射为 JSON 字符串
        if (request.getParams() != null && !request.getParams().isEmpty()) {
            params.put("TemplateParam", request.getParams().toJsonString());
        }

        // 外部业务流水追踪号 (OutId)
        if (StringUtils.hasText(request.getBizId())) {
            params.put("OutId", request.getBizId());
        }

        // 3. 原生 POP RPC 签名生成
        String signature = generateSignature(params, "POST", channel.getAccessKeySecret());

        // 4. 构建 application/x-www-form-urlencoded 请求体（包含 URL 编码后的 Signature）
        StringBuilder formBody = new StringBuilder();
        formBody.append("Signature=").append(percentEncode(signature));
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getValue() != null) {
                formBody.append("&")
                        .append(percentEncode(entry.getKey()))
                        .append("=")
                        .append(percentEncode(entry.getValue()));
            }
        }

        // 5. 确定请求端点并执行网络调用
        String endpoint = StringUtils.hasText(channel.getEndpoint()) ? channel.getEndpoint() : DEFAULT_ENDPOINT;
        Duration timeout = channel.getTimeout() != null ? channel.getTimeout() : Duration.ofSeconds(10);
        RestClient client = buildRestClient(timeout);

        String responseBody;
        try {
            responseBody = client.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formBody.toString())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            String errorBody = e.getResponseBodyAsString();
            parseAndThrowExceptionIfPossible(channel.getName(), errorBody, e);
            throw new SmsSendException(channel.getName(), "HTTP_" + e.getStatusCode().value(),
                    e.getStatusText(), SmsResultCode.SMS_SEND_ERROR, "阿里云短信网关响应异常: " + e.getMessage(), e);
        } catch (SmsSendException e) {
            throw e;
        } catch (Exception e) {
            throw new SmsSendException(channel.getName(), "NETWORK_ERROR", e.getMessage(),
                    SmsResultCode.SMS_SEND_ERROR, "阿里云短信网络通信异常: " + e.getMessage(), e);
        }

        // 6. 解析响应结果并执行因果异常转译
        return parseResponse(channel.getName(), request.getPhone(), request.getTemplateId(), responseBody);
    }

    /**
     * 生成阿里云 POP RPC 规范的 HMAC-SHA1 签名
     *
     * @param params          所有待签名参数集合（排除 Signature）
     * @param httpMethod      HTTP 请求方法（如 POST）
     * @param accessKeySecret 访问密钥 Secret
     * @return Base64 编码的签名串
     */
    public static String generateSignature(Map<String, String> params, String httpMethod, String accessKeySecret) {
        BusinessAssert.hasText(accessKeySecret, "accessKeySecret 不能为空");
        BusinessAssert.notNull(params, "待签名参数集合不能为空");

        // 1. 参数按 ASCII 字典序自然升序排列
        Map<String, String> sortedMap = new TreeMap<>(params);

        // 2. 构造规范化查询字符串 CanonicalizedQueryString
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> entry : sortedMap.entrySet()) {
            if ("Signature".equals(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            if (canonical.length() > 0) {
                canonical.append("&");
            }
            canonical.append(percentEncode(entry.getKey()))
                    .append("=")
                    .append(percentEncode(entry.getValue()));
        }

        // 3. 构造待签名字符串 StringToSign
        String stringToSign = httpMethod + "&" + percentEncode("/") + "&" + percentEncode(canonical.toString());

        // 4. 使用 HMAC-SHA1 计算哈希并进行 Base64 编码
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec((accessKeySecret + "&").getBytes(StandardCharsets.UTF_8), MAC_ALGORITHM));
            byte[] signBytes = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signBytes);
        } catch (Exception e) {
            throw new IllegalStateException("计算阿里云短信 HMAC-SHA1 签名异常: " + e.getMessage(), e);
        }
    }

    /**
     * 遵循 RFC 3986 规范的 URL 百分号编码
     * 针对 A-Z、a-z、0-9 以及 '-'、'_'、'.'、'~' 不编码
     * 将 '+' 转换为 '%20'，'*' 转换为 '%2A'，'%7E' 转换为 '~'
     *
     * @param value 原始待编码字符串
     * @return 符合规范的百分号编码字符串
     */
    public static String percentEncode(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    /**
     * 解析成功响应或抛出因果业务异常
     */
    private SmsResult parseResponse(String channelName, String phone, String templateId, String responseBody) {
        Map<String, Object> respMap = parseJsonSafely(responseBody);
        String code = getFieldIgnoreCase(respMap, "Code");
        String message = getFieldIgnoreCase(respMap, "Message");
        String bizId = getFieldIgnoreCase(respMap, "BizId");
        String requestId = getFieldIgnoreCase(respMap, "RequestId");

        if ("OK".equalsIgnoreCase(code)) {
            log.info("[AliyunSmsProvider] 阿里云发信成功 -> 渠道: {}, 目标号码: {}, 模板ID: {}, bizId: {}, requestId: {}",
                    channelName, DesensitizeUtil.mobilePhone(phone), templateId, bizId, requestId);
            return SmsResult.success(channelName, SmsSupplier.ALIYUN, bizId, requestId, 1);
        }

        throw new SmsSendException(channelName, code, message, SmsResultCode.SMS_SEND_ERROR,
                "阿里云短信发送失败: [" + code + "] " + message);
    }

    /**
     * 当收到 HTTP 非 200 响应时，尝试从 Body 解析阿里云结构化错误并抛出
     */
    private void parseAndThrowExceptionIfPossible(String channelName, String errorBody, Exception cause) {
        if (!StringUtils.hasText(errorBody)) {
            return;
        }
        try {
            Map<String, Object> respMap = parseJsonSafely(errorBody);
            String code = getFieldIgnoreCase(respMap, "Code");
            String message = getFieldIgnoreCase(respMap, "Message");
            if (StringUtils.hasText(code)) {
                throw new SmsSendException(channelName, code, message, SmsResultCode.SMS_SEND_ERROR,
                        "阿里云短信网关拒绝: [" + code + "] " + message, cause);
            }
        } catch (SmsSendException e) {
            throw e;
        } catch (Exception ignored) {
            // 解析失败不影响兜底异常向外抛出
        }
    }

    private String getFieldIgnoreCase(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null) {
                return String.valueOf(entry.getValue());
            }
        }
        return null;
    }
}

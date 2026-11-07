package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.sms.exception.SmsResultCode;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * 云片网短信服务驱动实现 (Yunpian)
 * 基于 Spring 6 原生 RestClient 与 JDK 17，原生实现云片网 HTTP 协议规范
 * 100% 纯原生零第三方 SDK 依赖，支持单条与批量模板短信投递、变量自适应转译与因果异常封装
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class YunpianSmsProvider extends AbstractHttpSmsProvider {

    /**
     * 默认云片网官方接入端点
     */
    public static final String DEFAULT_ENDPOINT = "https://sms.yunpian.com/v2";

    public YunpianSmsProvider(RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
    }

    public YunpianSmsProvider() {
        this(RestClient.builder());
    }

    @Override
    public SmsSupplier getSupplier() {
        return SmsSupplier.YUNPIAN;
    }

    @Override
    public SmsResult send(SmsRequest request, SmsChannel channel) {
        // 1. 前置卫语句防卫
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        BusinessAssert.notNull(channel, "短信渠道 SmsChannel 不能为空");

        String apiKey = StringUtils.hasText(channel.getAccessKeyId()) ? channel.getAccessKeyId() : channel.getAccessKeySecret();
        BusinessAssert.hasText(apiKey, "云片网短信 apikey (AccessKeyId) 不能为空");
        BusinessAssert.hasText(request.getPhone(), "接收短信的目标手机号不能为空");
        BusinessAssert.hasText(request.getTemplateId(), "云片网短信模板 ID (tpl_id) 不能为空");

        List<String> phones = request.getPhones();
        boolean isBatch = phones.size() > 1;

        // 2. 组装表单参数
        Map<String, String> formParams = new LinkedHashMap<>();
        formParams.put("apikey", apiKey.trim());
        formParams.put("mobile", String.join(",", phones));
        formParams.put("tpl_id", request.getTemplateId().trim());

        String tplValue = formatTplValue(request.getParams());
        if (StringUtils.hasText(tplValue)) {
            formParams.put("tpl_value", tplValue);
        }

        // 厂商状态报告推送地址
        if (StringUtils.hasText(channel.getStatusCallback())) {
            formParams.put("callback_url", channel.getStatusCallback().trim());
        }

        // 业务自定义流水追踪号
        if (StringUtils.hasText(request.getBizId())) {
            formParams.put("uid", request.getBizId().trim());
        }

        // 3. 构建 application/x-www-form-urlencoded 请求体
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

        // 4. 解析目标请求端点
        String endpoint = resolveEndpoint(channel.getEndpoint(), isBatch);
        Duration timeout = channel.getTimeout() != null ? channel.getTimeout() : Duration.ofSeconds(10);
        RestClient client = buildRestClient(timeout);

        String actionName = isBatch ? "TplBatchSend" : "TplSingleSend";
        String responseBody;
        try {
            responseBody = executeWithRetry(channel, actionName, () -> client.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class));
        } catch (RestClientResponseException e) {
            String errorBody = e.getResponseBodyAsString();
            parseAndThrowExceptionIfPossible(channel.getName(), errorBody, e);
            throw new SmsSendException(channel.getName(), "HTTP_" + e.getStatusCode().value(),
                    e.getStatusText(), SmsResultCode.SMS_SEND_ERROR, "云片网短信网关响应异常: " + e.getMessage(), e);
        } catch (SmsSendException e) {
            throw e;
        } catch (Exception e) {
            throw new SmsSendException(channel.getName(), "HTTP_CLIENT_ERROR", e.getMessage(),
                    SmsResultCode.SMS_SEND_ERROR, "云片网短信网络调用异常: " + e.getMessage(), e);
        }

        // 5. 校验并解析云厂商回执报文
        return parseResponse(channel.getName(), request.getPhone(), request.getTemplateId(), responseBody, phones.size());
    }

    /**
     * 将 SmsParam 转译为云片网规定的 tpl_value 格式 (#key#=urlEncode(value)&...)
     *
     * @param params 模板动态参数模型
     * @return 格式化后的 tpl_value 字符串
     */
    public static String formatTplValue(SmsParam params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.asMap().entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("&");
            }
            String key = entry.getKey();
            if (key.startsWith("#") && key.endsWith("#")) {
                sb.append(key).append("=");
            } else {
                sb.append("#").append(key).append("#=");
            }
            sb.append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * 解析请求端点
     */
    private String resolveEndpoint(String channelEndpoint, boolean isBatch) {
        if (!StringUtils.hasText(channelEndpoint)) {
            return DEFAULT_ENDPOINT + (isBatch ? "/sms/tpl_batch_send.json" : "/sms/tpl_single_send.json");
        }
        String ep = channelEndpoint.trim();
        if (ep.endsWith(".json")) {
            return ep;
        }
        if (ep.endsWith("/")) {
            ep = ep.substring(0, ep.length() - 1);
        }
        return ep + (isBatch ? "/sms/tpl_batch_send.json" : "/sms/tpl_single_send.json");
    }

    /**
     * 解析云片网响应或抛出因果异常
     */
    private SmsResult parseResponse(String channelName, String phone, String templateId, String responseBody, int phoneCount) {
        Map<String, Object> rootMap = parseJsonSafely(responseBody);

        // 1. 批量明细返回判定 (tpl_batch_send.json)
        if (rootMap.containsKey("data") && rootMap.get("data") instanceof List<?> dataList) {
            if (dataList.isEmpty()) {
                throw new SmsSendException(channelName, "EMPTY_DATA", "云片网响应明细 data 为空",
                        SmsResultCode.SMS_SEND_ERROR, "云片网响应明细为空: " + responseBody);
            }
            int totalFee = 0;
            String firstSid = null;
            for (Object item : dataList) {
                if (item instanceof Map<?, ?> itemMap) {
                    Object codeObj = itemMap.get("code");
                    int itemCode = codeObj instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(codeObj));
                    if (itemCode != 0) {
                        String msg = String.valueOf(itemMap.get("msg"));
                        throw new SmsSendException(channelName, String.valueOf(itemCode), msg,
                                SmsResultCode.SMS_SEND_ERROR, "云片网批量发信失败: [" + itemCode + "] " + msg);
                    }
                    if (firstSid == null && itemMap.get("sid") != null) {
                        firstSid = String.valueOf(itemMap.get("sid"));
                    }
                    Object countObj = itemMap.get("count");
                    totalFee += countObj instanceof Number cn ? cn.intValue() : 1;
                }
            }
            log.info("[YunpianSmsProvider] 云片网批量发信成功 -> 渠道: {}, 号码数: {}, 模板ID: {}, 首个SID: {}",
                    channelName, phoneCount, templateId, firstSid);
            return SmsResult.success(channelName, SmsSupplier.YUNPIAN, firstSid, firstSid, totalFee > 0 ? totalFee : phoneCount);
        }

        // 2. 单条明细返回判定 (tpl_single_send.json)
        Object codeObj = rootMap.get("code");
        if (codeObj == null) {
            throw new SmsSendException(channelName, "INVALID_RESPONSE", "云片网响应缺少 code 字段",
                    SmsResultCode.SMS_SEND_ERROR, "云片网响应格式异常: " + responseBody);
        }
        int code = codeObj instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(codeObj));
        if (code != 0) {
            String msg = String.valueOf(rootMap.get("msg"));
            String detail = rootMap.get("detail") != null ? String.valueOf(rootMap.get("detail")) : msg;
            throw new SmsSendException(channelName, String.valueOf(code), msg,
                    SmsResultCode.SMS_SEND_ERROR, "云片网短信发送失败: [" + code + "] " + detail);
        }

        String sid = rootMap.get("sid") != null ? String.valueOf(rootMap.get("sid")) : null;
        Object countObj = rootMap.get("count");
        int count = countObj instanceof Number cn ? cn.intValue() : 1;

        log.info("[YunpianSmsProvider] 云片网发信成功 -> 渠道: {}, 目标号码: {}, 模板ID: {}, SID: {}, 计费条数: {}",
                channelName, DesensitizeUtil.mobilePhone(phone), templateId, sid, count);

        return SmsResult.success(channelName, SmsSupplier.YUNPIAN, sid, sid, count);
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
                String msg = String.valueOf(rootMap.get("msg"));
                String detail = rootMap.get("detail") != null ? String.valueOf(rootMap.get("detail")) : msg;
                throw new SmsSendException(channelName, code, msg, SmsResultCode.SMS_SEND_ERROR,
                        "云片网短信网关拒绝: [" + code + "] " + detail, cause);
            }
        } catch (SmsSendException e) {
            throw e;
        } catch (Exception ignored) {
            // 解析失败时由外部继续抛出兜底异常
        }
    }

    private static String urlEncode(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

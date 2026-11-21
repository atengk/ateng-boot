package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.core.util.BatchSliceUtil;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sms.exception.SmsResultCode;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.model.*;
import io.github.atengk.boot.sms.support.SmsPhoneNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.*;

/**
 * 助通科技短信服务驱动实现 (Zhutong SMS v2)
 * 100% 纯原生实现双重 MD5 ({@code MD5(MD5(password) + tKey)}) 鉴权，
 * 支持 {@code v2/sendSmsTp}（模板短信，含 {@code extCode} 扩展码与原生个性化批量）与 {@code v2/sendSms}（无模板纯文本直发）双模
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class ZhutongSmsProvider extends AbstractHttpSmsProvider {

    /**
     * 默认助通科技官方公网接入端点
     */
    public static final String DEFAULT_ENDPOINT = "https://api.mix2.zthysms.com";
    private static final String PATH_TEMPLATE = "/v2/sendSmsTp";
    private static final String PATH_PLAIN = "/v2/sendSms";

    public ZhutongSmsProvider(RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
    }

    public ZhutongSmsProvider() {
        this(RestClient.builder());
    }

    @Override
    public SmsSupplier getSupplier() {
        return SmsSupplier.ZHUTONG;
    }

    @Override
    public SmsResult send(SmsRequest request, SmsChannel channel) {
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        BusinessAssert.notNull(channel, "短信渠道 SmsChannel 不能为空");
        BusinessAssert.hasText(channel.getAccessKeyId(), "助通短信账号 username (accessKeyId) 不能为空");
        BusinessAssert.hasText(channel.getAccessKeySecret(), "助通短信密码 password (accessKeySecret) 不能为空");
        BusinessAssert.hasText(request.getPhone(), "接收短信的目标手机号不能为空");

        boolean isPlainText = !StringUtils.hasText(request.getTemplateId()) && StringUtils.hasText(request.getContent());
        String signName = channel.resolveSignName(request.getTemplateId(), request.getSignName());

        List<String> formattedPhones = SmsPhoneNormalizer.toDomesticOrE164List(request.getPhones(), channel);

        if (isPlainText) {
            return sendPlainText(channel, request, signName, formattedPhones);
        }

        BusinessAssert.hasText(request.getTemplateId(), "助通短信模板 ID (tpId) 或纯文本正文不能同时为空");
        BusinessAssert.hasText(signName, "助通模板短信已报备签名 (signName) 不能为空");

        Map<String, Object> sharedParams = SmsParam.toMap(
                request.getParams(), channel.getParamOrder(request.getTemplateId()));
        List<Map<String, Object>> records = new ArrayList<>(formattedPhones.size());
        for (String phone : formattedPhones) {
            Map<String, Object> record = new LinkedHashMap<>(2);
            record.put("mobile", phone);
            record.put("tpContent", sharedParams);
            records.add(record);
        }
        return executeTemplateBatch(channel, request.getTemplateId().trim(), signName, request.getBizId(),
                request.getPhone(), formattedPhones.size(), records);
    }

    @Override
    public BatchSmsResult sendPersonalizedBatch(BatchSmsRequest request, SmsChannel channel) {
        if (request == null || !request.isPersonalized() || !StringUtils.hasText(request.getTemplateId())) {
            return null;
        }
        BusinessAssert.notNull(channel, "短信渠道 SmsChannel 不能为空");
        BusinessAssert.hasText(channel.getAccessKeyId(), "助通短信账号 username (accessKeyId) 不能为空");
        BusinessAssert.hasText(channel.getAccessKeySecret(), "助通短信密码 password (accessKeySecret) 不能为空");

        String signName = channel.resolveSignName(request.getTemplateId(), request.getSignName());
        BusinessAssert.hasText(signName, "助通模板短信已报备签名 (signName) 不能为空");

        List<String> phones = request.getPhones();
        List<List<String>> slices = BatchSliceUtil.slice(phones, getBatchLimit());
        List<String> failedPhones = new ArrayList<>();
        List<SmsResult> results = new ArrayList<>(slices.size());
        int successCount = 0;

        for (List<String> slice : slices) {
            List<Map<String, Object>> records = new ArrayList<>(slice.size());
            for (String rawPhone : slice) {
                SmsParam pParam = request.getPhoneParams().getOrDefault(rawPhone, request.getParams());
                Map<String, Object> tpContent = SmsParam.toMap(
                        pParam, channel.getParamOrder(request.getTemplateId()));
                Map<String, Object> record = new LinkedHashMap<>(2);
                record.put("mobile", SmsPhoneNormalizer.toDomesticOrE164(rawPhone, channel));
                record.put("tpContent", tpContent);
                records.add(record);
            }
            try {
                SmsResult sliceRes = executeTemplateBatch(channel, request.getTemplateId().trim(), signName,
                        request.getBizId(), slice.get(0), slice.size(), records);
                results.add(sliceRes);
                if (sliceRes.isSuccess()) {
                    successCount += slice.size();
                } else {
                    failedPhones.addAll(slice);
                }
            } catch (Exception ex) {
                failedPhones.addAll(slice);
                String rawCode = ex instanceof SmsSendException sse ? sse.getRawCode() : "ZHUTONG_BATCH_ERROR";
                results.add(SmsResult.failure(channel.getName(), SmsSupplier.ZHUTONG, rawCode, ex.getMessage()));
            }
        }
        return BatchSmsResult.of(phones.size(), successCount, failedPhones, Collections.emptyList(), results);
    }

    private SmsResult sendPlainText(SmsChannel channel, SmsRequest request, String signName, List<String> formattedPhones) {
        String fullContent = prependSignIfAbsent(signName, request.getContent());
        String tKey = String.valueOf(Instant.now().getEpochSecond());

        Map<String, Object> bodyMap = new LinkedHashMap<>(6);
        bodyMap.put("username", channel.getAccessKeyId().trim());
        bodyMap.put("password", signPassword(channel.getAccessKeySecret().trim(), tKey));
        bodyMap.put("tKey", tKey);
        bodyMap.put("mobile", String.join(",", formattedPhones));
        bodyMap.put("content", fullContent);
        if (StringUtils.hasText(channel.getExtCode())) {
            bodyMap.put("ext", channel.getExtCode().trim());
        }
        if (StringUtils.hasText(request.getBizId())) {
            bodyMap.put("extend", request.getBizId().trim());
        }

        String url = resolveActionUrl(normalizeEndpoint(channel.getEndpoint(), DEFAULT_ENDPOINT), PATH_PLAIN);
        String responseBody = executePostWithRetry(
                channel, "助通科技", "v2/sendSms", url, "application/json;charset=utf-8",
                null, JsonUtil.toJsonString(bodyMap),
                httpEx -> throwJsonHttpErrorIfPresent(channel, "助通科技", httpEx, "code", "msg")
        );
        return parseResponse(channel.getName(), request.getPhone(), "[PLAIN_TEXT]", responseBody, formattedPhones.size());
    }

    private SmsResult executeTemplateBatch(SmsChannel channel, String templateId, String signName, String bizId,
                                           String firstRawPhone, int phoneCount, List<Map<String, Object>> records) {
        String tKey = String.valueOf(Instant.now().getEpochSecond());

        Map<String, Object> bodyMap = new LinkedHashMap<>(8);
        bodyMap.put("username", channel.getAccessKeyId().trim());
        bodyMap.put("tKey", tKey);
        bodyMap.put("password", signPassword(channel.getAccessKeySecret().trim(), tKey));
        bodyMap.put("tpId", templateId);
        bodyMap.put("signature", ensureBracketedSign(signName));
        bodyMap.put("ext", channel.getExtCode() != null ? channel.getExtCode().trim() : "");
        bodyMap.put("extend", bizId != null ? bizId.trim() : "");
        bodyMap.put("records", records);

        String url = resolveActionUrl(normalizeEndpoint(channel.getEndpoint(), DEFAULT_ENDPOINT), PATH_TEMPLATE);
        String responseBody = executePostWithRetry(
                channel, "助通科技", "v2/sendSmsTp", url, "application/json;charset=utf-8",
                null, JsonUtil.toJsonString(bodyMap),
                httpEx -> throwJsonHttpErrorIfPresent(channel, "助通科技", httpEx, "code", "msg")
        );
        return parseResponse(channel.getName(), firstRawPhone, templateId, responseBody, phoneCount);
    }

    private static String signPassword(String plainPassword, String tKey) {
        return DigestUtil.md5Hex(DigestUtil.md5Hex(plainPassword) + tKey);
    }

    private SmsResult parseResponse(String channelName, String phone, String templateLabel, String responseBody, int phoneCount) {
        Map<String, Object> root = parseJsonSafely(responseBody);
        String code = root.get("code") != null ? String.valueOf(root.get("code")) : "UNKNOWN";
        if (!"200".equals(code)) {
            String msg = root.get("msg") != null ? String.valueOf(root.get("msg")) : "Zhutong SMS failed";
            throw new SmsSendException(channelName, code, msg, SmsResultCode.SMS_SEND_ERROR,
                    "助通科技短信发送失败: [" + code + "] " + msg);
        }

        String msgId = root.get("msgId") != null ? String.valueOf(root.get("msgId")) : null;
        if (log.isInfoEnabled()) {
            log.info("[ZhutongSmsProvider] 助通科技发信成功 -> 渠道: {}, 目标号码: {}, 模板: {}, MsgId: {}",
                    channelName, DesensitizeUtil.mobilePhone(phone), templateLabel, msgId);
        }
        return SmsResult.success(channelName, SmsSupplier.ZHUTONG, msgId, msgId, phoneCount);
    }
}

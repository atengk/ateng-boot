package io.github.atengk.boot.sms.interceptor;

import io.github.atengk.boot.sms.model.BatchSmsRequest;
import io.github.atengk.boot.sms.model.BatchSmsResult;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import lombok.Getter;
import lombok.Setter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 短信发送拦截链执行上下文
 * <p>
 * 贯穿 {@link SmsSendInterceptor} 的 {@code preSend}、{@code postSend} 与 {@code onError} 全生命周期，
 * 支持单条与批量发信模型透传、渠道与请求上下文改写及短路模拟结果注入。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Getter
@Setter
public class SmsSendContext {

    /**
     * 是否为批量发信流程
     */
    private final boolean batch;

    /**
     * 单条发信请求（单发模式非空）
     */
    private SmsRequest request;

    /**
     * 批量发信请求（批量模式非空）
     */
    private BatchSmsRequest batchRequest;

    /**
     * 当前解析得到的目标发信渠道
     */
    private SmsChannel channel;

    /**
     * 拦截器间共享扩展属性字典
     */
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    /**
     * 单发短路结果（例如沙箱 log-only 模拟成功）
     */
    private SmsResult shortCircuitResult;

    /**
     * 批量短路结果（例如沙箱 log-only 模拟成功）
     */
    private BatchSmsResult shortCircuitBatchResult;

    /**
     * 拦截阻断原因说明
     */
    private String blockReason;

    private SmsSendContext(boolean batch, SmsRequest request, BatchSmsRequest batchRequest, SmsChannel channel) {
        this.batch = batch;
        this.request = request;
        this.batchRequest = batchRequest;
        this.channel = channel;
    }

    /**
     * 创建单条发信拦截上下文
     *
     * @param request 单条发信请求
     * @param channel 目标短信渠道
     * @return 上下文实例
     */
    public static SmsSendContext forSingle(SmsRequest request, SmsChannel channel) {
        return new SmsSendContext(false, request, null, channel);
    }

    /**
     * 创建批量发信拦截上下文
     *
     * @param batchRequest 批量发信请求
     * @param channel      目标短信渠道
     * @return 上下文实例
     */
    public static SmsSendContext forBatch(BatchSmsRequest batchRequest, SmsChannel channel) {
        return new SmsSendContext(true, null, batchRequest, channel);
    }

    /**
     * 获取当前请求关联的目标手机号列表
     *
     * @return 手机号列表（保底返回空列表）
     */
    public List<String> getPhones() {
        if (batch && batchRequest != null) {
            return batchRequest.getPhones();
        }
        if (!batch && request != null) {
            return request.getPhones();
        }
        return Collections.emptyList();
    }

    /**
     * 写入扩展上下文属性
     *
     * @param key   属性键
     * @param value 属性值
     */
    public void setAttribute(String key, Object value) {
        if (key != null) {
            attributes.put(key, value);
        }
    }

    /**
     * 读取扩展上下文属性
     *
     * @param key 属性键
     * @param <T> 属性值类型
     * @return 属性值，不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }
}

package io.github.atengk.boot.sample.liteflow.context;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态规则链即时执行业务上下文
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
public class DynamicFlowContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 原始入参 Payload
     */
    private String inputPayload;

    /**
     * 业务执行产出结果
     */
    private Object result;

    /**
     * 上下文变量与中间态属性
     */
    private Map<String, Object> attributes = new ConcurrentHashMap<>();

    /**
     * 解析并补齐有效 Payload（若未设置则尝试读取兜底对象）
     *
     * @param defaultPayload 兜底或全局请求入参对象
     * @return 最终生效的 Payload 字符串
     */
    public String resolvePayload(Object defaultPayload) {
        if (this.inputPayload == null && defaultPayload != null) {
            this.inputPayload = defaultPayload.toString();
        }
        return this.inputPayload;
    }

    /**
     * 注入属性
     *
     * @param key   键
     * @param value 值
     */
    public void put(String key, Object value) {
        if (key != null && value != null) {
            attributes.put(key, value);
        }
    }

    /**
     * 读取属性
     *
     * @param key 键
     * @return 值
     */
    public Object get(String key) {
        return attributes.get(key);
    }
}

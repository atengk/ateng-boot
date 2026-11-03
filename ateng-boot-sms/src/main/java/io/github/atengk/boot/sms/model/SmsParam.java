package io.github.atengk.boot.sms.model;

import io.github.atengk.boot.core.util.JsonUtil;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.*;

/**
 * 统一短信模板参数领域模型
 * 支持键值对映射与有序数组双模构建，由底座依据云厂商特性自适应转换
 *
 * @author Ateng
 * @since 2026-11-02
 */
@EqualsAndHashCode
public class SmsParam implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 键值对参数字典
     */
    private final Map<String, Object> mapParams;

    /**
     * 有序数组参数列表
     */
    private final List<String> arrayParams;

    /**
     * 是否显式为数组模式
     */
    private final boolean arrayMode;

    private SmsParam(Map<String, Object> mapParams, List<String> arrayParams, boolean arrayMode) {
        this.mapParams = mapParams != null ? new LinkedHashMap<>(mapParams) : new LinkedHashMap<>();
        this.arrayParams = arrayParams != null ? new ArrayList<>(arrayParams) : new ArrayList<>();
        this.arrayMode = arrayMode;
    }

    /**
     * 创建空的键值对参数模型
     *
     * @return SmsParam 实例
     */
    public static SmsParam create() {
        return new SmsParam(new LinkedHashMap<>(), null, false);
    }

    /**
     * 基于单个键值对创建参数模型
     *
     * @param key   参数名
     * @param value 参数值
     * @return SmsParam 实例
     */
    public static SmsParam of(String key, Object value) {
        SmsParam param = create();
        param.add(key, value);
        return param;
    }

    /**
     * 基于既有 Map 创建键值对参数模型
     *
     * @param map 参数字典
     * @return SmsParam 实例
     */
    public static SmsParam ofMap(Map<String, ?> map) {
        SmsParam param = create();
        if (map != null) {
            map.forEach(param::add);
        }
        return param;
    }

    /**
     * 基于可变字符串数组创建有序数组参数模型
     *
     * @param params 有序字符串参数
     * @return SmsParam 实例
     */
    public static SmsParam ofArray(String... params) {
        List<String> list = params != null ? Arrays.asList(params) : Collections.emptyList();
        return new SmsParam(null, list, true);
    }

    /**
     * 基于列表创建有序数组参数模型
     *
     * @param params 有序字符串列表
     * @return SmsParam 实例
     */
    public static SmsParam ofArray(List<String> params) {
        return new SmsParam(null, params, true);
    }

    /**
     * 流式追加键值对参数
     *
     * @param key   参数名
     * @param value 参数值
     * @return 当前 SmsParam 实例
     */
    public SmsParam add(String key, Object value) {
        if (key != null) {
            this.mapParams.put(key, value);
        }
        return this;
    }

    /**
     * 获取不可修改的键值对参数字典
     *
     * @return 字典映射
     */
    public Map<String, Object> getMapParams() {
        return Collections.unmodifiableMap(mapParams);
    }

    /**
     * 获取不可修改的有序数组参数列表
     *
     * @return 数组列表
     */
    public List<String> getArrayParams() {
        return Collections.unmodifiableList(arrayParams);
    }

    /**
     * 是否为原生数组模式
     *
     * @return true 表示显式传入了数组
     */
    public boolean isArrayMode() {
        return arrayMode;
    }

    /**
     * 序列化键值对为 JSON 字符串（针对阿里云等厂商）
     *
     * @return JSON 字符串，若无参数则返回空 JSON 对象 "{}"
     */
    public String toJsonString() {
        if (mapParams.isEmpty()) {
            if (arrayMode && !arrayParams.isEmpty()) {
                // 若仅有数组参数，保底构造数字键 JSON {"1":"val1", "2":"val2"}
                Map<String, Object> fallbackMap = new LinkedHashMap<>();
                for (int i = 0; i < arrayParams.size(); i++) {
                    fallbackMap.put(String.valueOf(i + 1), arrayParams.get(i));
                }
                return JsonUtil.toJsonString(fallbackMap);
            }
            return "{}";
        }
        return JsonUtil.toJsonString(mapParams);
    }

    /**
     * 将参数自适应转换为有序字符串数组（针对腾讯云、华为云等厂商）
     * 转换优先级：
     * 1. 若为原生数组模式，直接返回原生数组；
     * 2. 若配置了 paramOrder，按顺序从 mapParams 提取值；
     * 3. 若 key 为纯数字，按数字升序提取值；
     * 4. 否则按 LinkedHashMap 插入顺序提取全部 values。
     *
     * @param paramOrder 配置声明的模板参数顺序（可为 null）
     * @return 有序字符串数组列表
     */
    public List<String> toOrderedArray(List<String> paramOrder) {
        if (arrayMode && !arrayParams.isEmpty()) {
            return new ArrayList<>(arrayParams);
        }
        if (mapParams.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 若配置了声明式顺序
        if (paramOrder != null && !paramOrder.isEmpty()) {
            List<String> result = new ArrayList<>(paramOrder.size());
            for (String key : paramOrder) {
                Object val = mapParams.get(key);
                result.add(val != null ? String.valueOf(val) : "");
            }
            return result;
        }

        // 2. 检查 Key 是否均为数字 (如 "1", "2")
        boolean allNumericKeys = mapParams.keySet().stream().allMatch(k -> {
            try {
                Integer.parseInt(k);
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        if (allNumericKeys) {
            List<String> result = new ArrayList<>();
            mapParams.keySet().stream()
                    .map(Integer::parseInt)
                    .sorted()
                    .forEach(numKey -> {
                        Object val = mapParams.get(String.valueOf(numKey));
                        result.add(val != null ? String.valueOf(val) : "");
                    });
            return result;
        }

        // 3. 默认按 Map 插入顺序提取
        List<String> result = new ArrayList<>(mapParams.size());
        for (Object val : mapParams.values()) {
            result.add(val != null ? String.valueOf(val) : "");
        }
        return result;
    }

    @Override
    public String toString() {
        if (arrayMode) {
            return arrayParams.toString();
        }
        return mapParams.toString();
    }
}

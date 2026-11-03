package io.github.atengk.boot.ai.graph;

import io.github.atengk.boot.core.util.JsonUtil;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认知状态图强类型状态模型抽象基类
 * <p>
 * 继承 LangGraph4j {@link AgentState}，通过 {@link TypedKey} 提供强类型数据访问与纯净 JSON 序列化契约。
 * 当底层数据反序列化为通用字典结构（如 {@link java.util.LinkedHashMap}）时，提供透明的延迟就地类型安全转译与回填缓存，
 * 彻底杜绝 {@link ClassCastException} 与远程代码执行安全隐患。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
public abstract class BaseGraphState extends AgentState {

    private static final Logger log = LoggerFactory.getLogger(BaseGraphState.class);
    private static final Field DATA_FIELD;

    static {
        Field field = null;
        try {
            field = AgentState.class.getDeclaredField("data");
            field.setAccessible(true);
        } catch (Exception e) {
            log.warn("无法反射访问 AgentState.data 内部属性，将启用降级缓存模式: {}", e.getMessage());
        }
        DATA_FIELD = field;
    }

    private final Map<String, Object> fallbackData = new ConcurrentHashMap<>();

    /**
     * 初始化状态图数据模型
     *
     * @param initData 初始状态字典（允许为 null）
     */
    public BaseGraphState(Map<String, Object> initData) {
        super(initData != null ? initData : Collections.emptyMap());
    }

    /**
     * 强类型安全读取状态属性
     * <p>
     * 若底层数据由于反序列化呈现为 Map 等中间结构，将自动利用 Core 底座 {@link JsonUtil}
     * 执行就地安全转译并回填缓存，保障后续读取达到 O(1) 极速性能。
     * </p>
     *
     * @param key 强类型键
     * @param <T> 目标类型
     * @return 包含目标对象的 Optional 包装
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(TypedKey<T> key) {
        if (key == null) {
            return Optional.empty();
        }
        Object raw = data().get(key.name());
        if (raw == null) {
            raw = fallbackData.get(key.name());
        }
        if (raw == null) {
            return Optional.empty();
        }

        Class<T> targetClass = key.getTargetClass();
        if (targetClass != null && targetClass.isInstance(raw)) {
            return Optional.of((T) raw);
        }

        try {
            T converted;
            if (targetClass != null) {
                converted = JsonUtil.convertValue(raw, targetClass);
            } else if (key.getTypeReference() != null) {
                converted = JsonUtil.convertValue(raw, key.getTypeReference());
            } else {
                converted = (T) raw;
            }
            if (converted != null) {
                updateRawData(key.name(), converted);
            }
            return Optional.ofNullable(converted);
        } catch (Exception e) {
            log.warn("BaseGraphState 状态属性 [{}] 转换为类型 [{}] 失败: {}", key.name(),
                    targetClass != null ? targetClass.getName() : key.getTypeReference(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 强类型读取状态属性（提供默认保底值）
     *
     * @param key 强类型键
     * @param defaultValue 缺省默认值
     * @param <T> 目标类型
     * @return 目标对象或默认值
     */
    public <T> T get(TypedKey<T> key, T defaultValue) {
        return get(key).orElse(defaultValue);
    }

    /**
     * 强类型前置卫语句读取状态属性（缺失时抛出异常）
     *
     * @param key 强类型键
     * @param <T> 目标类型
     * @return 目标对象
     * @throws IllegalStateException 属性缺失时抛出
     */
    public <T> T getOrThrow(TypedKey<T> key) {
        return get(key).orElseThrow(() -> new IllegalStateException("认知图状态必要属性 [" + key.name() + "] 缺失"));
    }

    /**
     * 强类型写入状态属性
     *
     * @param key 强类型键
     * @param value 目标对象值（为 null 时执行移除）
     * @param <T> 目标类型
     */
    public <T> void set(TypedKey<T> key, T value) {
        Objects.requireNonNull(key, "TypedKey must not be null");
        if (value == null) {
            remove(key);
        } else {
            updateRawData(key.name(), value);
        }
    }

    /**
     * 判断状态是否包含指定键
     *
     * @param key 强类型键
     * @return 是否存在
     */
    public boolean has(TypedKey<?> key) {
        if (key == null) {
            return false;
        }
        return data().containsKey(key.name()) || fallbackData.containsKey(key.name());
    }

    /**
     * 移除状态中的指定键
     *
     * @param key 强类型键
     */
    public void remove(TypedKey<?> key) {
        if (key == null) {
            return;
        }
        fallbackData.remove(key.name());
        Map<String, Object> rawMap = getRawDataMap();
        if (rawMap != null) {
            rawMap.remove(key.name());
        }
    }

    /**
     * 导出当前状态字典快照副本
     *
     * @return 纯净的键值字典
     */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>(data());
        result.putAll(fallbackData);
        return Collections.unmodifiableMap(result);
    }

    /**
     * 纯净 JSON 格式序列化导出
     *
     * @return JSON 文本
     */
    public String toJson() {
        return JsonUtil.toJsonString(toMap());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getRawDataMap() {
        if (DATA_FIELD != null) {
            try {
                return (Map<String, Object>) DATA_FIELD.get(this);
            } catch (Exception e) {
                log.trace("读取 AgentState.data 失败: {}", e.getMessage());
            }
        }
        return null;
    }

    private void updateRawData(String key, Object value) {
        Map<String, Object> rawMap = getRawDataMap();
        if (rawMap != null) {
            rawMap.put(key, value);
        } else {
            fallbackData.put(key, value);
        }
    }
}

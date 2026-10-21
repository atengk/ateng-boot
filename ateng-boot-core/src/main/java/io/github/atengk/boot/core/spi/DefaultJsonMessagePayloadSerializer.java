package io.github.atengk.boot.core.spi;

import io.github.atengk.boot.core.util.JsonUtil;

import java.lang.reflect.Type;

/**
 * 默认纯净 JSON 消息载荷序列化器实现
 * 统一依托 JsonUtil 静态门面，无 @class 污染
 *
 * @author Ateng
 * @since 2026-10-21
 */
public class DefaultJsonMessagePayloadSerializer implements MessagePayloadSerializer {

    public static final DefaultJsonMessagePayloadSerializer INSTANCE = new DefaultJsonMessagePayloadSerializer();

    @Override
    public byte[] serialize(Object payload) {
        return JsonUtil.toJsonBytes(payload);
    }

    @Override
    public <T> T deserialize(byte[] data, Class<T> targetClass) {
        return JsonUtil.parseObject(data, targetClass);
    }

    @Override
    public <T> T deserialize(byte[] data, Type targetType) {
        return JsonUtil.parseObject(data, targetType);
    }
}

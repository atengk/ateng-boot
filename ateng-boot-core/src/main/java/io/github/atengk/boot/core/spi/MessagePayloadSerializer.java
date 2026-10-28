package io.github.atengk.boot.core.spi;

import java.lang.reflect.Type;

/**
 * 跨中间件异步消息载荷序列化与反序列化 SPI 扩展点契约
 * 默认基于 Core 纯原生 JsonUtil 门面实现，彻底杜绝 @class 注入攻击
 *
 * @author Ateng
 * @since 2026-10-21
 */
public interface MessagePayloadSerializer {

    /**
     * 将业务对象序列化为字节数组
     *
     * @param payload 待序列化对象
     * @return 字节数组，入参为 null 时返回空数组
     */
    byte[] serialize(Object payload);

    /**
     * 将字节数组反序列化为指定类型的对象
     *
     * @param data        消息字节数组
     * @param targetClass 目标 Class
     * @param <T>         目标类型泛型
     * @return 反序列化对象
     */
    <T> T deserialize(byte[] data, Class<T> targetClass);

    /**
     * 将字节数组反序列化为复杂泛型对象
     *
     * @param data       消息字节数组
     * @param targetType 目标 Type (支持 ParameterizedType 等)
     * @param <T>        目标类型泛型
     * @return 反序列化对象
     */
    <T> T deserialize(byte[] data, Type targetType);

    /**
     * 获取全局默认纯净 JSON 序列化器实例
     *
     * @return 默认序列化器
     */
    static MessagePayloadSerializer defaultSerializer() {
        return DefaultJsonMessagePayloadSerializer.INSTANCE;
    }
}

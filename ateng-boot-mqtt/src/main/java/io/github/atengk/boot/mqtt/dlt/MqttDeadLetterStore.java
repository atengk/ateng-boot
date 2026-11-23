package io.github.atengk.boot.mqtt.dlt;

import io.github.atengk.boot.mqtt.model.MqttDeadLetterRecord;

import java.util.List;

/**
 * MQTT 死信记录持久化与检索 SPI
 * 用于突破 Broker 仅支持单条保留消息（Retained）的容量限制，支撑多条连续毒丸的顺序留存、精确重放与清理，并支持多命名客户端隔离
 *
 * @author Ateng
 * @since 2026-11-18
 */
public interface MqttDeadLetterStore {

    /**
     * 追加保存一条死信记录（默认客户端作用域）
     *
     * @param record 死信记录
     */
    void save(MqttDeadLetterRecord record);

    /**
     * 指定客户端作用域追加保存一条死信记录
     *
     * @param clientName 客户端逻辑名称
     * @param record     死信记录
     */
    default void save(String clientName, MqttDeadLetterRecord record) {
        save(record);
    }

    /**
     * 按先进先出（FIFO）顺序探查指定死信主题下最多 limit 条记录（默认客户端作用域）
     *
     * @param dltTopic 死信主题
     * @param limit    最大返回条数
     * @return 死信记录列表，无数据时返回空列表
     */
    List<MqttDeadLetterRecord> peek(String dltTopic, int limit);

    /**
     * 按先进先出（FIFO）顺序探查指定客户端与死信主题下最多 limit 条记录
     *
     * @param clientName 客户端逻辑名称
     * @param dltTopic   死信主题
     * @param limit      最大返回条数
     * @return 死信记录列表，无数据时返回空列表
     */
    default List<MqttDeadLetterRecord> peek(String clientName, String dltTopic, int limit) {
        return peek(dltTopic, limit);
    }

    /**
     * 从指定死信主题移除精确匹配的死信记录（默认客户端作用域）
     *
     * @param dltTopic 死信主题
     * @param recordId 死信记录唯一标识
     * @return true 若成功找到并移除
     */
    boolean remove(String dltTopic, String recordId);

    /**
     * 从指定客户端与死信主题移除精确匹配的死信记录
     *
     * @param clientName 客户端逻辑名称
     * @param dltTopic   死信主题
     * @param recordId   死信记录唯一标识
     * @return true 若成功找到并移除
     */
    default boolean remove(String clientName, String dltTopic, String recordId) {
        return remove(dltTopic, recordId);
    }

    /**
     * 清空指定死信主题下所有已存死信记录（默认客户端作用域）
     *
     * @param dltTopic 死信主题
     * @return 实际清理的记录数量
     */
    int purge(String dltTopic);

    /**
     * 清空指定客户端与死信主题下所有已存死信记录
     *
     * @param clientName 客户端逻辑名称
     * @param dltTopic   死信主题
     * @return 实际清理的记录数量
     */
    default int purge(String clientName, String dltTopic) {
        return purge(dltTopic);
    }
}

package io.github.atengk.boot.rocketmq.spi;

import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;

import java.util.Objects;

/**
 * RocketMQ 本地事务状态回查业务 SPI 接口
 *
 * <p>由各领域微服务业务组件实现并注入 Spring 容器，声明所负责监听和判定的 Topic。
 * 当 Broker 对超时未决的事务半消息触发回查时，全局统一回查分发器将根据 Topic 自动路由至对应实现执行决断，彻底消除单体上帝类。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
public interface RocketmqLocalTransactionChecker {

    /**
     * 绑定的 RocketMQ 主题 (Topic)
     *
     * @return 业务关心的 Topic 名称
     */
    String getTopic();

    /**
     * 判断当前 Checker 是否支持处理指定主题的消息回查
     *
     * @param topic 消息的主题名称
     * @return true 若支持
     */
    default boolean supports(String topic) {
        String supportedTopic = getTopic();
        return supportedTopic != null && supportedTopic.trim().equalsIgnoreCase(topic != null ? topic.trim() : null);
    }

    /**
     * 执行本地事务回查决策
     *
     * @param messageView Broker 推送的回查消息快照视图
     * @return 事务决断状态 (COMMIT, ROLLBACK, UNKNOWN)
     */
    TransactionResolution check(MessageView messageView);

    /**
     * 别名方法，兼容 checkLocalTransaction 语义调用
     *
     * @param messageView Broker 推送的回查消息快照视图
     * @return 事务决断状态 (COMMIT, ROLLBACK, UNKNOWN)
     */
    default TransactionResolution checkLocalTransaction(MessageView messageView) {
        return check(messageView);
    }
}

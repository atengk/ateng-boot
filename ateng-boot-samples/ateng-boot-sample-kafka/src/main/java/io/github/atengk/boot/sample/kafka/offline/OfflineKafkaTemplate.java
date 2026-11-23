package io.github.atengk.boot.sample.kafka.offline;

import io.micrometer.observation.Observation;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.kafka.support.SendResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 离线内存模拟 {@link KafkaTemplate}，在非 local Profile 下替代底层网络发送，将消息直接路由至 {@link OfflineKafkaBroker} 并完整保留事务提交与回滚语义
 *
 * @author Ateng
 * @since 2026-11-15
 */
public class OfflineKafkaTemplate extends KafkaTemplate<Object, Object> {

    private final OfflineKafkaBroker broker;
    private final ThreadLocal<Boolean> transactionActive = new ThreadLocal<>();
    private volatile ProducerListener<Object, Object> offlineProducerListener;

    public OfflineKafkaTemplate(
            ProducerFactory<Object, Object> producerFactory,
            OfflineKafkaBroker broker) {
        super(producerFactory);
        this.broker = Objects.requireNonNull(broker, "broker 不能为空");
    }

    @Override
    public void setProducerListener(ProducerListener<Object, Object> producerListener) {
        super.setProducerListener(producerListener);
        this.offlineProducerListener = producerListener;
    }

    @Override
    protected CompletableFuture<SendResult<Object, Object>> doSend(
            ProducerRecord<Object, Object> producerRecord,
            Observation observation) {
        ProducerListener<Object, Object> listener = this.offlineProducerListener;
        try {
            byte[] keyBytes = OfflineKafkaProxyFactory.serializeKeyToBytes(producerRecord.key());
            byte[] valueBytes = OfflineKafkaProxyFactory.serializeValueToBytes(producerRecord.value());
            RecordMetadata metadata = broker.publish(
                    producerRecord.topic(),
                    producerRecord.partition(),
                    producerRecord.timestamp(),
                    keyBytes,
                    valueBytes,
                    producerRecord.headers());
            if (listener != null) {
                listener.onSuccess(producerRecord, metadata);
            }
            return CompletableFuture.completedFuture(new SendResult<>(producerRecord, metadata));
        } catch (Throwable ex) {
            if (listener != null) {
                listener.onError(producerRecord, null, ex instanceof Exception e ? e : new RuntimeException(ex));
            }
            if (observation != null) {
                observation.error(ex);
            }
            CompletableFuture<SendResult<Object, Object>> failed = new CompletableFuture<>();
            failed.completeExceptionally(ex);
            return failed;
        } finally {
            if (observation != null) {
                observation.stop();
            }
        }
    }

    @Override
    public <T> T executeInTransaction(KafkaOperations.OperationsCallback<Object, Object, T> callback) {
        Objects.requireNonNull(callback, "callback 不能为空");
        boolean outer = Boolean.TRUE.equals(transactionActive.get());
        if (!outer) {
            transactionActive.set(Boolean.TRUE);
            broker.beginTransaction();
        }
        try {
            T result = callback.doInOperations(this);
            if (!outer) {
                broker.commitTransaction();
            }
            return result;
        } catch (Throwable ex) {
            if (!outer) {
                broker.rollbackTransaction();
            }
            throw ex;
        } finally {
            if (!outer) {
                transactionActive.remove();
            }
        }
    }

    @Override
    public boolean inTransaction() {
        return Boolean.TRUE.equals(transactionActive.get());
    }

    @Override
    public List<PartitionInfo> partitionsFor(String topic) {
        return broker.partitionsFor(topic);
    }

    @Override
    public void flush() {
        // 离线内存发送即时可见，无需刷盘阻塞
    }

    @Override
    public void sendOffsetsToTransaction(
            Map<TopicPartition, OffsetAndMetadata> offsets,
            ConsumerGroupMetadata groupMetadata) {
        if (groupMetadata != null) {
            broker.commitOffsets(groupMetadata.groupId(), offsets);
        }
    }

    @Override
    public Map<MetricName, ? extends Metric> metrics() {
        return Collections.emptyMap();
    }
}

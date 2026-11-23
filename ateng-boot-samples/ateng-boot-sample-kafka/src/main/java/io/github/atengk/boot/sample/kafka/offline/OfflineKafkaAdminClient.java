package io.github.atengk.boot.sample.kafka.offline;

import org.apache.kafka.clients.admin.AbortTransactionOptions;
import org.apache.kafka.clients.admin.AbortTransactionResult;
import org.apache.kafka.clients.admin.AbortTransactionSpec;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AlterClientQuotasOptions;
import org.apache.kafka.clients.admin.AlterClientQuotasResult;
import org.apache.kafka.clients.admin.AlterConfigsOptions;
import org.apache.kafka.clients.admin.AlterConfigsResult;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.AlterConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.AlterConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.AlterPartitionReassignmentsOptions;
import org.apache.kafka.clients.admin.AlterPartitionReassignmentsResult;
import org.apache.kafka.clients.admin.AlterReplicaLogDirsOptions;
import org.apache.kafka.clients.admin.AlterReplicaLogDirsResult;
import org.apache.kafka.clients.admin.AlterUserScramCredentialsOptions;
import org.apache.kafka.clients.admin.AlterUserScramCredentialsResult;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.CreateAclsOptions;
import org.apache.kafka.clients.admin.CreateAclsResult;
import org.apache.kafka.clients.admin.CreateDelegationTokenOptions;
import org.apache.kafka.clients.admin.CreateDelegationTokenResult;
import org.apache.kafka.clients.admin.CreatePartitionsOptions;
import org.apache.kafka.clients.admin.CreatePartitionsResult;
import org.apache.kafka.clients.admin.CreateTopicsOptions;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.DeleteAclsOptions;
import org.apache.kafka.clients.admin.DeleteAclsResult;
import org.apache.kafka.clients.admin.DeleteConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.DeleteConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.DeleteConsumerGroupsOptions;
import org.apache.kafka.clients.admin.DeleteConsumerGroupsResult;
import org.apache.kafka.clients.admin.DeleteRecordsOptions;
import org.apache.kafka.clients.admin.DeleteRecordsResult;
import org.apache.kafka.clients.admin.DeleteTopicsOptions;
import org.apache.kafka.clients.admin.DeleteTopicsResult;
import org.apache.kafka.clients.admin.DescribeAclsOptions;
import org.apache.kafka.clients.admin.DescribeAclsResult;
import org.apache.kafka.clients.admin.DescribeClientQuotasOptions;
import org.apache.kafka.clients.admin.DescribeClientQuotasResult;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.DescribeConfigsOptions;
import org.apache.kafka.clients.admin.DescribeConfigsResult;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsOptions;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsResult;
import org.apache.kafka.clients.admin.DescribeDelegationTokenOptions;
import org.apache.kafka.clients.admin.DescribeDelegationTokenResult;
import org.apache.kafka.clients.admin.DescribeFeaturesOptions;
import org.apache.kafka.clients.admin.DescribeFeaturesResult;
import org.apache.kafka.clients.admin.DescribeLogDirsOptions;
import org.apache.kafka.clients.admin.DescribeLogDirsResult;
import org.apache.kafka.clients.admin.DescribeMetadataQuorumOptions;
import org.apache.kafka.clients.admin.DescribeMetadataQuorumResult;
import org.apache.kafka.clients.admin.DescribeProducersOptions;
import org.apache.kafka.clients.admin.DescribeProducersResult;
import org.apache.kafka.clients.admin.DescribeReplicaLogDirsOptions;
import org.apache.kafka.clients.admin.DescribeReplicaLogDirsResult;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.DescribeTransactionsOptions;
import org.apache.kafka.clients.admin.DescribeTransactionsResult;
import org.apache.kafka.clients.admin.DescribeUserScramCredentialsOptions;
import org.apache.kafka.clients.admin.DescribeUserScramCredentialsResult;
import org.apache.kafka.clients.admin.ElectLeadersOptions;
import org.apache.kafka.clients.admin.ElectLeadersResult;
import org.apache.kafka.clients.admin.ExpireDelegationTokenOptions;
import org.apache.kafka.clients.admin.ExpireDelegationTokenResult;
import org.apache.kafka.clients.admin.FeatureUpdate;
import org.apache.kafka.clients.admin.FenceProducersOptions;
import org.apache.kafka.clients.admin.FenceProducersResult;
import org.apache.kafka.clients.admin.ListClientMetricsResourcesOptions;
import org.apache.kafka.clients.admin.ListClientMetricsResourcesResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.ListConsumerGroupsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupsResult;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListPartitionReassignmentsOptions;
import org.apache.kafka.clients.admin.ListPartitionReassignmentsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.ListTransactionsOptions;
import org.apache.kafka.clients.admin.ListTransactionsResult;
import org.apache.kafka.clients.admin.NewPartitionReassignment;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.admin.RemoveMembersFromConsumerGroupOptions;
import org.apache.kafka.clients.admin.RemoveMembersFromConsumerGroupResult;
import org.apache.kafka.clients.admin.RenewDelegationTokenOptions;
import org.apache.kafka.clients.admin.RenewDelegationTokenResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.admin.UnregisterBrokerOptions;
import org.apache.kafka.clients.admin.UnregisterBrokerResult;
import org.apache.kafka.clients.admin.UpdateFeaturesOptions;
import org.apache.kafka.clients.admin.UpdateFeaturesResult;
import org.apache.kafka.clients.admin.UserScramCredentialAlteration;
import org.apache.kafka.clients.admin.internals.CoordinatorKey;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.ElectionType;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicCollection;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionReplica;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.quota.ClientQuotaAlteration;
import org.apache.kafka.common.quota.ClientQuotaFilter;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 面向内存模拟 Broker 的离线 {@link AdminClient} 实现，无任何真实 Kafka 网络连接与阻塞 I/O
 *
 * @author Ateng
 * @since 2026-11-15
 */
public class OfflineKafkaAdminClient extends AdminClient {

    private static final Constructor<ListConsumerGroupOffsetsResult> LIST_OFFSETS_RESULT_CTOR = resolveListGroupOffsetsCtor();
    private static final Constructor<AlterConsumerGroupOffsetsResult> ALTER_OFFSETS_RESULT_CTOR = resolveAlterGroupOffsetsCtor();
    private static final Constructor<DescribeClusterResult> DESCRIBE_CLUSTER_RESULT_CTOR = resolveDescribeClusterCtor();

    private final OfflineKafkaBroker broker;

    public OfflineKafkaAdminClient(OfflineKafkaBroker broker) {
        this.broker = Objects.requireNonNull(broker, "broker 不能为空");
    }

    @Override
    public void close(Duration timeout) {
        // 离线客户端无底层套接字，关闭为空操作
    }

    @Override
    public CreateTopicsResult createTopics(Collection<NewTopic> newTopics, CreateTopicsOptions options) {
        broker.declareTopics(newTopics);
        return new CreateTopicsResult(Collections.emptyMap()) {
        };
    }

    @Override
    public DescribeTopicsResult describeTopics(TopicCollection topics, DescribeTopicsOptions options) {
        Collection<String> topicNames;
        if (topics instanceof TopicCollection.TopicNameCollection nameCollection) {
            topicNames = nameCollection.topicNames();
        } else {
            topicNames = Collections.emptyList();
        }
        Map<String, KafkaFuture<TopicDescription>> futures = broker.describeTopics(topicNames);
        return new DescribeTopicsResult(futures) {
        };
    }

    @Override
    public ListOffsetsResult listOffsets(Map<TopicPartition, OffsetSpec> topicPartitionOffsets, ListOffsetsOptions options) {
        return new ListOffsetsResult(broker.listOffsets(topicPartitionOffsets));
    }

    @Override
    public DeleteRecordsResult deleteRecords(
            Map<TopicPartition, RecordsToDelete> recordsToDelete,
            DeleteRecordsOptions options) {
        return new DeleteRecordsResult(broker.deleteRecords(recordsToDelete));
    }

    @Override
    public ListConsumerGroupOffsetsResult listConsumerGroupOffsets(
            Map<String, ListConsumerGroupOffsetsSpec> groupSpecs,
            ListConsumerGroupOffsetsOptions options) {
        Map<CoordinatorKey, KafkaFuture<Map<TopicPartition, OffsetAndMetadata>>> map = new LinkedHashMap<>();
        if (groupSpecs != null) {
            for (Map.Entry<String, ListConsumerGroupOffsetsSpec> entry : groupSpecs.entrySet()) {
                String groupId = entry.getKey();
                Map<TopicPartition, OffsetAndMetadata> committed = broker.getCommittedOffsets(groupId);
                ListConsumerGroupOffsetsSpec spec = entry.getValue();
                Map<TopicPartition, OffsetAndMetadata> filtered = committed;
                if (spec != null && spec.topicPartitions() != null && !spec.topicPartitions().isEmpty()) {
                    filtered = new LinkedHashMap<>();
                    for (TopicPartition tp : spec.topicPartitions()) {
                        if (committed.containsKey(tp)) {
                            filtered.put(tp, committed.get(tp));
                        }
                    }
                }
                KafkaFutureImpl<Map<TopicPartition, OffsetAndMetadata>> future = new KafkaFutureImpl<>();
                future.complete(filtered);
                map.put(CoordinatorKey.byGroupId(groupId), future);
            }
        }
        try {
            return LIST_OFFSETS_RESULT_CTOR.newInstance(map);
        } catch (Exception ex) {
            throw new IllegalStateException("构造 ListConsumerGroupOffsetsResult 失败", ex);
        }
    }

    @Override
    public AlterConsumerGroupOffsetsResult alterConsumerGroupOffsets(
            String groupId,
            Map<TopicPartition, OffsetAndMetadata> offsets,
            AlterConsumerGroupOffsetsOptions options) {
        broker.commitOffsets(groupId, offsets);
        Map<TopicPartition, Errors> errors = new LinkedHashMap<>();
        if (offsets != null) {
            for (TopicPartition tp : offsets.keySet()) {
                errors.put(tp, Errors.NONE);
            }
        }
        KafkaFutureImpl<Map<TopicPartition, Errors>> future = new KafkaFutureImpl<>();
        future.complete(errors);
        try {
            return ALTER_OFFSETS_RESULT_CTOR.newInstance(future);
        } catch (Exception ex) {
            throw new IllegalStateException("构造 AlterConsumerGroupOffsetsResult 失败", ex);
        }
    }

    @Override
    public Map<MetricName, ? extends Metric> metrics() {
        return Collections.emptyMap();
    }

    @Override
    public DeleteTopicsResult deleteTopics(TopicCollection topics, DeleteTopicsOptions options) {
        throw unsupported("deleteTopics");
    }

    @Override
    public ListTopicsResult listTopics(ListTopicsOptions options) {
        throw unsupported("listTopics");
    }

    @Override
    public DescribeClusterResult describeCluster(DescribeClusterOptions options) {
        KafkaFutureImpl<Collection<org.apache.kafka.common.Node>> nodesFuture = new KafkaFutureImpl<>();
        org.apache.kafka.common.Node node = broker.getClusterNode();
        nodesFuture.complete(Collections.singletonList(node));

        KafkaFutureImpl<org.apache.kafka.common.Node> controllerFuture = new KafkaFutureImpl<>();
        controllerFuture.complete(node);

        KafkaFutureImpl<String> clusterIdFuture = new KafkaFutureImpl<>();
        clusterIdFuture.complete(broker.getClusterId());

        KafkaFutureImpl<Set<org.apache.kafka.common.acl.AclOperation>> opsFuture = new KafkaFutureImpl<>();
        opsFuture.complete(Collections.emptySet());

        try {
            return DESCRIBE_CLUSTER_RESULT_CTOR.newInstance(nodesFuture, controllerFuture, clusterIdFuture, opsFuture);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("构建 DescribeClusterResult 失败", ex);
        }
    }

    @Override
    public DescribeAclsResult describeAcls(AclBindingFilter filter, DescribeAclsOptions options) {
        throw unsupported("describeAcls");
    }

    @Override
    public CreateAclsResult createAcls(Collection<AclBinding> acls, CreateAclsOptions options) {
        throw unsupported("createAcls");
    }

    @Override
    public DeleteAclsResult deleteAcls(Collection<AclBindingFilter> filters, DeleteAclsOptions options) {
        throw unsupported("deleteAcls");
    }

    @Override
    public DescribeConfigsResult describeConfigs(Collection<ConfigResource> resources, DescribeConfigsOptions options) {
        throw unsupported("describeConfigs");
    }

    @Override
    public AlterConfigsResult alterConfigs(Map<ConfigResource, Config> configs, AlterConfigsOptions options) {
        throw unsupported("alterConfigs");
    }

    @Override
    public AlterConfigsResult incrementalAlterConfigs(
            Map<ConfigResource, Collection<AlterConfigOp>> configs,
            AlterConfigsOptions options) {
        throw unsupported("incrementalAlterConfigs");
    }

    @Override
    public AlterReplicaLogDirsResult alterReplicaLogDirs(
            Map<TopicPartitionReplica, String> replicaAssignment,
            AlterReplicaLogDirsOptions options) {
        throw unsupported("alterReplicaLogDirs");
    }

    @Override
    public DescribeLogDirsResult describeLogDirs(Collection<Integer> brokers, DescribeLogDirsOptions options) {
        throw unsupported("describeLogDirs");
    }

    @Override
    public DescribeReplicaLogDirsResult describeReplicaLogDirs(
            Collection<TopicPartitionReplica> replicas,
            DescribeReplicaLogDirsOptions options) {
        throw unsupported("describeReplicaLogDirs");
    }

    @Override
    public CreatePartitionsResult createPartitions(Map<String, NewPartitions> newPartitions, CreatePartitionsOptions options) {
        throw unsupported("createPartitions");
    }

    @Override
    public CreateDelegationTokenResult createDelegationToken(CreateDelegationTokenOptions options) {
        throw unsupported("createDelegationToken");
    }

    @Override
    public RenewDelegationTokenResult renewDelegationToken(byte[] hmac, RenewDelegationTokenOptions options) {
        throw unsupported("renewDelegationToken");
    }

    @Override
    public ExpireDelegationTokenResult expireDelegationToken(byte[] hmac, ExpireDelegationTokenOptions options) {
        throw unsupported("expireDelegationToken");
    }

    @Override
    public DescribeDelegationTokenResult describeDelegationToken(DescribeDelegationTokenOptions options) {
        throw unsupported("describeDelegationToken");
    }

    @Override
    public DescribeConsumerGroupsResult describeConsumerGroups(
            Collection<String> groupIds,
            DescribeConsumerGroupsOptions options) {
        throw unsupported("describeConsumerGroups");
    }

    @Override
    public ListConsumerGroupsResult listConsumerGroups(ListConsumerGroupsOptions options) {
        throw unsupported("listConsumerGroups");
    }

    @Override
    public DeleteConsumerGroupsResult deleteConsumerGroups(
            Collection<String> groupIds,
            DeleteConsumerGroupsOptions options) {
        throw unsupported("deleteConsumerGroups");
    }

    @Override
    public DeleteConsumerGroupOffsetsResult deleteConsumerGroupOffsets(
            String groupId,
            Set<TopicPartition> partitions,
            DeleteConsumerGroupOffsetsOptions options) {
        throw unsupported("deleteConsumerGroupOffsets");
    }

    @Override
    public ElectLeadersResult electLeaders(
            ElectionType electionType,
            Set<TopicPartition> partitions,
            ElectLeadersOptions options) {
        throw unsupported("electLeaders");
    }

    @Override
    public AlterPartitionReassignmentsResult alterPartitionReassignments(
            Map<TopicPartition, Optional<NewPartitionReassignment>> reassignments,
            AlterPartitionReassignmentsOptions options) {
        throw unsupported("alterPartitionReassignments");
    }

    @Override
    public ListPartitionReassignmentsResult listPartitionReassignments(
            Optional<Set<TopicPartition>> partitions,
            ListPartitionReassignmentsOptions options) {
        throw unsupported("listPartitionReassignments");
    }

    @Override
    public RemoveMembersFromConsumerGroupResult removeMembersFromConsumerGroup(
            String groupId,
            RemoveMembersFromConsumerGroupOptions options) {
        throw unsupported("removeMembersFromConsumerGroup");
    }

    @Override
    public DescribeClientQuotasResult describeClientQuotas(ClientQuotaFilter filter, DescribeClientQuotasOptions options) {
        throw unsupported("describeClientQuotas");
    }

    @Override
    public AlterClientQuotasResult alterClientQuotas(
            Collection<ClientQuotaAlteration> entries,
            AlterClientQuotasOptions options) {
        throw unsupported("alterClientQuotas");
    }

    @Override
    public DescribeUserScramCredentialsResult describeUserScramCredentials(
            List<String> users,
            DescribeUserScramCredentialsOptions options) {
        throw unsupported("describeUserScramCredentials");
    }

    @Override
    public AlterUserScramCredentialsResult alterUserScramCredentials(
            List<UserScramCredentialAlteration> alterations,
            AlterUserScramCredentialsOptions options) {
        throw unsupported("alterUserScramCredentials");
    }

    @Override
    public DescribeFeaturesResult describeFeatures(DescribeFeaturesOptions options) {
        throw unsupported("describeFeatures");
    }

    @Override
    public UpdateFeaturesResult updateFeatures(Map<String, FeatureUpdate> featureUpdates, UpdateFeaturesOptions options) {
        throw unsupported("updateFeatures");
    }

    @Override
    public DescribeMetadataQuorumResult describeMetadataQuorum(DescribeMetadataQuorumOptions options) {
        throw unsupported("describeMetadataQuorum");
    }

    @Override
    public UnregisterBrokerResult unregisterBroker(int brokerId, UnregisterBrokerOptions options) {
        throw unsupported("unregisterBroker");
    }

    @Override
    public DescribeProducersResult describeProducers(
            Collection<TopicPartition> partitions,
            DescribeProducersOptions options) {
        throw unsupported("describeProducers");
    }

    @Override
    public DescribeTransactionsResult describeTransactions(
            Collection<String> transactionIds,
            DescribeTransactionsOptions options) {
        throw unsupported("describeTransactions");
    }

    @Override
    public AbortTransactionResult abortTransaction(AbortTransactionSpec spec, AbortTransactionOptions options) {
        throw unsupported("abortTransaction");
    }

    @Override
    public ListTransactionsResult listTransactions(ListTransactionsOptions options) {
        throw unsupported("listTransactions");
    }

    @Override
    public FenceProducersResult fenceProducers(Collection<String> transactionalIds, FenceProducersOptions options) {
        throw unsupported("fenceProducers");
    }

    @Override
    public ListClientMetricsResourcesResult listClientMetricsResources(ListClientMetricsResourcesOptions options) {
        throw unsupported("listClientMetricsResources");
    }

    @Override
    public Uuid clientInstanceId(Duration timeout) {
        throw unsupported("clientInstanceId");
    }

    private static UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException("离线 OfflineKafkaAdminClient 暂未模拟方法: " + method);
    }

    private static Constructor<ListConsumerGroupOffsetsResult> resolveListGroupOffsetsCtor() {
        try {
            Constructor<ListConsumerGroupOffsetsResult> ctor =
                    ListConsumerGroupOffsetsResult.class.getDeclaredConstructor(Map.class);
            ctor.setAccessible(true);
            return ctor;
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("无法解析 ListConsumerGroupOffsetsResult 构造器", ex);
        }
    }

    private static Constructor<AlterConsumerGroupOffsetsResult> resolveAlterGroupOffsetsCtor() {
        try {
            Constructor<AlterConsumerGroupOffsetsResult> ctor =
                    AlterConsumerGroupOffsetsResult.class.getDeclaredConstructor(KafkaFuture.class);
            ctor.setAccessible(true);
            return ctor;
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("无法解析 AlterConsumerGroupOffsetsResult 构造器", ex);
        }
    }

    private static Constructor<DescribeClusterResult> resolveDescribeClusterCtor() {
        try {
            Constructor<DescribeClusterResult> ctor = DescribeClusterResult.class.getDeclaredConstructor(
                    KafkaFuture.class, KafkaFuture.class, KafkaFuture.class, KafkaFuture.class);
            ctor.setAccessible(true);
            return ctor;
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("无法解析 DescribeClusterResult 构造器", ex);
        }
    }
}

package io.github.atengk.boot.milvus.client;

import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.milvus.grpc.MilvusServiceGrpc;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;

/**
 * 携带共享通道多库视图管理器引用的受管 {@link MilvusClientV2} 实例
 * 使自动装配主连接无需借助可变字段即可将多库隔离视图能力无损传递至模板层与管理器
 *
 * @author Ateng
 * @since 2026-11-20
 */
public class ManagedMilvusClientV2 extends MilvusClientV2 {

    private final MilvusDatabaseClientManager clientManager;

    public ManagedMilvusClientV2(ManagedChannel sharedChannel,
                                 Channel interceptedChannel,
                                 MilvusServiceGrpc.MilvusServiceBlockingStub blockingStub,
                                 ConnectConfig connectConfig,
                                 String defaultDatabase) {
        super(null);
        MilvusDatabaseClientManager.bindClientFields(this, sharedChannel, blockingStub, connectConfig);
        this.clientManager = MilvusDatabaseClientManager.managed(
                this, sharedChannel, interceptedChannel, connectConfig, defaultDatabase);
    }

    /**
     * 获取当前受管客户端关联的多库视图管理器
     *
     * @return 多库视图管理器
     */
    public MilvusDatabaseClientManager getClientManager() {
        return clientManager;
    }

    @Override
    public void close() {
        clientManager.destroy();
    }
}

package io.github.atengk.boot.milvus.client;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.milvus.context.MilvusContextHolder;
import io.github.atengk.boot.milvus.exception.MilvusExceptionTranslator;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.milvus.grpc.MilvusServiceGrpc;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 复用共享 {@link ManagedChannel} 的多逻辑数据库隔离客户端视图管理器
 * 为每个逻辑数据库维护独立 {@link MilvusClientV2} 视图以隔离 SDK 内部按集合名缓存的 Schema 元数据，同时保持零额外网络连接并支持受管停机释放
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Slf4j
public class MilvusDatabaseClientManager implements DisposableBean {

    private static final Field CHANNEL_FIELD;
    private static final Field STUB_FIELD;
    private static final Field CONFIG_FIELD;

    static {
        try {
            CHANNEL_FIELD = MilvusClientV2.class.getDeclaredField("channel");
            CHANNEL_FIELD.setAccessible(true);
            STUB_FIELD = MilvusClientV2.class.getDeclaredField("blockingStub");
            STUB_FIELD.setAccessible(true);
            CONFIG_FIELD = MilvusClientV2.class.getDeclaredField("connectConfig");
            CONFIG_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("无法定位 MilvusClientV2 核心字段，请检查 milvus-sdk-java 版本兼容性", e);
        }
    }

    private final MilvusClientV2 primaryClient;
    private final ManagedChannel sharedChannel;
    private final Channel interceptedChannel;
    private final ConnectConfig baseConfig;
    private final String defaultDatabase;
    private final ConcurrentMap<String, MilvusClientV2> databaseViewClients = new ConcurrentHashMap<>();

    private MilvusDatabaseClientManager(MilvusClientV2 primaryClient,
                                        ManagedChannel sharedChannel,
                                        Channel interceptedChannel,
                                        ConnectConfig baseConfig,
                                        String defaultDatabase) {
        this.primaryClient = primaryClient;
        this.sharedChannel = sharedChannel;
        this.interceptedChannel = interceptedChannel;
        this.baseConfig = baseConfig;
        this.defaultDatabase = defaultDatabase;
    }

    /**
     * 创建绑定现有共享通道的受管客户端视图管理器
     *
     * @param primaryClient      默认主客户端视图（需后续或同步绑定该管理器）
     * @param sharedChannel      底层共享物理连接通道
     * @param interceptedChannel 挂载动态切库与超时拦截器后的逻辑通道
     * @param baseConfig         基础连接配置
     * @param defaultDatabase    默认逻辑数据库名称
     * @return 受管客户端视图管理器
     */
    public static MilvusDatabaseClientManager managed(MilvusClientV2 primaryClient,
                                                      ManagedChannel sharedChannel,
                                                      Channel interceptedChannel,
                                                      ConnectConfig baseConfig,
                                                      String defaultDatabase) {
        Assert.notNull(primaryClient, "primaryClient must not be null");
        Assert.notNull(sharedChannel, "sharedChannel must not be null");
        Assert.notNull(interceptedChannel, "interceptedChannel must not be null");
        Assert.notNull(baseConfig, "baseConfig must not be null");
        String effectiveDefault = StringUtils.hasText(defaultDatabase) ? defaultDatabase.trim() : "default";
        return new MilvusDatabaseClientManager(primaryClient, sharedChannel, interceptedChannel, baseConfig, effectiveDefault);
    }

    /**
     * 创建非受管透传管理器（当业务注入自定义或测试替身 {@link MilvusClientV2} 时直接委托）
     *
     * @param externalClient 外部自定义或测试替身客户端
     * @return 非受管透传管理器
     */
    public static MilvusDatabaseClientManager unmanaged(MilvusClientV2 externalClient) {
        Assert.notNull(externalClient, "externalClient must not be null");
        return new MilvusDatabaseClientManager(externalClient, null, null, null, null);
    }

    /**
     * 统一解析当前线程生效的目标逻辑数据库名称
     * 优先级：显式 {@link MilvusContextHolder} 上下文 &gt; 多租户切库路由上下文 &gt; 全局/定制默认数据库
     *
     * @param defaultDatabase       默认数据库名称
     * @param tenantDatabaseRouting 是否开启多租户切库自动路由
     * @return 规范化（去除首尾空白）的目标数据库名称
     */
    public static String resolveRuntimeDatabase(String defaultDatabase, boolean tenantDatabaseRouting) {
        String explicitDb = MilvusContextHolder.getDatabase();
        if (StringUtils.hasText(explicitDb)) {
            return explicitDb.trim();
        }
        if (tenantDatabaseRouting && !TenantContextHolder.isIgnoreTenant()) {
            String tenantId = TenantContextHolder.getTenantId();
            if (StringUtils.hasText(tenantId)) {
                return tenantId.trim();
            }
        }
        return StringUtils.hasText(defaultDatabase) ? defaultDatabase.trim() : "default";
    }

    /**
     * 为阻塞存根按 RPC 超时阈值决定是否启用 wait-for-ready（仅当存在有限超时配置时启用，避免永久挂起）
     *
     * @param channel       逻辑通道
     * @param rpcDeadlineMs 单次 RPC 超时毫秒数
     * @return 配置完成后的阻塞存根
     */
    public static MilvusServiceGrpc.MilvusServiceBlockingStub newBlockingStub(Channel channel, long rpcDeadlineMs) {
        Assert.notNull(channel, "channel must not be null");
        MilvusServiceGrpc.MilvusServiceBlockingStub stub = MilvusServiceGrpc.newBlockingStub(channel);
        return rpcDeadlineMs > 0 ? stub.withWaitForReady() : stub;
    }

    /**
     * 获取当前受管客户端关联的默认逻辑数据库名称
     *
     * @return 默认逻辑数据库名称（非受管模式返回 null）
     */
    public String getDefaultDatabase() {
        return defaultDatabase;
    }

    /**
     * 判断传入客户端是否由当前共享通道管理器创建并接管
     *
     * @param candidate 待检测客户端实例
     * @return 是否受当前管理器接管
     */
    public boolean ownsClient(MilvusClientV2 candidate) {
        return sharedChannel != null && candidate != null && candidate == primaryClient;
    }

    /**
     * 按目标逻辑数据库解析对应的隔离客户端视图实例（共享同一物理连接通道）
     *
     * @param databaseName 目标逻辑数据库名称
     * @return 对应数据库隔离视图；非受管模式直接返回主客户端
     */
    public MilvusClientV2 resolveClient(String databaseName) {
        if (sharedChannel == null) {
            return primaryClient;
        }
        String targetDb = StringUtils.hasText(databaseName) ? databaseName.trim() : defaultDatabase;
        if (!StringUtils.hasText(targetDb) || targetDb.equals(defaultDatabase)) {
            return primaryClient;
        }
        return databaseViewClients.computeIfAbsent(targetDb, this::createDatabaseViewClient);
    }

    private MilvusClientV2 createDatabaseViewClient(String databaseName) {
        ConnectConfig viewConfig = copyConnectConfigWithDb(baseConfig, databaseName);
        MilvusServiceGrpc.MilvusServiceBlockingStub viewStub =
                newBlockingStub(interceptedChannel, viewConfig.getRpcDeadlineMs());
        return createBoundClient(sharedChannel, viewStub, viewConfig);
    }

    /**
     * 基于已有物理通道、拦截存根与连接配置创建无握手副作用的 {@link MilvusClientV2} 绑定实例
     *
     * @param channel       物理 gRPC 通道
     * @param blockingStub  阻塞存根
     * @param connectConfig 连接配置
     * @return 初始化完成字段绑定的客户端实例
     */
    public static MilvusClientV2 createBoundClient(ManagedChannel channel,
                                                   MilvusServiceGrpc.MilvusServiceBlockingStub blockingStub,
                                                   ConnectConfig connectConfig) {
        return bindClientFields(new MilvusClientV2(null), channel, blockingStub, connectConfig);
    }

    /**
     * 将共享物理通道、拦截存根与连接配置注入指定 {@link MilvusClientV2}（或其受管子类）实例
     *
     * @param target        目标客户端实例
     * @param channel       物理 gRPC 通道
     * @param blockingStub  阻塞存根
     * @param connectConfig 连接配置
     * @param <T>           客户端类型
     * @return 绑定完成后的客户端实例
     */
    public static <T extends MilvusClientV2> T bindClientFields(T target,
                                                                ManagedChannel channel,
                                                                MilvusServiceGrpc.MilvusServiceBlockingStub blockingStub,
                                                                ConnectConfig connectConfig) {
        Assert.notNull(target, "target client must not be null");
        Assert.notNull(channel, "channel must not be null");
        Assert.notNull(blockingStub, "blockingStub must not be null");
        Assert.notNull(connectConfig, "connectConfig must not be null");
        try {
            CHANNEL_FIELD.set(target, channel);
            STUB_FIELD.set(target, blockingStub);
            CONFIG_FIELD.set(target, connectConfig);
            return target;
        } catch (Exception ex) {
            throw MilvusExceptionTranslator.translate("绑定 MilvusClientV2 视图实例", ex);
        }
    }

    /**
     * 复制连接配置并覆盖逻辑数据库名称
     *
     * @param source 原始配置
     * @param dbName 目标数据库名称
     * @return 复制后的新配置对象
     */
    public static ConnectConfig copyConnectConfigWithDb(ConnectConfig source, String dbName) {
        Assert.notNull(source, "source ConnectConfig must not be null");
        return ConnectConfig.builder()
                .uri(source.getUri())
                .token(source.getToken())
                .username(source.getUsername())
                .password(source.getPassword())
                .dbName(dbName)
                .connectTimeoutMs(source.getConnectTimeoutMs())
                .keepAliveTimeMs(source.getKeepAliveTimeMs())
                .keepAliveTimeoutMs(source.getKeepAliveTimeoutMs())
                .keepAliveWithoutCalls(source.isKeepAliveWithoutCalls())
                .rpcDeadlineMs(source.getRpcDeadlineMs())
                .clientKeyPath(source.getClientKeyPath())
                .clientPemPath(source.getClientPemPath())
                .caPemPath(source.getCaPemPath())
                .serverPemPath(source.getServerPemPath())
                .serverName(source.getServerName())
                .secure(Boolean.TRUE.equals(source.getSecure()))
                .idleTimeoutMs(source.getIdleTimeoutMs())
                .sslContext(source.getSslContext())
                .build();
    }

    /**
     * 安全关闭 gRPC {@link ManagedChannel}，防止连接句柄与 Netty 线程泄漏
     *
     * @param channel 待关闭通道（可为 null）
     */
    public static void shutdownChannelQuietly(ManagedChannel channel) {
        if (channel == null || channel.isShutdown()) {
            return;
        }
        try {
            channel.shutdown();
            if (!channel.awaitTermination(3, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        } catch (Exception ex) {
            log.warn("关闭 Milvus ManagedChannel 异常: {}", ex.getMessage());
        }
    }

    @Override
    public void destroy() {
        databaseViewClients.clear();
        shutdownChannelQuietly(sharedChannel);
    }
}

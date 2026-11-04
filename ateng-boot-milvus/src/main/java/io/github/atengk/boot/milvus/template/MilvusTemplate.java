package io.github.atengk.boot.milvus.template;

import io.github.atengk.boot.milvus.exception.MilvusExceptionTranslator;
import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.milvus.v2.client.MilvusClientV2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;

import java.util.Objects;
import java.util.function.Function;

/**
 * Milvus 向量数据库核心操作门面 (三层立体分工与双核穿透)
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Slf4j
public class MilvusTemplate {

    private final MilvusClientV2 client;
    private final AtengMilvusProperties properties;

    public MilvusTemplate(MilvusClientV2 client, AtengMilvusProperties properties) {
        Assert.notNull(client, "MilvusClientV2 must not be null");
        Assert.notNull(properties, "AtengMilvusProperties must not be null");
        this.client = client;
        this.properties = properties;
    }

    /**
     * 获取底层原生 MilvusClientV2 客户端 (双核穿透逃逸舱)
     *
     * @return 官方原生 MilvusClientV2 实例
     */
    public MilvusClientV2 getClient() {
        return client;
    }

    /**
     * 获取当前生效的配置属性
     *
     * @return AtengMilvusProperties 配置对象
     */
    public AtengMilvusProperties getProperties() {
        return properties;
    }

    /**
     * 无损测试 Milvus 服务端连通性
     *
     * @return 是否成功连通
     */
    public boolean testConnection() {
        try {
            return client.clientIsReady();
        } catch (Exception e) {
            log.warn("Milvus 健康探针探测异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取 Milvus 服务端版本信息
     *
     * @return 服务端版本字符串 (如 "v2.4.4")
     */
    public String getServerVersion() {
        return execute("获取服务端版本", MilvusClientV2::getServerVersion);
    }

    /**
     * 原生客户端安全回调执行器，统一由 MilvusExceptionTranslator 提供因果异常保护
     *
     * @param operation 操作语义描述
     * @param action    执行动作
     * @param <T>       返回值泛型
     * @return 执行结果
     */
    public <T> T execute(String operation, Function<MilvusClientV2, T> action) {
        Objects.requireNonNull(action, "Action must not be null");
        try {
            return action.apply(client);
        } catch (Throwable t) {
            throw MilvusExceptionTranslator.translate(operation, t);
        }
    }
}

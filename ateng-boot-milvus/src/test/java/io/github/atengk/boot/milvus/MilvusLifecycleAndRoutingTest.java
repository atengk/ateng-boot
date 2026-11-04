package io.github.atengk.boot.milvus;

import com.google.gson.JsonObject;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.milvus.annotation.MilvusCollection;
import io.github.atengk.boot.milvus.annotation.MilvusField;
import io.github.atengk.boot.milvus.annotation.MilvusId;
import io.github.atengk.boot.milvus.annotation.MilvusVector;
import io.github.atengk.boot.milvus.context.MilvusContextHolder;
import io.github.atengk.boot.milvus.model.MilvusEntityConverter;
import io.github.atengk.boot.milvus.model.MilvusEntityMetadata;
import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AlterCollectionReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.index.request.ListIndexesReq;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 集合生命周期 (TTL & MMap 降本)、动态字段 $meta 双向映射与多租户切库自动路由全链路测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class MilvusLifecycleAndRoutingTest {

    private MilvusClientV2 client;
    private AtengMilvusProperties properties;
    private MilvusTemplate template;

    @BeforeEach
    void setUp() {
        client = mock(MilvusClientV2.class);
        properties = new AtengMilvusProperties();
        properties.setDatabase("default_db");
        template = new MilvusTemplate(client, properties);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        MilvusContextHolder.clearDatabase();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @MilvusCollection(name = "temp_chat_memory", ttlSeconds = 86400, mmap = true, replicaNumber = 3)
    public static class TempChatEntity {
        @MilvusId
        private Long id;

        @MilvusField
        private String message;

        @MilvusVector(dimension = 128)
        private List<Float> vector;

        private Map<String, Object> extra;
    }

    @Test
    @DisplayName("Metadata 解析 - 成功提取 TTL、MMap、replicaNumber 与 extraField")
    void testEntityMetadataParsing() {
        MilvusEntityMetadata metadata = MilvusEntityMetadata.of(TempChatEntity.class);

        assertThat(metadata.getTtlSeconds()).isEqualTo(86400L);
        assertThat(metadata.isMmap()).isTrue();
        assertThat(metadata.getReplicaNumber()).isEqualTo(3);
        assertThat(metadata.getExtraField()).isNotNull();
        assertThat(metadata.getExtraField().getField().getName()).isEqualTo("extra");
        assertThat(metadata.getExtraField().isExtra()).isTrue();

        // 验证 extra 不会作为物理静态 Schema 列被注册
        CreateCollectionReq req = metadata.toCreateCollectionReq();
        List<CreateCollectionReq.FieldSchema> fieldSchemaList = req.getCollectionSchema().getFieldSchemaList();
        assertThat(fieldSchemaList).noneMatch(f -> "extra".equals(f.getName()));
        assertThat(metadata.getFieldNames()).doesNotContain("extra");
    }

    @Test
    @DisplayName("createCollection - 自动向 Milvus 注入 collection.ttl.seconds 与 mmap.enabled 集合属性")
    void testCreateCollectionWithTtlAndMmap() {
        template.createCollection(TempChatEntity.class);

        // 验证调用了 createCollection
        verify(client).createCollection(any(CreateCollectionReq.class));

        // 验证调用了 alterCollection 注入属性
        ArgumentCaptor<AlterCollectionReq> captor = ArgumentCaptor.forClass(AlterCollectionReq.class);
        verify(client).alterCollection(captor.capture());

        AlterCollectionReq alterReq = captor.getValue();
        assertThat(alterReq.getCollectionName()).isEqualTo("temp_chat_memory");
        Map<String, String> props = alterReq.getProperties();
        assertThat(props).containsEntry("collection.ttl.seconds", "86400");
        assertThat(props).containsEntry("mmap.enabled", "true");
    }

    @Test
    @DisplayName("createCollectionAndIndex - 一键建表时按 replicaNumber 加载内存副本")
    void testCreateCollectionAndIndexWithReplicaNumber() {
        when(client.hasCollection(any())).thenReturn(false);
        when(client.listIndexes(any(ListIndexesReq.class))).thenReturn(Collections.emptyList());
        when(client.getLoadState(any())).thenReturn(false);

        template.createCollectionAndIndex(TempChatEntity.class);

        // 验证 alterCollection 设置了 TTL/MMap
        verify(client).alterCollection(any(AlterCollectionReq.class));

        // 验证 createIndex
        verify(client).createIndex(any(CreateIndexReq.class));

        // 验证 loadCollection 传递了 replicaNumber = 3
        ArgumentCaptor<LoadCollectionReq> loadCaptor = ArgumentCaptor.forClass(LoadCollectionReq.class);
        verify(client).loadCollection(loadCaptor.capture());

        LoadCollectionReq loadReq = loadCaptor.getValue();
        assertThat(loadReq.getCollectionName()).isEqualTo("temp_chat_memory");
        assertThat(loadReq.getNumReplicas()).isEqualTo(3);
    }

    @Test
    @DisplayName("MilvusEntityConverter - 写入时 extra 字典自动扁平化提取到根级别")
    void testDynamicFieldSerialization() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("clientIp", "127.0.0.1");
        extra.put("sessionTag", "vip_customer");
        extra.put("replyCount", 5);

        TempChatEntity entity = TempChatEntity.builder()
                .id(1001L)
                .message("Hello Milvus")
                .vector(List.of(0.1f, 0.2f))
                .extra(extra)
                .build();

        JsonObject jsonObject = MilvusEntityConverter.entityToJsonObject(entity);

        // extra 属性自身应该被移除，防止嵌套冲突
        assertThat(jsonObject.has("extra")).isFalse();

        // 动态列扁平化至根级别供 Milvus 动态字段 $meta 捕获
        assertThat(jsonObject.has("id")).isTrue();
        assertThat(jsonObject.get("id").getAsLong()).isEqualTo(1001L);
        assertThat(jsonObject.get("message").getAsString()).isEqualTo("Hello Milvus");
        assertThat(jsonObject.get("clientIp").getAsString()).isEqualTo("127.0.0.1");
        assertThat(jsonObject.get("sessionTag").getAsString()).isEqualTo("vip_customer");
        assertThat(jsonObject.get("replyCount").getAsInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("MilvusEntityConverter - 查询反序列化自动收集未知动态列回填至 extra 字典")
    void testDynamicFieldDeserialization() {
        Map<String, Object> rawData = new LinkedHashMap<>();
        rawData.put("id", 1001L);
        rawData.put("message", "Hello Milvus");
        rawData.put("vector", List.of(0.1f, 0.2f));
        // 未在实体中显式声明的动态列
        rawData.put("clientIp", "192.168.1.100");
        rawData.put("vipGrade", 8);
        rawData.put("channel", "IOS_APP");

        TempChatEntity entity = MilvusEntityConverter.mapToEntity(rawData, TempChatEntity.class);

        assertThat(entity).isNotNull();
        assertThat(entity.getId()).isEqualTo(1001L);
        assertThat(entity.getMessage()).isEqualTo("Hello Milvus");
        assertThat(entity.getVector()).containsExactly(0.1f, 0.2f);

        // 验证未知动态列被自动收集到 extra 字典中
        assertThat(entity.getExtra()).isNotNull();
        assertThat(entity.getExtra()).containsEntry("clientIp", "192.168.1.100");
        assertThat(entity.getExtra()).containsEntry("vipGrade", 8);
        assertThat(entity.getExtra()).containsEntry("channel", "IOS_APP");
    }

    @Test
    @DisplayName("MilvusEntityConverter - 从 Milvus 原生 $meta JsonObject 中解构未知动态列并回填")
    void testDynamicFieldDeserializationFromMeta() {
        JsonObject metaObj = new JsonObject();
        metaObj.addProperty("agentModel", "deepseek-v3");
        metaObj.addProperty("promptTokens", 350);

        Map<String, Object> rawData = new LinkedHashMap<>();
        rawData.put("id", 2002L);
        rawData.put("message", "AI Prompt");
        rawData.put("vector", List.of(0.3f, 0.4f));
        rawData.put("$meta", metaObj);

        TempChatEntity entity = MilvusEntityConverter.mapToEntity(rawData, TempChatEntity.class);

        assertThat(entity).isNotNull();
        assertThat(entity.getId()).isEqualTo(2002L);
        assertThat(entity.getExtra()).isNotNull();
        assertThat(entity.getExtra()).containsEntry("agentModel", "deepseek-v3");
        assertThat(entity.getExtra()).containsEntry("promptTokens", 350);
    }

    @Test
    @DisplayName("resolveCurrentDatabase - 默认回退全局配置数据库")
    void testDatabaseRoutingDefault() {
        properties.setTenantDatabaseRouting(false);
        assertThat(template.resolveCurrentDatabase()).isEqualTo("default_db");
    }

    @Test
    @DisplayName("resolveCurrentDatabase - 多租户切库路由开启且存在租户上下文时自动路由至租户专属数据库")
    void testDatabaseRoutingWithTenantContext() {
        properties.setTenantDatabaseRouting(true);
        TenantContextHolder.setTenantId("tenant_milvus_corp_a");

        assertThat(template.resolveCurrentDatabase()).isEqualTo("tenant_milvus_corp_a");
    }

    @Test
    @DisplayName("resolveCurrentDatabase - 忽略租户 (isIgnoreTenant) 时回退全局默认数据库")
    void testDatabaseRoutingIgnoreTenant() {
        properties.setTenantDatabaseRouting(true);
        TenantContextHolder.setTenantId("tenant_milvus_corp_a");
        TenantContextHolder.setIgnoreTenant(true);

        assertThat(template.resolveCurrentDatabase()).isEqualTo("default_db");
    }

    @Test
    @DisplayName("resolveCurrentDatabase - 显式 Milvus 上下文具备最高优先级")
    void testDatabaseRoutingExplicitContextOverride() {
        properties.setTenantDatabaseRouting(true);
        TenantContextHolder.setTenantId("tenant_milvus_corp_a");

        template.useDatabase("manual_override_db", () -> {
            assertThat(template.resolveCurrentDatabase()).isEqualTo("manual_override_db");
        });

        // 闭包退出后现场完全恢复
        assertThat(template.resolveCurrentDatabase()).isEqualTo("tenant_milvus_corp_a");
    }
}

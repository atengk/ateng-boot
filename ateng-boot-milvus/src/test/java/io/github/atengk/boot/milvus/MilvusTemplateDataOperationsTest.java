package io.github.atengk.boot.milvus;

import com.google.gson.JsonObject;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.milvus.annotation.MilvusCollection;
import io.github.atengk.boot.milvus.annotation.MilvusField;
import io.github.atengk.boot.milvus.annotation.MilvusId;
import io.github.atengk.boot.milvus.annotation.MilvusPartitionKey;
import io.github.atengk.boot.milvus.annotation.MilvusVector;
import io.github.atengk.boot.milvus.exception.MilvusException;
import io.github.atengk.boot.milvus.exception.MilvusResultCode;
import io.github.atengk.boot.milvus.model.MilvusHit;
import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.github.atengk.boot.milvus.properties.MilvusConsistencyLevel;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.collection.response.GetCollectionStatsResp;
import io.milvus.v2.service.partition.request.CreatePartitionReq;
import io.milvus.v2.service.partition.request.HasPartitionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.GetReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.GetResp;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import io.milvus.v2.service.vector.response.UpsertResp;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MilvusTemplate 数据操作门面 (DML/DQL/点查/切片/多租户) 单元测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class MilvusTemplateDataOperationsTest {

    private MilvusClientV2 client;
    private AtengMilvusProperties properties;
    private MilvusTemplate template;

    @BeforeEach
    void setUp() {
        client = mock(MilvusClientV2.class);
        properties = new AtengMilvusProperties();
        template = new MilvusTemplate(client, properties);
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @MilvusCollection(name = "kb_doc")
    static class KbDoc {
        @MilvusId
        private Long id;

        private String content;

        @MilvusPartitionKey(name = "tenant_id")
        private String tenantId;

        @MilvusVector(dimension = 3)
        private List<Float> vector;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @MilvusCollection(name = "partitioned_tenant_doc")
    static class PartitionedTenantDoc {
        @MilvusId
        private Long id;

        @MilvusPartitionKey(maxLength = 64)
        private String category;

        @MilvusField(name = "org_code", maxLength = 64, tenantField = true)
        private String orgCode;

        private String content;

        @MilvusVector(dimension = 3)
        private List<Float> vector;
    }

    @Test
    @DisplayName("insert & upsert - 字典与实体数据写入与租户自动织入")
    void testInsertAndUpsert() {
        TenantContextHolder.setTenantId("tenant_corp_a");

        when(client.insert(any(InsertReq.class))).thenReturn(InsertResp.builder().build());
        when(client.upsert(any(UpsertReq.class))).thenReturn(UpsertResp.builder().build());

        // 1. Map insert
        Map<String, Object> map = new HashMap<>();
        map.put("id", 1L);
        map.put("content", "文本A");
        map.put("vector", List.of(0.1f, 0.2f, 0.3f));
        template.insert("kb_doc", List.of(map));

        ArgumentCaptor<InsertReq> insertCaptor = ArgumentCaptor.forClass(InsertReq.class);
        verify(client).insert(insertCaptor.capture());
        InsertReq capturedInsert = insertCaptor.getValue();
        assertThat(capturedInsert.getCollectionName()).isEqualTo("kb_doc");
        assertThat(capturedInsert.getData()).hasSize(1);
        JsonObject json = capturedInsert.getData().get(0);
        assertThat(json.get("tenant_id").getAsString()).isEqualTo("tenant_corp_a");

        // 2. Entity upsert
        KbDoc doc = KbDoc.builder()
                .id(2L)
                .content("文本B")
                .vector(List.of(0.4f, 0.5f, 0.6f))
                .build();
        template.upsertEntities(List.of(doc));

        ArgumentCaptor<UpsertReq> upsertCaptor = ArgumentCaptor.forClass(UpsertReq.class);
        verify(client).upsert(upsertCaptor.capture());
        UpsertReq capturedUpsert = upsertCaptor.getValue();
        assertThat(capturedUpsert.getCollectionName()).isEqualTo("kb_doc");
        assertThat(doc.getTenantId()).isEqualTo("tenant_corp_a");
    }

    @Test
    @DisplayName("autoCreatePartition - 自动弹性创建不存在的时序物理分区")
    void testAutoCreatePartition() {
        properties.setAutoCreatePartition(true);

        when(client.hasPartition(any(HasPartitionReq.class))).thenReturn(false);
        when(client.insert(any(InsertReq.class))).thenReturn(InsertResp.builder().build());

        Map<String, Object> map = new HashMap<>();
        map.put("id", 10L);
        template.insert("kb_doc", List.of(map), "part_202611");

        // 验证自动触发了 createPartition
        ArgumentCaptor<CreatePartitionReq> partCaptor = ArgumentCaptor.forClass(CreatePartitionReq.class);
        verify(client).createPartition(partCaptor.capture());
        assertThat(partCaptor.getValue().getCollectionName()).isEqualTo("kb_doc");
        assertThat(partCaptor.getValue().getPartitionName()).isEqualTo("part_202611");
    }

    @Test
    @DisplayName("deleteByIds - 多租户模式下自动绑定租户过滤表达式防越权删除")
    void testDeleteByIdsWithTenant() {
        TenantContextHolder.setTenantId("tenant_isolated");

        when(client.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(DescribeCollectionResp.builder()
                        .primaryFieldName("id")
                        .build());
        when(client.delete(any(DeleteReq.class))).thenReturn(null);

        template.deleteByIds("kb_doc", List.of(101L, 102L));

        ArgumentCaptor<DeleteReq> deleteCaptor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(client).delete(deleteCaptor.capture());
        DeleteReq req = deleteCaptor.getValue();
        // 多租户模式下应该使用 filter 进行隔离防御
        assertThat(req.getFilter()).isEqualTo("(id in [101, 102]) and (tenant_id == 'tenant_isolated')");
    }

    @Test
    @DisplayName("deleteByIds - 多租户模式下主键解析失败时快速失败，杜绝裸ID越权删除")
    void testDeleteByIdsWithTenantFailFast() {
        TenantContextHolder.setTenantId("tenant_isolated");
        when(client.describeCollection(any(DescribeCollectionReq.class)))
                .thenThrow(new RuntimeException("获取元数据失败"));

        assertThatThrownBy(() -> template.deleteByIds("kb_doc", List.of(101L)))
                .isInstanceOf(MilvusException.class)
                .hasMessageContaining("多租户安全拦截")
                .satisfies(ex -> assertThat(((MilvusException) ex).getResultCode())
                        .isEqualTo(MilvusResultCode.TENANT_ID_REQUIRED));
        verify(client, never()).delete(any(DeleteReq.class));
    }

    @Test
    @DisplayName("deleteById - 单主键删除调用")
    void testDeleteById() {
        when(client.delete(any(DeleteReq.class))).thenReturn(null);
        template.deleteById("kb_doc", 101L);
        ArgumentCaptor<DeleteReq> captor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(client).delete(captor.capture());
        assertThat(captor.getValue().getIds()).containsExactly(101L);
    }

    @Test
    @DisplayName("deleteByIds - 非多租户模式直接按 ids 删除")
    void testDeleteByIdsWithoutTenant() {
        when(client.delete(any(DeleteReq.class))).thenReturn(null);

        template.deleteByIds("kb_doc", List.of(201L, 202L));

        ArgumentCaptor<DeleteReq> deleteCaptor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(client).delete(deleteCaptor.capture());
        DeleteReq req = deleteCaptor.getValue();
        assertThat(req.getIds()).containsExactly(201L, 202L);
    }

    @Test
    @DisplayName("deleteByExpr - 按条件删除并织入租户安全防护")
    void testDeleteByExpr() {
        TenantContextHolder.setTenantId("t_001");
        when(client.delete(any(DeleteReq.class))).thenReturn(null);

        template.deleteByExpr("kb_doc", "status == 0");

        ArgumentCaptor<DeleteReq> deleteCaptor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(client).delete(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().getFilter()).isEqualTo("(status == 0) and (tenant_id == 't_001')");
    }

    @Test
    @DisplayName("search - 向量相似度检索与强类型实体映射")
    void testSearch() {
        TenantContextHolder.setTenantId("tenant_rag");

        Map<String, Object> entityMap = new HashMap<>();
        entityMap.put("id", 1001L);
        entityMap.put("content", "RAG 检索增强生成");
        entityMap.put("tenant_id", "tenant_rag");

        SearchResp.SearchResult hitResult = SearchResp.SearchResult.builder()
                .id(1001L)
                .score(0.95f)
                .entity(entityMap)
                .build();
        SearchResp mockResp = SearchResp.builder()
                .searchResults(List.of(List.of(hitResult)))
                .build();
        when(client.search(any(SearchReq.class))).thenReturn(mockResp);

        List<Float> queryVec = List.of(0.1f, 0.2f, 0.3f);
        List<MilvusHit<KbDoc>> hits = template.search(KbDoc.class, queryVec, 5, "content like '%RAG%'", MilvusConsistencyLevel.STRONG);

        assertThat(hits).hasSize(1);
        MilvusHit<KbDoc> hit = hits.get(0);
        assertThat(hit.getId()).isEqualTo(1001L);
        assertThat(hit.getScore()).isEqualTo(0.95f);
        assertThat(hit.getEntity()).isNotNull();
        assertThat(hit.getEntity().getId()).isEqualTo(1001L);
        assertThat(hit.getEntity().getContent()).isEqualTo("RAG 检索增强生成");

        ArgumentCaptor<SearchReq> searchCaptor = ArgumentCaptor.forClass(SearchReq.class);
        verify(client).search(searchCaptor.capture());
        SearchReq req = searchCaptor.getValue();
        assertThat(req.getCollectionName()).isEqualTo("kb_doc");
        assertThat(req.getTopK()).isEqualTo(5);
        assertThat(req.getFilter()).isEqualTo("(content like '%RAG%') and (tenant_id == 'tenant_rag')");
        assertThat(req.getConsistencyLevel()).isEqualTo(io.milvus.v2.common.ConsistencyLevel.STRONG);
    }

    @Test
    @DisplayName("query - 标量条件查询与多租户织入")
    void testQuery() {
        TenantContextHolder.setTenantId("tenant_scalar");

        Map<String, Object> entityMap = new HashMap<>();
        entityMap.put("id", 2001L);
        entityMap.put("content", "标量查询");
        entityMap.put("tenant_id", "tenant_scalar");

        QueryResp.QueryResult qResult = QueryResp.QueryResult.builder()
                .entity(entityMap)
                .build();
        QueryResp queryResp = QueryResp.builder()
                .queryResults(List.of(qResult))
                .build();
        when(client.query(any(QueryReq.class))).thenReturn(queryResp);

        List<KbDoc> docs = template.query(KbDoc.class, "id > 2000");
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).getId()).isEqualTo(2001L);

        ArgumentCaptor<QueryReq> queryCaptor = ArgumentCaptor.forClass(QueryReq.class);
        verify(client).query(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getFilter()).isEqualTo("(id > 2000) and (tenant_id == 'tenant_scalar')");
    }

    @Test
    @DisplayName("getById - 正常获取与跨租户越权拦截防御")
    void testGetByIdDefense() {
        TenantContextHolder.setTenantId("my_tenant");

        // 1. 本租户数据正常获取
        Map<String, Object> myEntity = new HashMap<>();
        myEntity.put("id", 3001L);
        myEntity.put("tenant_id", "my_tenant");
        myEntity.put("content", "租户数据");

        when(client.get(any(GetReq.class))).thenReturn(GetResp.builder()
                .getResults(List.of(QueryResp.QueryResult.builder().entity(myEntity).build()))
                .build());

        KbDoc myDoc = template.getById(KbDoc.class, 3001L);
        assertThat(myDoc).isNotNull();
        assertThat(myDoc.getId()).isEqualTo(3001L);

        // 2. 尝试获取其他租户数据，应被安全拦截返回 null
        Map<String, Object> otherEntity = new HashMap<>();
        otherEntity.put("id", 3002L);
        otherEntity.put("tenant_id", "other_tenant"); // 越权数据
        otherEntity.put("content", "机密数据");

        when(client.get(any(GetReq.class))).thenReturn(GetResp.builder()
                .getResults(List.of(QueryResp.QueryResult.builder().entity(otherEntity).build()))
                .build());

        KbDoc interceptedDoc = template.getById(KbDoc.class, 3002L);
        assertThat(interceptedDoc).isNull();

        // 3. 显式指定出参字段不含 tenant_id 时，点查仍强制拉取租户字段校验，跨租户严格拦截为 null
        Map<String, Object> noTenantEntity = new HashMap<>();
        noTenantEntity.put("id", 3003L);
        noTenantEntity.put("content", "未授权租户数据");
        noTenantEntity.put("tenant_id", "hacker_tenant");

        when(client.get(any(GetReq.class))).thenReturn(GetResp.builder()
                .getResults(List.of(QueryResp.QueryResult.builder().entity(noTenantEntity).build()))
                .build());

        Map<String, Object> interceptedMap = template.getById("kb_doc", 3003L, List.of("content"));
        assertThat(interceptedMap).isNull();
    }

    @Test
    @DisplayName("count & existsById - 统计与存在性探测")
    void testCountAndExists() {
        // existsById
        Map<String, Object> existMap = new HashMap<>();
        existMap.put("id", 4001L);
        when(client.get(any(GetReq.class))).thenReturn(GetResp.builder()
                .getResults(List.of(QueryResp.QueryResult.builder().entity(existMap).build()))
                .build());
        assertThat(template.existsById(KbDoc.class, 4001L)).isTrue();

        // count without filter: 读取 getCollectionStats
        when(client.getCollectionStats(any())).thenReturn(GetCollectionStatsResp.builder()
                .numOfEntities(42L)
                .build());
        assertThat(template.count(KbDoc.class)).isEqualTo(42L);

        // count with filter: query count(*)
        Map<String, Object> countMap = new HashMap<>();
        countMap.put("count(*)", 15L);
        when(client.query(any(QueryReq.class))).thenReturn(QueryResp.builder()
                .queryResults(List.of(QueryResp.QueryResult.builder().entity(countMap).build()))
                .build());
        assertThat(template.count(KbDoc.class, "status == 1")).isEqualTo(15L);
    }

    @Test
    @DisplayName("insertBatch - 海量数据透明切片安全摄取")
    void testInsertBatch() {
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", (long) i);
            data.add(row);
        }

        when(client.insert(any(InsertReq.class))).thenReturn(InsertResp.builder().build());

        // 指定切片大小为 10，25 条数据应切分为 3 批调用 insert
        template.insertBatch("kb_doc", data, 10);

        verify(client, times(3)).insert(any(InsertReq.class));
    }

    @Test
    @DisplayName("正交解耦 - 实体兼有业务 @MilvusPartitionKey 与独立租户列时全链路按租户列隔离且遵循全局默认列名")
    void testOrthogonalPartitionKeyAndTenantFieldAcrossDmlAndDql() {
        TenantContextHolder.setTenantId("org_alpha");
        when(client.insert(any(InsertReq.class))).thenReturn(InsertResp.builder().build());
        when(client.upsert(any(UpsertReq.class))).thenReturn(UpsertResp.builder().build());
        when(client.delete(any(DeleteReq.class))).thenReturn(null);
        when(client.search(any(SearchReq.class))).thenReturn(SearchResp.builder()
                .searchResults(List.of(List.of()))
                .build());
        when(client.query(any(QueryReq.class))).thenReturn(QueryResp.builder()
                .queryResults(List.of())
                .build());

        // 1. insertEntities / upsertEntities 自动填充 org_code 而非 category
        PartitionedTenantDoc doc = PartitionedTenantDoc.builder()
                .id(1L)
                .category("TECH")
                .content("多租户正交隔离")
                .vector(List.of(0.1f, 0.2f, 0.3f))
                .build();
        template.insertEntities(List.of(doc));
        assertThat(doc.getOrgCode()).isEqualTo("org_alpha");
        assertThat(doc.getCategory()).isEqualTo("TECH");

        doc.setOrgCode(null);
        template.upsertEntities(List.of(doc));
        assertThat(doc.getOrgCode()).isEqualTo("org_alpha");

        // 2. deleteEntities 按实体 org_code 过滤
        template.deleteEntities(PartitionedTenantDoc.class, List.of(1L));
        ArgumentCaptor<DeleteReq> deleteCaptor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(client).delete(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().getFilter()).isEqualTo("(id in [1]) and (org_code == 'org_alpha')");

        // 3. search / query / count 按实体 org_code 过滤（绝不织入 category）
        template.search(PartitionedTenantDoc.class, List.of(0.1f, 0.2f, 0.3f), 5, "category == 'TECH'");
        ArgumentCaptor<SearchReq> searchCaptor = ArgumentCaptor.forClass(SearchReq.class);
        verify(client).search(searchCaptor.capture());
        assertThat(searchCaptor.getValue().getFilter()).isEqualTo("(category == 'TECH') and (org_code == 'org_alpha')");

        template.query(PartitionedTenantDoc.class, "category == 'TECH'");
        template.count(PartitionedTenantDoc.class, "category == 'TECH'");
        ArgumentCaptor<QueryReq> queryCaptor = ArgumentCaptor.forClass(QueryReq.class);
        verify(client, times(2)).query(queryCaptor.capture());
        assertThat(queryCaptor.getAllValues().get(0).getFilter()).isEqualTo("(category == 'TECH') and (org_code == 'org_alpha')");
        assertThat(queryCaptor.getAllValues().get(1).getFilter()).isEqualTo("(category == 'TECH') and (org_code == 'org_alpha')");

        // 4. getById / getByIds 使用 org_code 进行跨租户拦截（category 相同但 org_code 不同也必须拦截）
        Map<String, Object> crossTenantRow = new HashMap<>();
        crossTenantRow.put("id", 99L);
        crossTenantRow.put("category", "org_alpha");
        crossTenantRow.put("org_code", "org_beta");
        when(client.get(any(GetReq.class))).thenReturn(GetResp.builder()
                .getResults(List.of(QueryResp.QueryResult.builder().entity(crossTenantRow).build()))
                .build());
        assertThat(template.getById(PartitionedTenantDoc.class, 99L)).isNull();
        assertThat(template.getByIds(PartitionedTenantDoc.class, List.of(99L))).isEmpty();
        assertThat(template.existsById(PartitionedTenantDoc.class, 99L)).isFalse();

        Map<String, Object> ownTenantRow = new HashMap<>();
        ownTenantRow.put("id", 98L);
        ownTenantRow.put("category", "OTHER");
        ownTenantRow.put("org_code", "org_alpha");
        when(client.get(any(GetReq.class))).thenReturn(GetResp.builder()
                .getResults(List.of(QueryResp.QueryResult.builder().entity(ownTenantRow).build()))
                .build());
        assertThat(template.existsById(PartitionedTenantDoc.class, 98L)).isTrue();

        // 5. 无实体类操作遵循全局默认配置 ateng.boot.milvus.tenant-field-name
        properties.setTenantFieldName("custom_tenant_col");
        Map<String, Object> rawRow = new HashMap<>();
        rawRow.put("id", 2L);
        template.insert("raw_col", List.of(rawRow));
        assertThat(rawRow).containsEntry("custom_tenant_col", "org_alpha");
        template.deleteByExpr("raw_col", "status == 1");
        ArgumentCaptor<DeleteReq> rawDeleteCaptor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(client, times(2)).delete(rawDeleteCaptor.capture());
        assertThat(rawDeleteCaptor.getAllValues().get(1).getFilter())
                .isEqualTo("(status == 1) and (custom_tenant_col == 'org_alpha')");
    }

    @Test
    @DisplayName("batchConfig - 配置化 batchSize 与显式传参覆盖优先级验证")
    void testConfiguredBatchSizeAndExplicitOverride() {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<KbDoc> docs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", (long) i);
            rows.add(row);
            docs.add(KbDoc.builder().id((long) i).content("c" + i).vector(List.of(0.1f, 0.2f, 0.3f)).build());
        }

        when(client.insert(any(InsertReq.class))).thenReturn(InsertResp.builder().build());
        when(client.upsert(any(UpsertReq.class))).thenReturn(UpsertResp.builder().build());

        properties.setBatchSize(4);

        // 1. 缺省批大小重载遵循 properties.batchSize=4 (10 条 -> 4+4+2 = 3 批)
        template.insertBatch("kb_doc", rows);
        verify(client, times(3)).insert(any(InsertReq.class));

        template.upsertBatch("kb_doc", rows);
        verify(client, times(3)).upsert(any(UpsertReq.class));

        template.upsertBatchEntities(docs);
        verify(client, times(6)).upsert(any(UpsertReq.class));

        // 2. 显式传入 batchSize=3 优先覆盖配置 (10 条 -> 3+3+3+1 = 4 批)
        template.insertBatchEntities(docs, 3);
        verify(client, times(7)).insert(any(InsertReq.class));
    }

    @Test
    @DisplayName("maxRetries & retryDelay - 配置化指数退避重试次数与延迟接线验证")
    void testConfiguredBatchRetryBehavior() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        List<Map<String, Object>> singleRow = List.of(row);

        properties.setBatchSize(10);
        properties.setRetryDelay(Duration.ofMillis(40));

        // 1. maxRetries=2 (首次 + 2 次重试 = 共 3 次)，第 3 次成功，且退避延迟生效 (40ms + 80ms = 120ms)
        properties.setMaxRetries(2);
        when(client.insert(any(InsertReq.class)))
                .thenThrow(new RuntimeException("transient 1"))
                .thenThrow(new RuntimeException("transient 2"))
                .thenReturn(InsertResp.builder().build());

        long startMs = System.currentTimeMillis();
        template.insertBatch("kb_doc", singleRow);
        long elapsedMs = System.currentTimeMillis() - startMs;
        verify(client, times(3)).insert(any(InsertReq.class));
        assertThat(elapsedMs).isGreaterThanOrEqualTo(90L);

        // 2. maxRetries=1 (首次 + 1 次重试 = 共 2 次)，持续失败后快速终止
        MilvusClientV2 failClient = mock(MilvusClientV2.class);
        AtengMilvusProperties failProps = new AtengMilvusProperties();
        failProps.setMaxRetries(1);
        failProps.setRetryDelay(Duration.ofMillis(5));
        MilvusTemplate failTemplate = new MilvusTemplate(failClient, failProps);

        when(failClient.upsert(any(UpsertReq.class))).thenThrow(new RuntimeException("persistent error"));
        assertThatThrownBy(() -> failTemplate.upsertBatch("kb_doc", singleRow))
                .isInstanceOf(MilvusException.class);
        verify(failClient, times(2)).upsert(any(UpsertReq.class));
    }
}

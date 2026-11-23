package io.github.atengk.boot.sample.milvus;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.util.EnumUtil;
import io.github.atengk.boot.milvus.client.MilvusDatabaseClientManager;
import io.github.atengk.boot.milvus.context.MilvusContextHolder;
import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.github.atengk.boot.milvus.properties.MilvusConsistencyLevel;
import io.github.atengk.boot.sample.milvus.dto.AliasAlterDto;
import io.github.atengk.boot.sample.milvus.dto.AliasCreateDto;
import io.github.atengk.boot.sample.milvus.dto.DatabaseCreateDto;
import io.github.atengk.boot.sample.milvus.dto.DatabaseScopedSearchDto;
import io.github.atengk.boot.sample.milvus.dto.DocumentBatchInsertDto;
import io.github.atengk.boot.sample.milvus.dto.DocumentInsertDto;
import io.github.atengk.boot.sample.milvus.dto.ExprSearchDto;
import io.github.atengk.boot.sample.milvus.dto.GroupBySearchDto;
import io.github.atengk.boot.sample.milvus.dto.HybridSearchDto;
import io.github.atengk.boot.sample.milvus.dto.MultimodalDenseSearchDto;
import io.github.atengk.boot.sample.milvus.dto.MultimodalHybridSearchDto;
import io.github.atengk.boot.sample.milvus.dto.MultimodalInsertDto;
import io.github.atengk.boot.sample.milvus.dto.MultimodalSparseSearchDto;
import io.github.atengk.boot.sample.milvus.dto.PartitionArchiveInsertDto;
import io.github.atengk.boot.sample.milvus.dto.PartitionBatchOpDto;
import io.github.atengk.boot.sample.milvus.dto.PartitionOpDto;
import io.github.atengk.boot.sample.milvus.dto.RangeSearchDto;
import io.github.atengk.boot.sample.milvus.dto.StreamQueryDto;
import io.github.atengk.boot.sample.milvus.dto.VectorSearchDto;
import io.milvus.grpc.ErrorCode;
import io.milvus.grpc.FlushRequest;
import io.milvus.grpc.FlushResponse;
import io.milvus.grpc.MilvusServiceGrpc;
import io.milvus.orm.iterator.QueryIterator;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.GetLoadStateReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.collection.response.GetCollectionStatsResp;
import io.milvus.v2.service.collection.response.ListCollectionsResp;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import io.milvus.v2.service.database.request.DropDatabaseReq;
import io.milvus.v2.service.database.response.ListDatabasesResp;
import io.milvus.v2.service.partition.request.CreatePartitionReq;
import io.milvus.v2.service.partition.request.DropPartitionReq;
import io.milvus.v2.service.partition.request.HasPartitionReq;
import io.milvus.v2.service.partition.request.ListPartitionsReq;
import io.milvus.v2.service.partition.request.LoadPartitionsReq;
import io.milvus.v2.service.partition.request.ReleasePartitionsReq;
import io.milvus.v2.service.utility.response.DescribeAliasResp;
import io.milvus.v2.service.utility.response.ListAliasResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.GetReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryIteratorReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.GetResp;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import io.milvus.v2.service.vector.response.UpsertResp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 样例工程 Milvus 向量检索与多向量混合查询全场景端到端实测 (双模自适应驱动)
 *
 * @author Ateng
 * @since 2026-11-04
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SampleMilvusIntegrationTest.TestMilvusConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Slf4j
@DisplayName("样例工程 Milvus 向量检索与多向量混合查询全场景端到端实测")
class SampleMilvusIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MilvusClientV2 milvusClientV2;

    @Autowired
    private AtengMilvusProperties milvusProperties;

    @BeforeEach
    void setUp() {
        TenantContextHolder.clear();
        MilvusContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        MilvusContextHolder.clear();
    }

    /**
     * CI 默认/离线环境纯内存 Mock 测试桩 (仅在非 local profile 下生效)
     */
    @TestConfiguration
    @Profile("!local")
    static class TestMilvusConfig {

        @Bean
        @Primary
        public MilvusClientV2 mockMilvusClientV2() {
            MilvusClientV2 client = Mockito.mock(MilvusClientV2.class);

            // 1. 健康探针与版本
            when(client.clientIsReady()).thenReturn(true);
            when(client.getServerVersion()).thenReturn("v2.4.4");

            // 2. 集合与物理分区生命周期及结构
            when(client.hasCollection(any())).thenReturn(false);

            Map<String, Set<String>> partitionsByCol = new ConcurrentHashMap<>();
            Map<String, Set<String>> loadedPartitionsByCol = new ConcurrentHashMap<>();

            when(client.getLoadState(any(GetLoadStateReq.class))).thenAnswer(inv -> {
                GetLoadStateReq req = inv.getArgument(0);
                if (req.getPartitionName() != null) {
                    Set<String> loaded = loadedPartitionsByCol.get(req.getCollectionName());
                    return loaded != null && loaded.contains(req.getPartitionName());
                }
                return true;
            });

            when(client.hasPartition(any(HasPartitionReq.class))).thenAnswer(inv -> {
                HasPartitionReq req = inv.getArgument(0);
                Set<String> parts = partitionsByCol.get(req.getCollectionName());
                return parts != null && parts.contains(req.getPartitionName());
            });
            Mockito.doAnswer(inv -> {
                CreatePartitionReq req = inv.getArgument(0);
                partitionsByCol.computeIfAbsent(req.getCollectionName(), k -> ConcurrentHashMap.newKeySet())
                        .add(req.getPartitionName());
                return null;
            }).when(client).createPartition(any(CreatePartitionReq.class));
            Mockito.doAnswer(inv -> {
                DropPartitionReq req = inv.getArgument(0);
                Set<String> parts = partitionsByCol.get(req.getCollectionName());
                if (parts != null) {
                    parts.remove(req.getPartitionName());
                }
                Set<String> loaded = loadedPartitionsByCol.get(req.getCollectionName());
                if (loaded != null) {
                    loaded.remove(req.getPartitionName());
                }
                return null;
            }).when(client).dropPartition(any(DropPartitionReq.class));
            when(client.listPartitions(any(ListPartitionsReq.class))).thenAnswer(inv -> {
                ListPartitionsReq req = inv.getArgument(0);
                Set<String> parts = partitionsByCol.computeIfAbsent(req.getCollectionName(), k -> {
                    Set<String> set = ConcurrentHashMap.newKeySet();
                    set.add("_default");
                    return set;
                });
                return new ArrayList<>(parts);
            });
            Mockito.doAnswer(inv -> {
                LoadPartitionsReq req = inv.getArgument(0);
                if (req.getPartitionNames() != null) {
                    loadedPartitionsByCol.computeIfAbsent(req.getCollectionName(), k -> ConcurrentHashMap.newKeySet())
                            .addAll(req.getPartitionNames());
                }
                return null;
            }).when(client).loadPartitions(any(LoadPartitionsReq.class));
            Mockito.doAnswer(inv -> {
                ReleasePartitionsReq req = inv.getArgument(0);
                Set<String> loaded = loadedPartitionsByCol.get(req.getCollectionName());
                if (loaded != null && req.getPartitionNames() != null) {
                    loaded.removeAll(req.getPartitionNames());
                }
                return null;
            }).when(client).releasePartitions(any(ReleasePartitionsReq.class));

            // 绑定 gRPC Flush 阻塞存根
            MilvusServiceGrpc.MilvusServiceBlockingStub blockingStub =
                    Mockito.mock(MilvusServiceGrpc.MilvusServiceBlockingStub.class);
            when(blockingStub.flush(any(FlushRequest.class))).thenReturn(
                    FlushResponse.newBuilder()
                            .setStatus(io.milvus.grpc.Status.newBuilder()
                                    .setCode(0)
                                    .setErrorCode(ErrorCode.Success)
                                    .build())
                            .build()
            );
            MilvusDatabaseClientManager.bindBlockingStub(client, blockingStub);

            ListCollectionsResp listResp = ListCollectionsResp.builder()
                    .collectionNames(Collections.singletonList("sample_document"))
                    .build();
            when(client.listCollections()).thenReturn(listResp);

            DescribeCollectionResp descResp = DescribeCollectionResp.builder()
                    .collectionName("sample_document")
                    .description("知识库文档向量集合")
                    .primaryFieldName("id")
                    .build();
            when(client.describeCollection(any())).thenReturn(descResp);

            GetCollectionStatsResp statsResp = GetCollectionStatsResp.builder()
                    .numOfEntities(1L)
                    .build();
            when(client.getCollectionStats(any())).thenReturn(statsResp);

            // 3. 写入与覆盖
            when(client.insert(any(InsertReq.class))).thenReturn(InsertResp.builder().build());
            when(client.upsert(any(UpsertReq.class))).thenReturn(UpsertResp.builder().build());

            // 4. 单文档点查与标量查询
            Map<String, Object> entityMap = new HashMap<>();
            entityMap.put("id", 1L);
            entityMap.put("category", "TECH");
            entityMap.put("tenant_id", "tenant_a");
            entityMap.put("title", "Spring Boot 向量检索技术");
            entityMap.put("content", "Milvus 基础设施全场景实战");
            entityMap.put("vector", List.of(0.1f, 0.2f, 0.3f, 0.4f));
            entityMap.put("count(*)", 1L);

            Map<String, Object> multimodalMap = new HashMap<>();
            multimodalMap.put("id", 1001L);
            multimodalMap.put("title", "多模态检索技术白皮书");
            multimodalMap.put("summary", "结合稠密语义与稀疏关键字的高精度检索架构");
            multimodalMap.put("dense_vector", List.of(0.1f, 0.2f, 0.3f, 0.4f));
            multimodalMap.put("sparse_vector", Map.of(101L, 0.85f, 202L, 0.45f));
            multimodalMap.put("extra", Map.of("source", "crawler", "score", 99));

            QueryResp.QueryResult queryResult = QueryResp.QueryResult.builder()
                    .entity(entityMap)
                    .build();

            Map<String, Object> tenantAlphaMap = new HashMap<>();
            tenantAlphaMap.put("id", 100L);
            tenantAlphaMap.put("category", "SAAS");
            tenantAlphaMap.put("tenant_id", "tenant_corp_alpha");
            tenantAlphaMap.put("title", "租户隔离专有文档");
            tenantAlphaMap.put("content", "严格保障租户数据安全");
            tenantAlphaMap.put("vector", List.of(0.1f, 0.1f, 0.1f, 0.1f));

            when(client.get(any(GetReq.class))).thenAnswer(invocation -> {
                GetReq req = invocation.getArgument(0);
                if (req.getIds() != null && req.getIds().contains(999999L)) {
                    return GetResp.builder().getResults(Collections.emptyList()).build();
                }
                Map<String, Object> targetMap;
                if ("sample_multimodal".equals(req.getCollectionName())) {
                    targetMap = multimodalMap;
                } else if (req.getIds() != null && req.getIds().contains(100L)) {
                    targetMap = tenantAlphaMap;
                } else {
                    targetMap = entityMap;
                }
                QueryResp.QueryResult r = QueryResp.QueryResult.builder().entity(targetMap).build();
                return GetResp.builder().getResults(Collections.singletonList(r)).build();
            });

            QueryResp queryResp = QueryResp.builder()
                    .queryResults(Collections.singletonList(queryResult))
                    .build();
            when(client.query(any(QueryReq.class))).thenReturn(queryResp);

            when(client.queryIterator(any(QueryIteratorReq.class))).thenAnswer(inv -> {
                QueryIterator iterator = Mockito.mock(QueryIterator.class);
                QueryResultsWrapper.RowRecord rowRecord = Mockito.mock(QueryResultsWrapper.RowRecord.class);
                when(rowRecord.getFieldValues()).thenReturn(new HashMap<>(entityMap));
                when(iterator.next())
                        .thenReturn(Collections.singletonList(rowRecord))
                        .thenReturn(Collections.emptyList());
                return iterator;
            });

            // 5. 向量检索与混合检索
            when(client.search(any(SearchReq.class))).thenAnswer(invocation -> {
                SearchReq req = invocation.getArgument(0);
                Map<String, Object> targetMap = "sample_multimodal".equals(req.getCollectionName()) ? multimodalMap : entityMap;
                SearchResp.SearchResult hitResult = SearchResp.SearchResult.builder()
                        .id(targetMap.get("id"))
                        .score(0.96f)
                        .entity(targetMap)
                        .build();
                return SearchResp.builder()
                        .searchResults(Collections.singletonList(Collections.singletonList(hitResult)))
                        .build();
            });

            when(client.hybridSearch(any(HybridSearchReq.class))).thenAnswer(invocation -> {
                HybridSearchReq req = invocation.getArgument(0);
                Map<String, Object> targetMap = "sample_multimodal".equals(req.getCollectionName()) ? multimodalMap : entityMap;
                SearchResp.SearchResult hitResult = SearchResp.SearchResult.builder()
                        .id(targetMap.get("id"))
                        .score(0.96f)
                        .entity(targetMap)
                        .build();
                return SearchResp.builder()
                        .searchResults(Collections.singletonList(Collections.singletonList(hitResult)))
                        .build();
            });

            // 6. 集合别名运维
            ListAliasResp aliasResp = ListAliasResp.builder()
                    .collectionName("sample_document")
                    .alias(List.of("doc_alias_prod", "doc_alias_preview"))
                    .build();
            when(client.listAliases(any())).thenReturn(aliasResp);

            DescribeAliasResp descAliasResp = DescribeAliasResp.builder()
                    .alias("doc_alias_prod")
                    .collectionName("sample_document")
                    .build();
            when(client.describeAlias(any())).thenReturn(descAliasResp);

            // 7. 逻辑数据库生命周期运维
            Set<String> databases = ConcurrentHashMap.newKeySet();
            databases.add("default");
            when(client.listDatabases()).thenAnswer(inv -> ListDatabasesResp.builder()
                    .databaseNames(new ArrayList<>(databases))
                    .build());
            Mockito.doAnswer(inv -> {
                CreateDatabaseReq req = inv.getArgument(0);
                databases.add(req.getDatabaseName());
                return null;
            }).when(client).createDatabase(any(CreateDatabaseReq.class));
            Mockito.doAnswer(inv -> {
                DropDatabaseReq req = inv.getArgument(0);
                databases.remove(req.getDatabaseName());
                return null;
            }).when(client).dropDatabase(any(DropDatabaseReq.class));

            return client;
        }
    }

    @Test
    @Order(1)
    @DisplayName("1. 测试集合生命周期与健康运维初始化 (MilvusLifecycleController)")
    void testLifecycleHealthAndInit() throws Exception {
        // 1. 健康探测
        mockMvc.perform(get("/sample/milvus/lifecycle/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.serverVersion").isNotEmpty());

        // 2. 集合初始化
        mockMvc.perform(post("/sample/milvus/lifecycle/collection/init"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNotEmpty());

        // 3. 列出集合并验证包含 sample_document
        mockMvc.perform(get("/sample/milvus/lifecycle/collection/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(hasItem("sample_document")));

        // 4. 查看集合结构
        mockMvc.perform(get("/sample/milvus/lifecycle/collection/describe")
                        .param("collectionName", "sample_document"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.collectionName").value("sample_document"))
                .andExpect(jsonPath("$.data.loadState").value("Loaded"));

        // 5. 显式加载集合
        mockMvc.perform(post("/sample/milvus/lifecycle/collection/load")
                        .param("collectionName", "sample_document"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @Order(2)
    @DisplayName("2. 测试单向量 CRUD 与检索控制器 (MilvusVectorController)")
    void testVectorController() throws Exception {
        DocumentInsertDto insertDto = DocumentInsertDto.builder()
                .id(1L)
                .category("TECH")
                .tenantId("tenant_a")
                .title("Spring Boot 向量检索技术")
                .content("Milvus 基础设施全场景实战")
                .vector(List.of(0.1f, 0.2f, 0.3f, 0.4f))
                .build();

        // 1. 单文档插入
        mockMvc.perform(post("/sample/milvus/vector/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(insertDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(1));

        // 2. 单文档覆盖写入 (Upsert)
        mockMvc.perform(post("/sample/milvus/vector/upsert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(insertDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(1));

        // 3. 批量切片数据摄取
        DocumentInsertDto doc2 = DocumentInsertDto.builder()
                .id(2L)
                .category("AI")
                .tenantId("tenant_b")
                .title("LLM 与 RAG 架构设计")
                .content("大模型向量召回优化")
                .vector(List.of(0.2f, 0.3f, 0.4f, 0.5f))
                .build();
        DocumentInsertDto doc3 = DocumentInsertDto.builder()
                .id(3L)
                .category("BIGDATA")
                .tenantId("tenant_c")
                .title("海量文本特征抽取")
                .content("向量数据库高性能切片摄取")
                .vector(List.of(0.3f, 0.4f, 0.5f, 0.6f))
                .build();
        DocumentBatchInsertDto batchDto = DocumentBatchInsertDto.builder()
                .documents(Arrays.asList(doc2, doc3))
                .build();
        mockMvc.perform(post("/sample/milvus/vector/batch-insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(2));

        // 3.1 批量覆盖写入 (batchUpsert) 与显式落盘 flush
        DocumentInsertDto doc2Updated = DocumentInsertDto.builder()
                .id(2L)
                .category("AI")
                .tenantId("tenant_b")
                .title("LLM 与 RAG 架构设计 (Upsert)")
                .content("大模型向量召回优化")
                .vector(List.of(0.2f, 0.3f, 0.4f, 0.5f))
                .build();
        DocumentBatchInsertDto batchUpsertDto = DocumentBatchInsertDto.builder()
                .documents(Arrays.asList(doc2Updated, doc3))
                .build();
        mockMvc.perform(post("/sample/milvus/vector/batch-upsert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchUpsertDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(2));

        mockMvc.perform(post("/sample/milvus/lifecycle/collection/flush")
                        .param("collectionName", "sample_document"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 4. 按主键查询、批量点查 (getByIds) 与存在性探测 (existsById)
        mockMvc.perform(get("/sample/milvus/vector/get/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.title").value("Spring Boot 向量检索技术"));

        mockMvc.perform(get("/sample/milvus/vector/batch-get")
                        .param("ids", "1", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").isNotEmpty());

        mockMvc.perform(get("/sample/milvus/vector/exists/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(get("/sample/milvus/vector/exists/999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(false));

        // 5. 向量相似度检索 (ANN Search)
        VectorSearchDto searchDto = VectorSearchDto.builder()
                .vector(List.of(0.1f, 0.2f, 0.3f, 0.4f))
                .topK(5)
                .filterExpr("id > 0")
                .build();
        mockMvc.perform(post("/sample/milvus/vector/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(searchDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").isNotEmpty());

        // 6. 纯标量表达式查询
        mockMvc.perform(get("/sample/milvus/vector/query")
                        .param("expr", "id > 0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 7. 数量统计
        mockMvc.perform(get("/sample/milvus/vector/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNotEmpty());

        // 8. 按主键删除文档 1 与按标量表达式删除文档 3
        mockMvc.perform(delete("/sample/milvus/vector/delete/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(delete("/sample/milvus/vector/delete-by-expr")
                        .param("expr", "id == 3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @Order(3)
    @DisplayName("3. 测试多向量混合检索控制器 (MilvusHybridController)")
    void testHybridController() throws Exception {
        // 1. RRF 融合重排检索
        HybridSearchDto rrfSearchDto = HybridSearchDto.builder()
                .vector(List.of(0.1f, 0.2f, 0.3f, 0.4f))
                .secondaryVector(List.of(0.5f, 0.6f, 0.7f, 0.8f))
                .topK(10)
                .rankerType("RRF")
                .rrfK(60)
                .filterExpr("id > 0")
                .build();
        mockMvc.perform(post("/sample/milvus/hybrid/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rrfSearchDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 2. WEIGHTED 加权融合重排检索
        HybridSearchDto weightedSearchDto = HybridSearchDto.builder()
                .vector(List.of(0.1f, 0.2f, 0.3f, 0.4f))
                .topK(5)
                .rankerType("WEIGHTED")
                .weights(List.of(1.0f))
                .build();
        mockMvc.perform(post("/sample/milvus/hybrid/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(weightedSearchDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        int hybridCallsBeforeFailFast = Mockito.mockingDetails(milvusClientV2).isMock()
                ? (int) Mockito.mockingDetails(milvusClientV2).getInvocations().stream()
                .filter(inv -> "hybridSearch".equals(inv.getMethod().getName()))
                .count()
                : 0;

        // 3. WEIGHTED 权重数量与子检索通道数不匹配时前置 Fail-Fast 拦截 (2 路向量 vs 1 个权重)
        HybridSearchDto mismatchWeightDto = HybridSearchDto.builder()
                .vector(List.of(0.1f, 0.2f, 0.3f, 0.4f))
                .secondaryVector(List.of(0.5f, 0.6f, 0.7f, 0.8f))
                .topK(5)
                .rankerType("WEIGHTED")
                .weights(List.of(1.0f))
                .build();
        mockMvc.perform(post("/sample/milvus/hybrid/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mismatchWeightDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 4. WEIGHTED 缺少 weights 或非法 rankerType 或空向量前置 Fail-Fast 拦截
        HybridSearchDto emptyWeightsDto = HybridSearchDto.builder()
                .vector(List.of(0.1f, 0.2f, 0.3f, 0.4f))
                .topK(5)
                .rankerType("WEIGHTED")
                .weights(Collections.emptyList())
                .build();
        mockMvc.perform(post("/sample/milvus/hybrid/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyWeightsDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        HybridSearchDto invalidRankerDto = HybridSearchDto.builder()
                .vector(List.of(0.1f, 0.2f, 0.3f, 0.4f))
                .topK(5)
                .rankerType("UNKNOWN")
                .build();
        mockMvc.perform(post("/sample/milvus/hybrid/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRankerDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        HybridSearchDto emptyVectorDto = HybridSearchDto.builder()
                .vector(Collections.emptyList())
                .topK(5)
                .build();
        mockMvc.perform(post("/sample/milvus/hybrid/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyVectorDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        if (Mockito.mockingDetails(milvusClientV2).isMock()) {
            long hybridCallsAfterFailFast = Mockito.mockingDetails(milvusClientV2).getInvocations().stream()
                    .filter(inv -> "hybridSearch".equals(inv.getMethod().getName()))
                    .count();
            assertThat(hybridCallsAfterFailFast).isEqualTo(hybridCallsBeforeFailFast);
        }

        // 5. 校验 Sample 容器中 AtengMilvusProperties 批处理配置与 IBaseEnum 枚举契约装配
        assertThat(milvusProperties.getBatchSize()).isEqualTo(1000);
        assertThat(milvusProperties.getMaxRetries()).isEqualTo(3);
        assertThat(milvusProperties.getRetryDelay()).isEqualTo(Duration.ofMillis(200));
        assertThat(milvusProperties.getConsistencyLevel()).isEqualTo(MilvusConsistencyLevel.BOUNDED);
        assertThat((Object) milvusProperties.getConsistencyLevel()).isInstanceOf(IBaseEnum.class);
        assertThat(EnumUtil.fromCode(MilvusConsistencyLevel.class, "BOUNDED"))
                .contains(MilvusConsistencyLevel.BOUNDED);
    }

    @Test
    @Order(4)
    @DisplayName("4. 测试 SaaS 多租户隔离控制器与上下文零污染 (MilvusTenantController)")
    void testTenantController() throws Exception {
        DocumentInsertDto tenantInsertDto = DocumentInsertDto.builder()
                .id(100L)
                .category("SAAS")
                .title("租户隔离专有文档")
                .content("严格保障租户数据安全")
                .vector(List.of(0.1f, 0.1f, 0.1f, 0.1f))
                .build();

        // 1. 租户写入 (DTO 未传 tenantId，验证由上下文自动填充至 tenant_id 而非 category)
        mockMvc.perform(post("/sample/milvus/tenant/insert")
                        .header("X-Tenant-Id", "tenant_corp_alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tenantInsertDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(100));

        // 验证调用后线程上下文已安全重置清理
        assertThat(TenantContextHolder.getTenantId()).isNull();

        if (Mockito.mockingDetails(milvusClientV2).isMock()) {
            ArgumentCaptor<InsertReq> insertCaptor = ArgumentCaptor.forClass(InsertReq.class);
            Mockito.verify(milvusClientV2, Mockito.atLeastOnce()).insert(insertCaptor.capture());
            List<InsertReq> allInserts = insertCaptor.getAllValues();
            InsertReq lastInsert = allInserts.get(allInserts.size() - 1);
            assertThat(lastInsert.getData().get(0).get("tenant_id").getAsString()).isEqualTo("tenant_corp_alpha");
            assertThat(lastInsert.getData().get(0).get("category").getAsString()).isEqualTo("SAAS");
        }

        // 2. 租户检索 (自动织入 tenant_id 条件，而非 category)
        VectorSearchDto tenantSearchDto = VectorSearchDto.builder()
                .vector(List.of(0.1f, 0.1f, 0.1f, 0.1f))
                .topK(5)
                .filterExpr("category == 'SAAS'")
                .build();
        mockMvc.perform(post("/sample/milvus/tenant/search")
                        .header("X-Tenant-Id", "tenant_corp_alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tenantSearchDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        assertThat(TenantContextHolder.getTenantId()).isNull();

        if (Mockito.mockingDetails(milvusClientV2).isMock()) {
            ArgumentCaptor<SearchReq> searchCaptor = ArgumentCaptor.forClass(SearchReq.class);
            Mockito.verify(milvusClientV2, Mockito.atLeastOnce()).search(searchCaptor.capture());
            List<SearchReq> allSearches = searchCaptor.getAllValues();
            assertThat(allSearches.get(allSearches.size() - 1).getFilter())
                    .isEqualTo("(category == 'SAAS') and (tenant_id == 'tenant_corp_alpha')");
        }

        // 3. 租户统计
        mockMvc.perform(get("/sample/milvus/tenant/count")
                        .header("X-Tenant-Id", "tenant_corp_alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNotEmpty());

        assertThat(TenantContextHolder.getTenantId()).isNull();

        awaitGetVisible("/sample/milvus/tenant/get/100", "tenant_corp_alpha");

        // 4. 租户点查：本租户正常读取，跨租户访问严格拦截返回空
        mockMvc.perform(get("/sample/milvus/tenant/get/100")
                        .header("X-Tenant-Id", "tenant_corp_alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.tenantId").value("tenant_corp_alpha"));
        assertThat(TenantContextHolder.getTenantId()).isNull();

        mockMvc.perform(get("/sample/milvus/tenant/get/100")
                        .header("X-Tenant-Id", "tenant_corp_other"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertThat(TenantContextHolder.getTenantId()).isNull();

        // 5. 租户安全删除：自动织入 tenant_id 隔离过滤
        mockMvc.perform(delete("/sample/milvus/tenant/delete/100")
                        .header("X-Tenant-Id", "tenant_corp_alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
        assertThat(TenantContextHolder.getTenantId()).isNull();

        if (Mockito.mockingDetails(milvusClientV2).isMock()) {
            ArgumentCaptor<DeleteReq> deleteCaptor = ArgumentCaptor.forClass(DeleteReq.class);
            Mockito.verify(milvusClientV2, Mockito.atLeastOnce()).delete(deleteCaptor.capture());
            List<DeleteReq> allDeletes = deleteCaptor.getAllValues();
            assertThat(allDeletes.get(allDeletes.size() - 1).getFilter())
                    .isEqualTo("(id in [100]) and (tenant_id == 'tenant_corp_alpha')");
        }
    }

    @Test
    @Order(5)
    @DisplayName("5. 测试 JSR-303 防御性传输校验 (Fail-Fast)")
    void testValidationFailure() throws Exception {
        DocumentInsertDto invalidDto = DocumentInsertDto.builder()
                .id(999L)
                .category("INVALID")
                .build();

        mockMvc.perform(post("/sample/milvus/vector/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidDto)))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertThat(status).isIn(200, 400);
                });
    }

    @Test
    @Order(6)
    @DisplayName("6. 测试集合别名运维与蓝绿无感切流 (MilvusAliasController)")
    void testAliasOperations() throws Exception {
        // 1. 创建别名
        AliasCreateDto createDto = AliasCreateDto.builder()
                .collectionName("sample_document")
                .alias("doc_alias_prod")
                .build();
        mockMvc.perform(post("/sample/milvus/alias/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 2. 变更切流别名
        AliasAlterDto alterDto = AliasAlterDto.builder()
                .collectionName("sample_document")
                .alias("doc_alias_prod")
                .checkLoaded(true)
                .build();
        mockMvc.perform(post("/sample/milvus/alias/alter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(alterDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 3. 列出别名列表
        mockMvc.perform(get("/sample/milvus/alias/list")
                        .param("collectionName", "sample_document"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasItem("doc_alias_prod")));

        // 4. 查看别名详情
        mockMvc.perform(get("/sample/milvus/alias/detail")
                        .param("alias", "doc_alias_prod"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.alias").value("doc_alias_prod"))
                .andExpect(jsonPath("$.data.collectionName").value("sample_document"));

        // 5. 解绑/删除别名
        mockMvc.perform(delete("/sample/milvus/alias/drop")
                        .param("alias", "doc_alias_prod"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @Order(7)
    @DisplayName("7. 测试高级特征检索：GroupBy 去重聚合与 Range Search 范围检索 (MilvusAdvancedSearchController)")
    void testAdvancedSearchOperations() throws Exception {
        // 1. GroupBy 去重聚合检索
        GroupBySearchDto groupByDto = GroupBySearchDto.builder()
                .collectionName("sample_document")
                .vector(List.of(0.1f, 0.2f, 0.3f, 0.4f))
                .groupByField("category")
                .topK(3)
                .build();

        mockMvc.perform(post("/sample/milvus/search/group-by")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupByDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 2. 范围检索 (Range Search)
        RangeSearchDto rangeDto = RangeSearchDto.builder()
                .collectionName("sample_document")
                .vector(List.of(0.1f, 0.2f, 0.3f, 0.4f))
                .radius(0.5f)
                .rangeFilter(0.8f)
                .topK(5)
                .build();

        mockMvc.perform(post("/sample/milvus/search/range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rangeDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 3. MilvusExprHelper 复合过滤表达式检索
        ExprSearchDto scalarExprDto = ExprSearchDto.builder()
                .collectionName("sample_document")
                .vector(List.of(0.2f, 0.3f, 0.4f, 0.5f))
                .category("AI")
                .titlePrefix("LLM%")
                .minId(2L)
                .topK(5)
                .build();
        mockMvc.perform(post("/sample/milvus/search/expr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(scalarExprDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.compiledExpr")
                        .value("(category == 'AI') and (title like 'LLM%') and (id >= 2)"))
                .andExpect(jsonPath("$.data.hits").isArray());

        // 离线桩下额外校验 TEXT_MATCH BM25 表达式构造与单引号安全转义
        if (Mockito.mockingDetails(milvusClientV2).isMock()) {
            ExprSearchDto textMatchDto = ExprSearchDto.builder()
                    .collectionName("sample_document")
                    .vector(List.of(0.1f, 0.2f, 0.3f, 0.4f))
                    .category("TECH")
                    .textMatchQuery("Milvus's RAG")
                    .topK(3)
                    .build();
            mockMvc.perform(post("/sample/milvus/search/expr")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(textMatchDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.compiledExpr")
                            .value("(category == 'TECH') and (TEXT_MATCH(content, 'Milvus\\'s RAG'))"));
        }

        // 无过滤条件 Fail-Fast 校验
        ExprSearchDto emptyExprDto = ExprSearchDto.builder()
                .vector(List.of(0.1f, 0.2f, 0.3f, 0.4f))
                .topK(5)
                .build();
        mockMvc.perform(post("/sample/milvus/search/expr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyExprDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 4. QueryIterator 游标流式深度遍历
        StreamQueryDto streamDto = StreamQueryDto.builder()
                .category("AI")
                .minId(2L)
                .batchSize(100)
                .build();
        mockMvc.perform(post("/sample/milvus/search/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(streamDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.filterExpr").value("(category == 'AI') and (id >= 2)"))
                .andExpect(jsonPath("$.data.batchCount").value(1))
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.items[0].id").isNotEmpty());
    }

    @Test
    @Order(8)
    @DisplayName("8. 测试多逻辑数据库运维与 AutoCloseable 作用域动态切库检索 (MilvusDatabaseController)")
    void testDatabaseLifecycleAndScopedRouting() throws Exception {
        String tempDb = "sample_route_db";
        boolean dropped = false;
        try {
            // 1. 创建临时逻辑数据库
            DatabaseCreateDto createDto = DatabaseCreateDto.builder()
                    .databaseName(tempDb)
                    .build();
            mockMvc.perform(post("/sample/milvus/database/create")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(true));
            assertThat(MilvusContextHolder.getDatabase()).isNull();

            // 2. 对称判存与列表验证
            mockMvc.perform(get("/sample/milvus/database/has")
                            .param("databaseName", tempDb))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(true));

            mockMvc.perform(get("/sample/milvus/database/list"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data", hasItem(tempDb)));

            // 3. AutoCloseable 作用域切库检索并验证上下文零残留
            DatabaseScopedSearchDto scopedSearchDto = DatabaseScopedSearchDto.builder()
                    .databaseName("default")
                    .vector(List.of(0.1f, 0.2f, 0.3f, 0.4f))
                    .topK(3)
                    .filterExpr("id > 0")
                    .build();
            mockMvc.perform(post("/sample/milvus/database/search")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(scopedSearchDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray());
            assertThat(MilvusContextHolder.getDatabase()).isNull();

            // 4. 删除临时逻辑数据库并确认不存在
            mockMvc.perform(delete("/sample/milvus/database/drop")
                            .param("databaseName", tempDb))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(true));
            dropped = true;

            mockMvc.perform(get("/sample/milvus/database/has")
                            .param("databaseName", tempDb))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(false));
            assertThat(MilvusContextHolder.getDatabase()).isNull();
        } finally {
            if (!dropped) {
                try {
                    mockMvc.perform(delete("/sample/milvus/database/drop").param("databaseName", tempDb));
                } catch (Exception ignored) {
                }
            }
            MilvusContextHolder.clear();
        }
    }

    @Test
    @Order(9)
    @DisplayName("9. 测试物理分区生命周期运维、isPartitionsLoaded、auto-create-partition 与显式落盘 flush (MilvusPartitionController)")
    void testPartitionLifecycleAndFlush() throws Exception {
        String archiveCol = "sample_archive_log";
        try {
            // 1. 初始化归档日志集合
            mockMvc.perform(post("/sample/milvus/partition/init"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 2. 显式创建物理分区 part_202611 并列表验证
            PartitionOpDto createPartDto = PartitionOpDto.builder()
                    .collectionName(archiveCol)
                    .partitionName("part_202611")
                    .build();
            mockMvc.perform(post("/sample/milvus/partition/create")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createPartDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(true));

            mockMvc.perform(get("/sample/milvus/partition/list")
                            .param("collectionName", archiveCol))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data", hasItem("part_202611")));

            // 3. 加载分区并调用 isPartitionsLoaded 探测
            PartitionBatchOpDto batchPartDto = PartitionBatchOpDto.builder()
                    .collectionName(archiveCol)
                    .partitionNames(List.of("part_202611"))
                    .build();
            mockMvc.perform(post("/sample/milvus/partition/load")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(batchPartDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(true));

            awaitPartitionLoadState(archiveCol, "part_202611", true);

            // 4. 演示 auto-create-partition 自动补建 part_202612 并显式 flush 落盘
            PartitionArchiveInsertDto archiveInsertDto = PartitionArchiveInsertDto.builder()
                    .partitionName("part_202612")
                    .id(5001L)
                    .archiveMonth("2026-12")
                    .title("归档审计日志")
                    .vector(List.of(0.1f, 0.2f, 0.3f, 0.4f))
                    .build();
            mockMvc.perform(post("/sample/milvus/partition/archive-insert")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(archiveInsertDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(5001L));

            mockMvc.perform(get("/sample/milvus/partition/list")
                            .param("collectionName", archiveCol))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data", hasItem("part_202612")));

            mockMvc.perform(post("/sample/milvus/lifecycle/collection/flush")
                            .param("collectionName", archiveCol))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(true));

            // 5. 释放分区并确认 loaded=false，随后删除分区
            mockMvc.perform(post("/sample/milvus/partition/release")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(batchPartDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(true));

            awaitPartitionLoadState(archiveCol, "part_202611", false);

            mockMvc.perform(delete("/sample/milvus/partition")
                            .param("collectionName", archiveCol)
                            .param("partitionName", "part_202611"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(true));
        } finally {
            try {
                mockMvc.perform(delete("/sample/milvus/lifecycle/collection")
                        .param("collectionName", archiveCol));
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @Order(10)
    @DisplayName("10. 测试集合释放与删除终态清理 (MilvusLifecycleController)")
    void testLifecycleReleaseAndDrop() throws Exception {
        // 1. 显式释放集合内存
        mockMvc.perform(post("/sample/milvus/lifecycle/collection/release")
                        .param("collectionName", "sample_document"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 2. 删除测试集合
        mockMvc.perform(delete("/sample/milvus/lifecycle/collection")
                        .param("collectionName", "sample_document"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @Order(11)
    @DisplayName("11. 测试多模态多特征向量检索与混合召回 (MilvusMultimodalController)")
    void testMultimodalOperations() throws Exception {
        // 1. 初始化多模态集合
        mockMvc.perform(post("/sample/milvus/multimodal/init"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("多模态集合与向量索引初始化成功并已就绪加载"));

        // 2. 插入多模态文档
        MultimodalInsertDto insertDto = MultimodalInsertDto.builder()
                .id(1001L)
                .title("多模态检索技术白皮书")
                .summary("结合稠密语义与稀疏关键字的高精度检索架构")
                .denseVector(List.of(0.1f, 0.2f, 0.3f, 0.4f))
                .sparseVector(Map.of(101L, 0.85f, 202L, 0.45f))
                .extra(Map.of("source", "crawler", "score", 99))
                .build();

        mockMvc.perform(post("/sample/milvus/multimodal/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(insertDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(1001L));

        mockMvc.perform(post("/sample/milvus/lifecycle/collection/flush")
                        .param("collectionName", "sample_multimodal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
        awaitGetVisible("/sample/milvus/multimodal/1001", null);

        // 3. 点查多模态文档
        mockMvc.perform(get("/sample/milvus/multimodal/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1001L))
                .andExpect(jsonPath("$.data.title").value("多模态检索技术白皮书"));

        // 4. 稠密向量检索
        MultimodalDenseSearchDto denseDto = MultimodalDenseSearchDto.builder()
                .vector(List.of(0.1f, 0.2f, 0.3f, 0.4f))
                .topK(3)
                .build();

        mockMvc.perform(post("/sample/milvus/multimodal/search/dense")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(denseDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 5. 稀疏向量检索
        MultimodalSparseSearchDto sparseDto = MultimodalSparseSearchDto.builder()
                .sparseVector(Map.of(101L, 0.85f))
                .topK(3)
                .build();

        mockMvc.perform(post("/sample/milvus/multimodal/search/sparse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sparseDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 6. 稠密与稀疏多路混合召回
        MultimodalHybridSearchDto hybridDto = MultimodalHybridSearchDto.builder()
                .denseVector(List.of(0.1f, 0.2f, 0.3f, 0.4f))
                .sparseVector(Map.of(101L, 0.85f))
                .topK(5)
                .build();

        mockMvc.perform(post("/sample/milvus/multimodal/search/hybrid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(hybridDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 7. 显式释放并删除多模态测试集合 (保证环境零残留)
        mockMvc.perform(post("/sample/milvus/lifecycle/collection/release")
                        .param("collectionName", "sample_multimodal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(delete("/sample/milvus/lifecycle/collection")
                        .param("collectionName", "sample_multimodal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    private void awaitPartitionLoadState(String collectionName, String partitionName, boolean expectedLoaded) throws Exception {
        int maxAttempts = Mockito.mockingDetails(milvusClientV2).isMock() ? 1 : 25;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            String body = mockMvc.perform(get("/sample/milvus/partition/loaded")
                            .param("collectionName", collectionName)
                            .param("partitionNames", partitionName))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            boolean loaded = objectMapper.readTree(body).path("data").asBoolean();
            if (loaded == expectedLoaded) {
                return;
            }
            Thread.sleep(200);
        }
        mockMvc.perform(get("/sample/milvus/partition/loaded")
                        .param("collectionName", collectionName)
                        .param("partitionNames", partitionName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(expectedLoaded));
    }

    private void awaitGetVisible(String uri, String tenantHeader) throws Exception {
        int maxAttempts = Mockito.mockingDetails(milvusClientV2).isMock() ? 1 : 40;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            var request = get(uri);
            if (tenantHeader != null) {
                request.header("X-Tenant-Id", tenantHeader);
            }
            String body = mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            if (objectMapper.readTree(body).path("data").has("id")) {
                return;
            }
            Thread.sleep(250);
        }
        var request = get(uri);
        if (tenantHeader != null) {
            request.header("X-Tenant-Id", tenantHeader);
        }
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").exists());
    }
}

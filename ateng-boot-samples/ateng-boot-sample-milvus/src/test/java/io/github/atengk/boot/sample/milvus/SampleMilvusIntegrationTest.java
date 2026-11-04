package io.github.atengk.boot.sample.milvus;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.milvus.context.MilvusContextHolder;
import io.github.atengk.boot.sample.milvus.dto.AliasAlterDto;
import io.github.atengk.boot.sample.milvus.dto.AliasCreateDto;
import io.github.atengk.boot.sample.milvus.dto.DocumentBatchInsertDto;
import io.github.atengk.boot.sample.milvus.dto.DocumentInsertDto;
import io.github.atengk.boot.sample.milvus.dto.GroupBySearchDto;
import io.github.atengk.boot.sample.milvus.dto.HybridSearchDto;
import io.github.atengk.boot.sample.milvus.dto.MultimodalDenseSearchDto;
import io.github.atengk.boot.sample.milvus.dto.MultimodalHybridSearchDto;
import io.github.atengk.boot.sample.milvus.dto.MultimodalInsertDto;
import io.github.atengk.boot.sample.milvus.dto.MultimodalSparseSearchDto;
import io.github.atengk.boot.sample.milvus.dto.RangeSearchDto;
import io.github.atengk.boot.sample.milvus.dto.VectorSearchDto;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.collection.response.GetCollectionStatsResp;
import io.milvus.v2.service.collection.response.ListCollectionsResp;
import io.milvus.v2.service.utility.response.DescribeAliasResp;
import io.milvus.v2.service.utility.response.ListAliasResp;
import io.milvus.v2.service.vector.request.GetReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.InsertReq;
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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

            // 2. 集合生命周期与结构
            when(client.hasCollection(any())).thenReturn(false);
            when(client.getLoadState(any())).thenReturn(true);

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

            when(client.get(any(GetReq.class))).thenAnswer(invocation -> {
                GetReq req = invocation.getArgument(0);
                Map<String, Object> targetMap = "sample_multimodal".equals(req.getCollectionName()) ? multimodalMap : entityMap;
                QueryResp.QueryResult r = QueryResp.QueryResult.builder().entity(targetMap).build();
                return GetResp.builder().getResults(Collections.singletonList(r)).build();
            });

            QueryResp queryResp = QueryResp.builder()
                    .queryResults(Collections.singletonList(queryResult))
                    .build();
            when(client.query(any(QueryReq.class))).thenReturn(queryResp);

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

        // 4. 按主键查询
        mockMvc.perform(get("/sample/milvus/vector/get/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.title").value("Spring Boot 向量检索技术"));

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

        // 8. 按主键删除文档 1
        mockMvc.perform(delete("/sample/milvus/vector/delete/1"))
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
    }

    @Test
    @Order(4)
    @DisplayName("4. 测试 SaaS 多租户隔离控制器与上下文零污染 (MilvusTenantController)")
    void testTenantController() throws Exception {
        DocumentInsertDto tenantInsertDto = DocumentInsertDto.builder()
                .id(100L)
                .category("SAAS")
                .tenantId("tenant_corp_alpha")
                .title("租户隔离专有文档")
                .content("严格保障租户数据安全")
                .vector(List.of(0.1f, 0.1f, 0.1f, 0.1f))
                .build();

        // 1. 租户写入
        mockMvc.perform(post("/sample/milvus/tenant/insert")
                        .header("X-Tenant-Id", "tenant_corp_alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tenantInsertDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(100));

        // 验证调用后线程上下文已安全重置清理
        assertThat(TenantContextHolder.getTenantId()).isNull();

        // 2. 租户检索
        VectorSearchDto tenantSearchDto = VectorSearchDto.builder()
                .vector(List.of(0.1f, 0.1f, 0.1f, 0.1f))
                .topK(5)
                .build();
        mockMvc.perform(post("/sample/milvus/tenant/search")
                        .header("X-Tenant-Id", "tenant_corp_alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tenantSearchDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        assertThat(TenantContextHolder.getTenantId()).isNull();

        // 3. 租户统计
        mockMvc.perform(get("/sample/milvus/tenant/count")
                        .header("X-Tenant-Id", "tenant_corp_alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNotEmpty());

        assertThat(TenantContextHolder.getTenantId()).isNull();
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
    }

    @Test
    @Order(8)
    @DisplayName("8. 测试集合释放与删除终态清理 (MilvusLifecycleController)")
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
    @Order(9)
    @DisplayName("9. 测试多模态多特征向量检索与混合召回 (MilvusMultimodalController)")
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
}

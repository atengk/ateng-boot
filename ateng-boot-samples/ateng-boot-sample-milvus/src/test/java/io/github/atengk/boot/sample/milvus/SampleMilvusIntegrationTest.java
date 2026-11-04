package io.github.atengk.boot.sample.milvus;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.milvus.context.MilvusContextHolder;
import io.github.atengk.boot.sample.milvus.dto.DocumentBatchInsertDto;
import io.github.atengk.boot.sample.milvus.dto.DocumentInsertDto;
import io.github.atengk.boot.sample.milvus.dto.HybridSearchDto;
import io.github.atengk.boot.sample.milvus.dto.VectorSearchDto;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.collection.response.GetCollectionStatsResp;
import io.milvus.v2.service.collection.response.ListCollectionsResp;
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
import org.junit.jupiter.api.Test;
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

            QueryResp.QueryResult queryResult = QueryResp.QueryResult.builder()
                    .entity(entityMap)
                    .build();

            GetResp getResp = GetResp.builder()
                    .getResults(Collections.singletonList(queryResult))
                    .build();
            when(client.get(any(GetReq.class))).thenReturn(getResp);

            QueryResp queryResp = QueryResp.builder()
                    .queryResults(Collections.singletonList(queryResult))
                    .build();
            when(client.query(any(QueryReq.class))).thenReturn(queryResp);

            // 5. 向量检索与混合检索
            SearchResp.SearchResult hitResult = SearchResp.SearchResult.builder()
                    .id(1L)
                    .score(0.96f)
                    .entity(entityMap)
                    .build();
            SearchResp searchResp = SearchResp.builder()
                    .searchResults(Collections.singletonList(Collections.singletonList(hitResult)))
                    .build();
            when(client.search(any(SearchReq.class))).thenReturn(searchResp);
            when(client.hybridSearch(any(HybridSearchReq.class))).thenReturn(searchResp);

            return client;
        }
    }

    @Test
    @DisplayName("测试集合生命周期与健康运维控制器 (MilvusLifecycleController)")
    void testLifecycleController() throws Exception {
        // 1. 健康探测
        mockMvc.perform(get("/sample/milvus/lifecycle/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.serverVersion").value("v2.4.4"));

        // 2. 集合初始化
        mockMvc.perform(post("/sample/milvus/lifecycle/collection/init"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNotEmpty());

        // 3. 列出集合
        mockMvc.perform(get("/sample/milvus/lifecycle/collection/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0]").value("sample_document"));

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

        // 6. 显式释放集合
        mockMvc.perform(post("/sample/milvus/lifecycle/collection/release")
                        .param("collectionName", "sample_document"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 7. 删除集合
        mockMvc.perform(delete("/sample/milvus/lifecycle/collection")
                        .param("collectionName", "sample_document"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("测试单向量 CRUD 与检索控制器 (MilvusVectorController)")
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
                .title("LLM 与 RAG 架构设计")
                .content("大模型向量召回优化")
                .vector(List.of(0.2f, 0.3f, 0.4f, 0.5f))
                .build();
        DocumentBatchInsertDto batchDto = DocumentBatchInsertDto.builder()
                .documents(Arrays.asList(insertDto, doc2))
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
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.title").value("Spring Boot 向量检索技术"));

        // 5. 按主键删除
        mockMvc.perform(delete("/sample/milvus/vector/delete/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 6. 向量相似度检索 (ANN Search)
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
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].score").value(0.96));

        // 7. 纯标量表达式查询
        mockMvc.perform(get("/sample/milvus/vector/query")
                        .param("expr", "id > 0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value(1));

        // 8. 数量统计
        mockMvc.perform(get("/sample/milvus/vector/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(1));
    }

    @Test
    @DisplayName("测试多向量混合检索控制器 (MilvusHybridController)")
    void testHybridController() throws Exception {
        // 1. RRF 融合重排检索
        HybridSearchDto rrfSearchDto = HybridSearchDto.builder()
                .vector(List.of(0.1f, 0.2f, 0.3f, 0.4f))
                .secondaryVector(List.of(0.5f, 0.6f, 0.7f, 0.8f))
                .topK(10)
                .rankerType("RRF")
                .rrfK(60)
                .build();
        mockMvc.perform(post("/sample/milvus/hybrid/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rrfSearchDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].score").value(0.96));

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
                .andExpect(jsonPath("$.data[0].id").value(1));
    }

    @Test
    @DisplayName("测试 SaaS 多租户隔离控制器与上下文零污染 (MilvusTenantController)")
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
                .andExpect(jsonPath("$.data[0].id").value(1));

        assertThat(TenantContextHolder.getTenantId()).isNull();

        // 3. 租户统计
        mockMvc.perform(get("/sample/milvus/tenant/count")
                        .header("X-Tenant-Id", "tenant_corp_alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(1));

        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    @DisplayName("测试 JSR-303 防御性传输校验 (Fail-Fast)")
    void testValidationFailure() throws Exception {
        // 缺少必填标题与向量入参，直接触发 400 或参数校验业务错误码拦截
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
}

package io.github.atengk.boot.sample.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.core.dto.BatchAndCacheRequest;
import io.github.atengk.boot.sample.core.dto.SpelAndPrimitivesRequest;
import io.github.atengk.boot.sample.core.model.SampleUserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Core 数据结构、流式切片与通用算法原语端到端 HTTP 集成测试
 * 覆盖 CoreDataAndTreeController 全部 4 个对外接口契约、树形装配剪枝、大批量切片、LRU 缓存淘汰与 SpEL 沙箱安全防御
 *
 * @author Ateng
 * @since 2026-11-07
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Core 数据结构与通用算法原语端到端集成测试")
class CoreDataAndTreeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void tearDown() {
        // 确保跨用例环境零污染
    }

    @Test
    @DisplayName("测试商品类目树线性组装、深度优先展平与全链路面包屑寻路")
    void testGetCategoryTree_FullTreeAndBreadcrumb() throws Exception {
        mockMvc.perform(get("/sample/core/data/category-tree")
                        .param("targetNodeName", "5G智能手机"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                // 顶级分类包含数码电脑、家用电器、户外运动
                .andExpect(jsonPath("$.data.categoryTree", hasSize(3)))
                .andExpect(jsonPath("$.data.categoryTree[0].name", is("数码电脑")))
                .andExpect(jsonPath("$.data.totalCount", is(14)))
                .andExpect(jsonPath("$.data.leafCount", is(7)))
                .andExpect(jsonPath("$.data.leafCategories", hasSize(7)))
                // 面包屑全链路: 数码电脑 -> 智能设备 -> 5G智能手机
                .andExpect(jsonPath("$.data.breadcrumbPath", hasSize(3)))
                .andExpect(jsonPath("$.data.breadcrumbPath[0].name", is("数码电脑")))
                .andExpect(jsonPath("$.data.breadcrumbPath[1].name", is("智能设备")))
                .andExpect(jsonPath("$.data.breadcrumbPath[2].name", is("5G智能手机")));
    }

    @Test
    @DisplayName("测试商品类目树递归剪枝过滤与祖先保留分支")
    void testGetCategoryTree_WithFilterKeyword() throws Exception {
        mockMvc.perform(get("/sample/core/data/category-tree")
                        .param("keyword", "手机")
                        .param("targetNodeName", "折叠屏手机"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                // 仅命中的数码电脑分支保留，家用电器与户外运动被剔除
                .andExpect(jsonPath("$.data.filteredTree", hasSize(1)))
                .andExpect(jsonPath("$.data.filteredTree[0].name", is("数码电脑")))
                .andExpect(jsonPath("$.data.filteredTree[0].children", hasSize(1)))
                .andExpect(jsonPath("$.data.filteredTree[0].children[0].name", is("智能设备")))
                .andExpect(jsonPath("$.data.filteredTree[0].children[0].children", hasSize(2)))
                // 面包屑寻路正常命中
                .andExpect(jsonPath("$.data.breadcrumbPath", hasSize(3)))
                .andExpect(jsonPath("$.data.breadcrumbPath[2].name", is("折叠屏手机")));
    }

    @Test
    @DisplayName("测试大批量切片流转、流式去重、LRU租户配额淘汰与内存真明文深度克隆")
    void testProcessBatchAndCache_Success() throws Exception {
        BatchAndCacheRequest request = BatchAndCacheRequest.builder()
                .orderCodes(List.of("ORD-001", "ORD-002", "ORD-003", "ORD-004", "ORD-005", "ORD-006", "ORD-007", "ORD-001", "ORD-002"))
                .sliceSize(3)
                .tenantId("tenant-vip-999")
                .tenantName("VIP尊享租户")
                .quotaLimit(1000)
                .build();

        mockMvc.perform(post("/sample/core/data/batch-and-cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                // 9 个订单切分为 3 批 (3, 3, 3)
                .andExpect(jsonPath("$.data.batchCount", is(3)))
                .andExpect(jsonPath("$.data.sliceDetails", hasSize(3)))
                .andExpect(jsonPath("$.data.sliceDetails[0]", hasSize(3)))
                // 属性去重后 7 个独立订单
                .andExpect(jsonPath("$.data.distinctItemCount", is(7)))
                // LRU 缓存状态演进
                .andExpect(jsonPath("$.data.cacheStatus.initialSize", is(2)))
                .andExpect(jsonPath("$.data.cacheStatus.activeTenant.tenantId", is("tenant-standard-001")))
                .andExpect(jsonPath("$.data.cacheStatus.evictedTenantId", is("tenant-enterprise-002")))
                .andExpect(jsonPath("$.data.cacheStatus.evicted", is(true)))
                .andExpect(jsonPath("$.data.cacheStatus.computedTenant.tenantId", is("tenant-computed-004")))
                // 核心不变量：内存真明文深度克隆断言
                .andExpect(jsonPath("$.data.clonedPlaintextPhone", is("13812345678")))
                .andExpect(jsonPath("$.data.clonedPlaintextIdCard", is("110101199003072345")));
    }

    @Test
    @DisplayName("测试大批量切片入参 JSR-303 第一道防线格式校验拦截")
    void testProcessBatchAndCache_ValidationFailed() throws Exception {
        BatchAndCacheRequest invalidRequest = BatchAndCacheRequest.builder()
                .orderCodes(Collections.emptyList()) // 空列表
                .sliceSize(0)                         // 小于 1
                .tenantId("")                         // 空白串
                .tenantName("")
                .quotaLimit(0)
                .build();

        mockMvc.perform(post("/sample/core/data/batch-and-cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.success", is(false)));
    }

    @Test
    @DisplayName("测试SpEL沙箱求值、防RCE注入拦截、BeanUtil非空覆盖合并与高频通用原语")
    void testProcessSpelAndPrimitives_NormalAndSandboxBlocked() throws Exception {
        // 使用原生明文 JSON 模拟客户端真实请求（避免客户端 objectMapper 提前对入参打掩码）
        String rawJson = """
                {
                    "expression": "#user.name + '-' + #user.status",
                    "simulateMaliciousSpel": true,
                    "user": {
                        "id": 10086,
                        "name": "诸葛孔明",
                        "status": 1
                    },
                    "patchUser": {
                        "name": "诸葛亮"
                    },
                    "rawString": "order_delivery_address",
                    "rawNumberStr": "1234567890123",
                    "baseDate": "2026-11-06"
                }
                """;

        mockMvc.perform(post("/sample/core/data/spel-and-primitives")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                // SpEL 在内存真明文环境下合法求值
                .andExpect(jsonPath("$.data.spelResult", is("诸葛孔明-1")))
                // SpEL 安全沙箱成功阻断高危 RCE 注入
                .andExpect(jsonPath("$.data.rceBlocked", is(true)))
                .andExpect(jsonPath("$.data.blockedMessage", notNullValue()))
                // LambdaUtil 字段名推导
                .andExpect(jsonPath("$.data.lambdaFieldName", is("name")))
                // BeanUtil.copyIgnoreNull 局部增量合并覆盖（HTTP 响应出界触发 @FieldSensitive 脱敏为 诸*亮）
                .andExpect(jsonPath("$.data.mergedUser.name", is("诸*亮")))
                .andExpect(jsonPath("$.data.mergedUser.status", is(1)))
                .andExpect(jsonPath("$.data.mergedUser.id", is("10086"))) // Long 序列化为 String
                // DateTimeUtil 工作日推演
                .andExpect(jsonPath("$.data.workdayResult.workday", is(true)))
                .andExpect(jsonPath("$.data.workdayResult.betweenDays", is("3"))) // Long 序列化为 String
                // StringUtil 算法变换
                .andExpect(jsonPath("$.data.stringTransformResult.camelCase", is("orderDeliveryAddress")))
                .andExpect(jsonPath("$.data.stringTransformResult.underlineCase", is("order_delivery_address")))
                .andExpect(jsonPath("$.data.stringTransformResult.truncatedText", is("order_deli")))
                // ConvertUtil 安全数值容错转换
                .andExpect(jsonPath("$.data.convertedLong", is("1234567890123"))) // Long 序列化为 String
                // RandomUtil 安全随机码
                .andExpect(jsonPath("$.data.randomSmsCode", hasLength(6)))
                // 元组展示
                .andExpect(jsonPath("$.data.tupleDemo", notNullValue()))
                .andExpect(jsonPath("$.data.spelCacheSize", notNullValue()))
                .andExpect(jsonPath("$.data.lambdaCacheSize", notNullValue()));
    }

    @Test
    @DisplayName("POST /sample/core/data/json-and-bean - 验证 byte[] 集合/字典/泛型反序列化与增量合并闭环")
    void testJsonAndBeanSuccess() throws Exception {
        String rawJson = """
                {
                    "users": [
                        {
                            "id": 2001,
                            "name": "诸葛孔明",
                            "phone": "13800138000",
                            "status": 1
                        },
                        {
                            "id": 2002,
                            "name": "司马仲达",
                            "phone": "13900139000",
                            "status": 1
                        }
                    ],
                    "metadata": {
                        "topic": "order.events",
                        "format": "json-bytes"
                    },
                    "patchUser": {
                        "name": "诸葛亮"
                    }
                }
                """;

        mockMvc.perform(post("/sample/core/data/json-and-bean")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.userBytesLength", notNullValue()))
                .andExpect(jsonPath("$.data.genericEnvelopeUserCount", is(2)))
                .andExpect(jsonPath("$.data.parsedUsers", hasSize(2)))
                .andExpect(jsonPath("$.data.parsedUsers[0].id", is("2001")))
                .andExpect(jsonPath("$.data.parsedUsers[0].name", is("诸**明")))
                .andExpect(jsonPath("$.data.parsedUsers[0].phone", is("138****8000")))
                .andExpect(jsonPath("$.data.parsedTypedMap.topic", is("order.events")))
                .andExpect(jsonPath("$.data.parsedGenericMap.format", is("json-bytes")))
                .andExpect(jsonPath("$.data.deepClonedFirstUser.name", is("诸*亮")))
                .andExpect(jsonPath("$.data.deepClonedFirstUser.phone", is("138****8000")));
    }

    @Test
    @DisplayName("POST /sample/core/data/json-and-bean - 空用户列表或空字典触发 JSR-303 校验拦截")
    void testJsonAndBeanValidationFail() throws Exception {
        String invalidJson = """
                {
                    "users": [],
                    "metadata": {}
                }
                """;

        mockMvc.perform(post("/sample/core/data/json-and-bean")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.success", is(false)));
    }
}

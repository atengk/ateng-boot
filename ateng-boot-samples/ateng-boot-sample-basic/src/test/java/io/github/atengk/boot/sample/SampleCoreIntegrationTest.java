package io.github.atengk.boot.sample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Pair;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.model.Triple;
import io.github.atengk.boot.core.util.*;
import io.github.atengk.boot.sample.entity.UserEntity;
import io.github.atengk.boot.sample.mapper.UserMapper;
import io.github.atengk.boot.sample.vo.UserVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ateng-boot-core 基础设施最高切缝端到端集成测试
 * 在真实 Spring Boot 容器与 Web 环境下，端到端联合演练 Core 底座核心能力：
 * 1. JsonUtil 序列化门面与时区格式；
 * 2. 视图层出界 @FieldSensitive 动态掩码与后台内存真明文不变量（ADR-0012）；
 * 3. AsyncContext 四维上下文快照跨线程池全生命周期透传；
 * 4. SpringUtil 容器全局门面与事件发布；
 * 5. Result 函数式流转算子与业务异常流转；
 * 6. LambdaUtil/CollectionUtil/BeanUtil/RetryUtil/FileUtil/Pair/Triple 标准件联合演练。
 *
 * @author Ateng
 * @since 2026-10-17
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("ateng-boot-core 核心基础设施端到端集成测试")
class SampleCoreIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    private ExecutorService threadPool;

    @BeforeEach
    void setUp() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        threadPool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        if (threadPool != null) {
            threadPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("1. JsonUtil 门面：在容器环境下验证序列化、反序列化、时间格式与长整型防截断")
    void testJsonUtilInSpringContext() {
        UserEntity user = new UserEntity();
        user.setId(104485532445560832L);
        user.setName("Json测试用户");
        user.setCreateTime(LocalDateTime.of(2026, 10, 17, 12, 0, 0));

        // 1. 标准序列化为 JSON
        String json = JsonUtil.toJsonString(user);
        assertThat(json).contains("\"name\":\"Json测试用户\"");
        assertThat(json).contains("2026-10-17 12:00:00");

        // 2. 超大 Long 转 String 序列化模式
        String jsonWithLongStr = JsonUtil.toJsonStringWithLongToString(user);
        assertThat(jsonWithLongStr).contains("\"id\":\"104485532445560832\"");

        // 3. 反序列化与未知属性容错
        String jsonWithUnknown = "{\"id\":1001,\"name\":\"测试\",\"unknownField\":\"ignored\"}";
        UserEntity parsed = JsonUtil.parseObject(jsonWithUnknown, UserEntity.class);
        assertNotNull(parsed);
        assertEquals(1001L, parsed.getId());
        assertEquals("测试", parsed.getName());
    }

    @Test
    @DisplayName("2. 数据安全生命周期分离：Controller 响应动态脱敏，后台 Service/Entity 内存保持 100% 原始真实明文")
    void testDesensitizationAndInMemoryPlaintextInvariant() throws Exception {
        // 1. 在数据库中插入真实明文用户数据
        UserEntity entity = new UserEntity();
        entity.setName("诸葛孔明");
        entity.setPhone("13812345678");
        entity.setIdCard("110101199003072345");
        entity.setAddress("北京市海淀区中关村南大街1号院");
        entity.setAge(30);
        userMapper.insert(entity);
        Long userId = entity.getId();
        assertNotNull(userId);

        // 2. 验证 Service 与数据库持久层内存真明文（严格恪守 ADR-0012 不变量）
        UserEntity queriedFromDb = userMapper.selectById(userId);
        assertNotNull(queriedFromDb);
        assertEquals("13812345678", queriedFromDb.getPhone(), "持久层读取在内存中必须保持原始真实明文");
        assertEquals("110101199003072345", queriedFromDb.getIdCard(), "持久层身份证在内存中必须保持真实明文");

        // 3. 模拟前端通过 HTTP 请求详情接口 /api/users/{id} 获取数据，验证出界动态脱敏
        mockMvc.perform(get("/api/users/" + userId)
                        .characterEncoding("UTF-8")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(String.valueOf(userId)))
                .andExpect(jsonPath("$.data.phone").value("138****5678"))
                .andExpect(jsonPath("$.data.idCard").value("110101********2345"))
                .andExpect(jsonPath("$.data.name").value("诸葛孔明"));
    }

    @Test
    @DisplayName("3. AsyncContext 四维上下文快照：在多线程池中安全透传并在消费后严格恢复现场")
    void testAsyncContextPropagationInThreadPool() throws Exception {
        MDC.put("traceId", "trace-core-8888");
        UserContextHolder.setUserId("user-core-999");
        TenantContextHolder.setTenantId("tenant-core-111");

        AtomicReference<String> asyncTraceId = new AtomicReference<>();
        AtomicReference<String> asyncUserId = new AtomicReference<>();
        AtomicReference<String> asyncTenantId = new AtomicReference<>();
        AtomicReference<Boolean> asyncIgnoreTenant = new AtomicReference<>();

        // 使用 AsyncContext.wrapSupplier 包装并在后台线程池中执行
        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                AsyncContext.wrapSupplier(() -> {
                    asyncTraceId.set(MDC.get("traceId"));
                    asyncUserId.set(UserContextHolder.getUserId());
                    asyncTenantId.set(TenantContextHolder.getTenantId());
                    asyncIgnoreTenant.set(TenantContextHolder.isIgnoreTenant());
                    return "task-done";
                }),
                threadPool
        );

        String result = future.get(5, TimeUnit.SECONDS);
        assertEquals("task-done", result);

        // 验证子线程精准捕获了四维上下文
        assertEquals("trace-core-8888", asyncTraceId.get());
        assertEquals("user-core-999", asyncUserId.get());
        assertEquals("tenant-core-111", asyncTenantId.get());
        assertFalse(asyncIgnoreTenant.get());

        // 验证主线程现场未受任何污染
        assertEquals("trace-core-8888", MDC.get("traceId"));
        assertEquals("user-core-999", UserContextHolder.getUserId());
        assertEquals("tenant-core-111", TenantContextHolder.getTenantId());
    }

    @Test
    @DisplayName("4. SpringUtil 全局门面：动态获取容器 Bean、配置解析与应用事件发布")
    void testSpringUtilInApplicationContext() {
        // 1. 获取受管 Bean
        UserMapper mapper = SpringUtil.getBean(UserMapper.class);
        assertNotNull(mapper);

        // 2. 读取当前 Spring 环境配置
        String appName = SpringUtil.getProperty("spring.application.name", "ateng-boot-samples");
        assertNotNull(appName);

        // 3. 动态发布 POJO 事件并验证监听接收
        AtomicReference<String> eventCaptured = new AtomicReference<>();
        if (SpringUtil.getApplicationContext() instanceof org.springframework.context.ConfigurableApplicationContext ctx) {
            ctx.addApplicationListener(event -> {
                if (event instanceof PayloadApplicationEvent<?> payloadEvent) {
                    if ("core-event-test".equals(payloadEvent.getPayload())) {
                        eventCaptured.set((String) payloadEvent.getPayload());
                    }
                }
            });
        }

        SpringUtil.publishEvent("core-event-test");
        assertEquals("core-event-test", eventCaptured.get());
    }

    @Test
    @DisplayName("5. Result 函数式流转算子：map, peek, orElse, orElseThrow 与链式消费")
    void testResultFunctionalMonadicOperators() {
        // 1. 成功流转映射
        Result<String> origin = Result.success("10086");
        Result<Long> mapped = origin.map(Long::parseLong);
        assertTrue(mapped.isSuccess());
        assertEquals(10086L, mapped.getData());

        // 2. 观察与消费
        AtomicReference<Long> observed = new AtomicReference<>();
        mapped.peek(observed::set);
        assertEquals(10086L, observed.get());

        // 3. 失败透传与 orElse 兜底
        Result<String> fail = Result.fail(404, "数据不存在");
        Result<Integer> failMapped = fail.map(Integer::parseInt);
        assertFalse(failMapped.isSuccess());
        assertEquals(404, failMapped.getCode());
        assertEquals(999, failMapped.orElse(999));

        // 4. orElseThrow 抛出 BusinessException
        BusinessException ex = assertThrows(BusinessException.class, failMapped::orElseThrow);
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("6. 核心标准件联合演练：Pair/Triple, LambdaUtil, CollectionUtil, BeanUtil, RetryUtil 与 FileUtil")
    void testCoreUtilitiesIntegration() {
        // 1. Pair 与 Triple 不可变元组
        Pair<String, Integer> pair = Pair.of("total", 100);
        assertEquals("total", pair.getKey());
        assertEquals(100, pair.getValue());

        Triple<String, Long, Boolean> triple = Triple.of("trace", 200L, true);
        assertEquals("trace", triple.left());
        assertEquals(200L, triple.middle());
        assertTrue(triple.right());

        // 2. LambdaUtil 属性推导
        assertEquals("name", LambdaUtil.getFieldName(UserEntity::getName));
        assertEquals("deleted", LambdaUtil.getFieldName(UserEntity::getDeleted));

        // 3. CollectionUtil 切片与 MoreCollectors
        List<Integer> nums = Arrays.asList(1, 2, 3, 4, 5);
        List<List<Integer>> partitions = CollectionUtil.partition(nums, 2);
        assertEquals(3, partitions.size());

        Map<String, String> safeMap = List.of(new Pair<>("k1", "v1")).stream()
                .collect(MoreCollectors.toSafeMap(Pair::getKey, Pair::getValue));
        assertEquals("v1", safeMap.get("k1"));

        // 4. BeanUtil 实例化拷贝
        UserEntity entity = new UserEntity();
        entity.setName("拷贝测试");
        entity.setAge(25);
        UserVO vo = BeanUtil.copy(entity, UserVO.class);
        assertNotNull(vo);
        assertEquals("拷贝测试", vo.getName());
        assertEquals(25, vo.getAge());

        // 5. RetryUtil 指数退避重试执行
        AtomicInteger attempts = new AtomicInteger();
        String retryResult = RetryUtil.execute(() -> {
            if (attempts.incrementAndGet() < 2) {
                throw new RuntimeException("偶发异常");
            }
            return "ok";
        }, RetryUtil.RetryConfig.builder().maxAttempts(3).delay(Duration.ofMillis(10)).build());
        assertEquals("ok", retryResult);
        assertEquals(2, attempts.get());

        // 6. FileUtil 安全清洗与容量
        assertEquals("secret.doc", FileUtil.cleanFilename("../../secret.doc"));
        assertEquals("png", FileUtil.getExtName("avatar.PNG"));
        assertEquals("1.00 KB", FileUtil.formatSize(1024));
    }

    @Test
    @DisplayName("7. 因果异常体系与堆栈双模演练：无 cause 默认清空堆栈免开销，有 cause 自动保留调用栈与完整因果链")
    void testBusinessExceptionCausalDuality() {
        // 1. 无 cause 业务校验：默认不抓取堆栈，提升高频校验吞吐
        BusinessException lightEx = new BusinessException(CommonResultCode.BAD_REQUEST);
        assertThat(lightEx.getStackTrace()).isEmpty();
        assertThat(lightEx.getCause()).isNull();
        assertThat(lightEx.getCode()).isEqualTo(400);

        // 2. 有底层驱动 cause：自动采集当前堆栈并保留原始因果链
        IOException rootCause = new IOException("底层网络连接重置");
        BusinessException causalEx = new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "第三方网关通信故障", rootCause);
        assertThat(causalEx.getCause()).isSameAs(rootCause);
        assertThat(causalEx.getStackTrace()).isNotEmpty();
        assertThat(causalEx.getMessage()).isEqualTo("第三方网关通信故障");
        assertThat(causalEx.getResultCode()).isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR);
    }
}

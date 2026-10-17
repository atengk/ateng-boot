package io.github.atengk.boot.core.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 统一 API 响应载体 Result 测试
 *
 * @author Ateng
 * @since 2026-10-15
 */
@DisplayName("统一 API 响应载体 Result 测试")
class ResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("构建无数据成功响应")
    void shouldBuildSuccessWithoutData() {
        Result<Void> result = Result.success();

        assertEquals(200, result.getCode());
        assertEquals("操作成功", result.getMsg());
        assertNull(result.getData());
        assertTrue(result.isSuccess());
        assertTrue(result.getTimestamp() > 0);
        assertNull(result.getTraceId());
    }

    @Test
    @DisplayName("构建携带数据成功响应")
    void shouldBuildSuccessWithData() {
        String data = "hello ateng";
        Result<String> result = Result.success(data);

        assertEquals(200, result.getCode());
        assertEquals("操作成功", result.getMsg());
        assertEquals(data, result.getData());
        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("构建携带自定义文案与数据成功响应")
    void shouldBuildSuccessWithCustomMsgAndData() {
        Result<Integer> result = Result.success(100, "创建成功");

        assertEquals(200, result.getCode());
        assertEquals("创建成功", result.getMsg());
        assertEquals(100, result.getData());
        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("通过错误码与文案构建失败响应")
    void shouldBuildFailWithCodeAndMsg() {
        Result<String> result = Result.fail(400, "参数格式错误");

        assertEquals(400, result.getCode());
        assertEquals("参数格式错误", result.getMsg());
        assertNull(result.getData());
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("通过 IResultCode 构建失败响应")
    void shouldBuildFailWithResultCode() {
        Result<Void> result = Result.fail(CommonResultCode.FORBIDDEN);

        assertEquals(403, result.getCode());
        assertEquals("权限不足，拒绝访问", result.getMsg());
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("自动从 MDC 抓取当前 traceId")
    void shouldCaptureTraceIdFromMdcAutomatically() {
        MDC.put("traceId", "trace-test-12345");

        Result<String> result = Result.success("test");
        assertEquals("trace-test-12345", result.getTraceId());
    }

    @Test
    @DisplayName("支持显式链式设置 traceId")
    void shouldSupportExplicitTraceIdSetting() {
        Result<String> result = Result.<String>success("test")
                .traceId("custom-trace-id");

        assertEquals("custom-trace-id", result.getTraceId());
    }

    @Test
    @DisplayName("getOrThrow 在成功状态下正常返回数据")
    void shouldReturnDataOnGetOrThrowWhenSuccess() {
        Result<String> result = Result.success("valid data");
        assertEquals("valid data", result.getOrThrow());
    }

    @Test
    @DisplayName("getOrThrow 在失败状态下抛出对应 BusinessException")
    void shouldThrowBusinessExceptionOnGetOrThrowWhenFailed() {
        Result<String> result = Result.fail(CommonResultCode.NOT_FOUND);

        BusinessException exception = assertThrows(BusinessException.class, result::getOrThrow);
        assertEquals(404, exception.getCode());
        assertEquals("请求资源不存在", exception.getMsg());
    }

    @Test
    @DisplayName("JSON 序列化契约：traceId 为 null 时忽略该字段，非空时包含")
    void shouldRespectJsonSerializationContract() throws Exception {
        // 场景 1: traceId 为 null
        Result<String> resultWithoutTrace = Result.success("payload");
        String jsonWithoutTrace = objectMapper.writeValueAsString(resultWithoutTrace);
        JsonNode nodeWithoutTrace = objectMapper.readTree(jsonWithoutTrace);

        assertTrue(nodeWithoutTrace.has("code"));
        assertTrue(nodeWithoutTrace.has("msg"));
        assertTrue(nodeWithoutTrace.has("data"));
        assertTrue(nodeWithoutTrace.has("timestamp"));
        assertTrue(nodeWithoutTrace.has("success"));
        assertTrue(nodeWithoutTrace.get("success").asBoolean());
        assertFalse(nodeWithoutTrace.has("traceId"));

        // 场景 2: traceId 不为 null
        Result<String> resultWithTrace = Result.<String>success("payload").traceId("trace-999");
        String jsonWithTrace = objectMapper.writeValueAsString(resultWithTrace);
        JsonNode nodeWithTrace = objectMapper.readTree(jsonWithTrace);

        assertTrue(nodeWithTrace.has("traceId"));
        assertEquals("trace-999", nodeWithTrace.get("traceId").asText());

        // 场景 3: 反序列化完整性还原
        Result<String> deserialized = objectMapper.readValue(
                jsonWithTrace,
                new TypeReference<Result<String>>() {}
        );
        assertEquals(200, deserialized.getCode());
        assertEquals("payload", deserialized.getData());
        assertTrue(deserialized.isSuccess());
        assertEquals("trace-999", deserialized.getTraceId());

        // 场景 4: 反序列化无 traceId 的 JSON 时不被本地调用者线程的 MDC 污染
        MDC.put("traceId", "local-thread-trace-id");
        Result<String> deserializedWithoutTrace = objectMapper.readValue(
                jsonWithoutTrace,
                new TypeReference<Result<String>>() {}
        );
        assertNull(deserializedWithoutTrace.getTraceId());
    }

    @Test
    @DisplayName("map: 成功状态下正常进行载荷类型转换并保留元信息")
    void shouldMapSuccessfullyWhenSuccess() {
        Result<String> original = Result.<String>success("123").traceId("t-1");
        Result<Integer> mapped = original.map(Integer::parseInt);

        assertTrue(mapped.isSuccess());
        assertEquals(200, mapped.getCode());
        assertEquals(123, mapped.getData());
        assertEquals("t-1", mapped.getTraceId());
        assertEquals(original.getTimestamp(), mapped.getTimestamp());
    }

    @Test
    @DisplayName("map: 成功但载荷为 null 时安全返回 null 载荷")
    void shouldMapNullDataSafelyWhenSuccess() {
        Result<String> original = Result.success();
        Result<Integer> mapped = original.map(Integer::parseInt);

        assertTrue(mapped.isSuccess());
        assertNull(mapped.getData());
    }

    @Test
    @DisplayName("map: 失败状态下跳过转换并透传原错误码、错误提示与 traceId")
    void shouldPassThroughFailOnMap() {
        Result<String> fail = Result.<String>fail(403, "无权限").traceId("t-fail");
        Result<Integer> mapped = fail.map(Integer::parseInt);

        assertFalse(mapped.isSuccess());
        assertEquals(403, mapped.getCode());
        assertEquals("无权限", mapped.getMsg());
        assertNull(mapped.getData());
        assertEquals("t-fail", mapped.getTraceId());
    }

    @Test
    @DisplayName("peek: 仅在成功且数据非空时触发观察，不改变 Result 自身")
    void shouldPeekOnlyWhenSuccessAndDataPresent() {
        java.util.concurrent.atomic.AtomicReference<String> observed = new java.util.concurrent.atomic.AtomicReference<>();
        Result<String> res = Result.success("ateng").peek(observed::set);

        assertSame(res, res.peek(v -> {}));
        assertEquals("ateng", observed.get());

        // 失败状态下不触发
        observed.set(null);
        Result.fail("error").peek(v -> observed.set("unexpected"));
        assertNull(observed.get());
    }

    @Test
    @DisplayName("ifSuccess: 成功且数据非空时执行消费操作")
    void shouldExecuteIfSuccess() {
        java.util.concurrent.atomic.AtomicBoolean called = new java.util.concurrent.atomic.AtomicBoolean(false);
        Result.success("ok").ifSuccess(val -> called.set(true));
        assertTrue(called.get());

        called.set(false);
        Result.fail("err").ifSuccess(val -> called.set(true));
        assertFalse(called.get());
    }

    @Test
    @DisplayName("ifFail: 失败状态下执行错误回调")
    void shouldExecuteIfFail() {
        java.util.concurrent.atomic.AtomicInteger capturedCode = new java.util.concurrent.atomic.AtomicInteger();
        Result.fail(404, "not found").ifFail(r -> capturedCode.set(r.getCode()));
        assertEquals(404, capturedCode.get());

        capturedCode.set(0);
        Result.success("fine").ifFail(r -> capturedCode.set(r.getCode()));
        assertEquals(0, capturedCode.get());
    }

    @Test
    @DisplayName("orElse 与 orElseGet: 成功返回原数据，失败或 null 返回保底值")
    void shouldReturnDefaultValueOnOrElseAndOrElseGet() {
        // 成功且非空
        assertEquals("real", Result.success("real").orElse("fallback"));
        assertEquals("real", Result.success("real").orElseGet(() -> "fallback"));

        // 成功但为 null
        assertEquals("fallback", Result.<String>success().orElse("fallback"));
        assertEquals("fallback", Result.<String>success().orElseGet(() -> "fallback"));

        // 失败
        assertEquals("fallback", Result.<String>fail().orElse("fallback"));
        assertEquals("fallback", Result.<String>fail().orElseGet(() -> "fallback"));
    }

    @Test
    @DisplayName("orElseThrow: 成功返回数据，失败抛出 BusinessException")
    void shouldThrowBusinessExceptionOnOrElseThrow() {
        assertEquals("data", Result.success("data").orElseThrow());

        BusinessException ex = assertThrows(BusinessException.class, () -> Result.fail(502, "网关超时").orElseThrow());
        assertEquals(502, ex.getCode());
        assertEquals("网关超时", ex.getMsg());
    }

    @Test
    @DisplayName("orElseThrow(Function): 失败时抛出定制异常")
    void shouldThrowCustomExceptionOnOrElseThrow() {
        assertEquals("data", Result.success("data").orElseThrow(IllegalStateException::new));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> Result.fail("参数非法").orElseThrow(IllegalArgumentException::new)
        );
        assertEquals("参数非法", ex.getMessage());
    }
}

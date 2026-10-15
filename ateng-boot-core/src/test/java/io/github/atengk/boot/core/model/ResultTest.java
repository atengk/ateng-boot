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
}

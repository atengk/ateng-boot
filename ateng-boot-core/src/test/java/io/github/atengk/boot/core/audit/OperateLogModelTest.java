package io.github.atengk.boot.core.audit;

import io.github.atengk.boot.core.util.JsonUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 操作审计日志模型与操作动作枚举单元测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
class OperateLogModelTest {

    @Test
    @DisplayName("验证 OperateTypeEnum 涵盖 11 种通用操作动作及其编码与描述")
    void shouldVerifyAllOperateTypeEnumConstants() {
        assertEquals(11, OperateTypeEnum.values().length);

        assertEquals(0, OperateTypeEnum.OTHER.getCode());
        assertEquals("其他", OperateTypeEnum.OTHER.getDescription());

        assertEquals(1, OperateTypeEnum.INSERT.getCode());
        assertEquals("新增", OperateTypeEnum.INSERT.getDescription());

        assertEquals(2, OperateTypeEnum.UPDATE.getCode());
        assertEquals("修改", OperateTypeEnum.UPDATE.getDescription());

        assertEquals(3, OperateTypeEnum.DELETE.getCode());
        assertEquals("删除", OperateTypeEnum.DELETE.getDescription());

        assertEquals(4, OperateTypeEnum.QUERY.getCode());
        assertEquals("查询", OperateTypeEnum.QUERY.getDescription());

        assertEquals(5, OperateTypeEnum.EXPORT.getCode());
        assertEquals("导出", OperateTypeEnum.EXPORT.getDescription());

        assertEquals(6, OperateTypeEnum.IMPORT.getCode());
        assertEquals("导入", OperateTypeEnum.IMPORT.getDescription());

        assertEquals(7, OperateTypeEnum.LOGIN.getCode());
        assertEquals("登录", OperateTypeEnum.LOGIN.getDescription());

        assertEquals(8, OperateTypeEnum.LOGOUT.getCode());
        assertEquals("登出", OperateTypeEnum.LOGOUT.getDescription());

        assertEquals(9, OperateTypeEnum.GRANT.getCode());
        assertEquals("授权", OperateTypeEnum.GRANT.getDescription());

        assertEquals(10, OperateTypeEnum.CLEAN.getCode());
        assertEquals("清空", OperateTypeEnum.CLEAN.getDescription());
    }

    @Test
    @DisplayName("验证 OperateTypeEnum.of(code) 安全匹配与兜底降级")
    void shouldMatchOperateTypeEnumByCodeSafely() {
        assertEquals(OperateTypeEnum.INSERT, OperateTypeEnum.of(1));
        assertEquals(OperateTypeEnum.CLEAN, OperateTypeEnum.of(10));
        assertEquals(OperateTypeEnum.OTHER, OperateTypeEnum.of(0));

        // 越界与空值安全兜底为 OTHER
        assertEquals(OperateTypeEnum.OTHER, OperateTypeEnum.of(null));
        assertEquals(OperateTypeEnum.OTHER, OperateTypeEnum.of(999));
        assertEquals(OperateTypeEnum.OTHER, OperateTypeEnum.of(-1));
    }

    @Test
    @DisplayName("验证 OperateTypeEnum.fromValue 安全反序列化与多源解析")
    void shouldParseOperateTypeFromVariousValues() {
        assertEquals(OperateTypeEnum.OTHER, OperateTypeEnum.fromValue(null));
        assertEquals(OperateTypeEnum.INSERT, OperateTypeEnum.fromValue(1));
        assertEquals(OperateTypeEnum.UPDATE, OperateTypeEnum.fromValue("UPDATE"));
        assertEquals(OperateTypeEnum.DELETE, OperateTypeEnum.fromValue("delete"));
        assertEquals(OperateTypeEnum.QUERY, OperateTypeEnum.fromValue("4"));
        assertEquals(OperateTypeEnum.OTHER, OperateTypeEnum.fromValue("unknown-action"));
    }

    @Test
    @DisplayName("验证 OperateLogModel 构造、Builder 与字段设值")
    void shouldConstructOperateLogModelViaBuilderAndSetters() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 18, 12, 30, 0);
        OperateLogModel model = OperateLogModel.builder()
                .traceId("trace-001")
                .tenantId("tenant-100")
                .userId("user-10001")
                .userName("张三")
                .module("用户管理")
                .operateType(OperateTypeEnum.INSERT)
                .description("新增用户账号")
                .requestMethod("POST")
                .requestUrl("/api/users")
                .clientIp("192.168.1.100")
                .operateLocation("中国北京北京")
                .requestParams("{\"username\":\"zhangsan\"}")
                .responseResult("{\"code\":200,\"msg\":\"success\"}")
                .status(0)
                .costTime(25L)
                .errorMsg(null)
                .operateTime(now)
                .build();

        assertEquals("trace-001", model.getTraceId());
        assertEquals("tenant-100", model.getTenantId());
        assertEquals("user-10001", model.getUserId());
        assertEquals("张三", model.getUserName());
        assertEquals("用户管理", model.getModule());
        assertEquals(OperateTypeEnum.INSERT, model.getOperateType());
        assertEquals("新增用户账号", model.getDescription());
        assertEquals("POST", model.getRequestMethod());
        assertEquals("/api/users", model.getRequestUrl());
        assertEquals("192.168.1.100", model.getClientIp());
        assertEquals("中国北京北京", model.getOperateLocation());
        assertEquals("{\"username\":\"zhangsan\"}", model.getRequestParams());
        assertEquals("{\"code\":200,\"msg\":\"success\"}", model.getResponseResult());
        assertEquals(0, model.getStatus());
        assertEquals(25L, model.getCostTime());
        assertNull(model.getErrorMsg());
        assertEquals(now, model.getOperateTime());
    }

    @Test
    @DisplayName("验证 isSuccess() 语法糖判定逻辑")
    void shouldDetermineIsSuccessCorrectly() {
        OperateLogModel model = new OperateLogModel();

        // 默认 status 为 null -> false
        assertFalse(model.isSuccess());

        // status = 0 -> 成功
        model.setStatus(0);
        assertTrue(model.isSuccess());

        // status = 1 -> 失败
        model.setStatus(1);
        assertFalse(model.isSuccess());

        // status = 500 -> 失败
        model.setStatus(500);
        assertFalse(model.isSuccess());

        // status 重置为 null -> 失败
        model.setStatus(null);
        assertFalse(model.isSuccess());
    }

    @Test
    @DisplayName("验证 addExtra 便捷扩展字典操作")
    void shouldAddExtraConveniently() {
        OperateLogModel model = new OperateLogModel();
        assertNull(model.getExtra());

        model.addExtra("browser", "Chrome")
                .addExtra("os", "Windows 11")
                .addExtra("retryCount", 2);

        assertNotNull(model.getExtra());
        assertEquals(3, model.getExtra().size());
        assertEquals("Chrome", model.getExtra().get("browser"));
        assertEquals("Windows 11", model.getExtra().get("os"));
        assertEquals(2, model.getExtra().get("retryCount"));
    }

    @Test
    @DisplayName("验证 Jackson 序列化与反序列化全要素无失真且无 @class 污染")
    void shouldSerializeAndDeserializeJsonCleanly() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 18, 12, 30, 0);
        Map<String, Object> extra = new HashMap<>();
        extra.put("bizId", 88888L);
        extra.put("tag", "vip");

        OperateLogModel original = OperateLogModel.builder()
                .traceId("trace-abc")
                .tenantId("tenant-001")
                .userId("user-999")
                .userName("李四")
                .module("订单管理")
                .operateType(OperateTypeEnum.DELETE)
                .description("删除过期订单")
                .requestMethod("DELETE")
                .requestUrl("/api/orders/88888")
                .clientIp("127.0.0.1")
                .operateLocation("内网IP")
                .requestParams("{\"orderId\":88888}")
                .responseResult("{\"code\":200}")
                .status(0)
                .costTime(18L)
                .errorMsg(null)
                .extra(extra)
                .operateTime(now)
                .build();

        // 1. 序列化为 JSON
        String json = JsonUtil.toJsonString(original);
        assertNotNull(json);

        // 验证关键字段存在
        assertTrue(json.contains("\"traceId\":\"trace-abc\""));
        assertTrue(json.contains("\"operateType\":\"DELETE\""));
        assertTrue(json.contains("\"status\":0"));
        assertTrue(json.contains("\"module\":\"订单管理\""));
        assertTrue(json.contains("\"operateTime\":\"2026-10-18 12:30:00\""));

        // 验证没有多余的派生字段与 @class 污染
        assertFalse(json.contains("\"@class\""));
        assertFalse(json.contains("\"success\":"));

        // 2. 反序列化
        OperateLogModel deserialized = JsonUtil.parseObject(json, OperateLogModel.class);
        assertNotNull(deserialized);

        assertEquals(original.getTraceId(), deserialized.getTraceId());
        assertEquals(original.getTenantId(), deserialized.getTenantId());
        assertEquals(original.getUserId(), deserialized.getUserId());
        assertEquals(original.getUserName(), deserialized.getUserName());
        assertEquals(original.getModule(), deserialized.getModule());
        assertEquals(original.getOperateType(), deserialized.getOperateType());
        assertEquals(original.getDescription(), deserialized.getDescription());
        assertEquals(original.getRequestMethod(), deserialized.getRequestMethod());
        assertEquals(original.getRequestUrl(), deserialized.getRequestUrl());
        assertEquals(original.getClientIp(), deserialized.getClientIp());
        assertEquals(original.getOperateLocation(), deserialized.getOperateLocation());
        assertEquals(original.getRequestParams(), deserialized.getRequestParams());
        assertEquals(original.getResponseResult(), deserialized.getResponseResult());
        assertEquals(original.getStatus(), deserialized.getStatus());
        assertEquals(original.getCostTime(), deserialized.getCostTime());
        assertEquals(original.getErrorMsg(), deserialized.getErrorMsg());
        assertEquals(original.getOperateTime(), deserialized.getOperateTime());

        // 验证 extra 扩展内容
        assertNotNull(deserialized.getExtra());
        assertEquals("vip", deserialized.getExtra().get("tag"));

        // 验证语法糖判定反序列化后有效
        assertTrue(deserialized.isSuccess());
    }

    @Test
    @DisplayName("容错与对抗验证：未知属性与空值安全反序列化")
    void shouldHandleUnknownPropertiesAndEmptyJsonSafely() {
        // 包含未知属性与外部 success 字段的 JSON
        String jsonWithUnknown = "{\"traceId\":\"trace-unknown\",\"status\":1,\"unknownField\":\"extra-val\",\"success\":false}";
        OperateLogModel model = JsonUtil.parseObject(jsonWithUnknown, OperateLogModel.class);

        assertNotNull(model);
        assertEquals("trace-unknown", model.getTraceId());
        assertEquals(1, model.getStatus());
        assertFalse(model.isSuccess());

        // 空 JSON
        OperateLogModel emptyModel = JsonUtil.parseObject("{}", OperateLogModel.class);
        assertNotNull(emptyModel);
        assertNull(emptyModel.getTraceId());
        assertNull(emptyModel.getStatus());
        assertFalse(emptyModel.isSuccess());
    }
}

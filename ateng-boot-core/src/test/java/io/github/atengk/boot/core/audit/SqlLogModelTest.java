package io.github.atengk.boot.core.audit;

import io.github.atengk.boot.core.enums.SqlTypeEnum;
import io.github.atengk.boot.core.util.JsonUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 结构化 SQL 审计日志模型与 SQL 操作类型枚举单元测试
 *
 * @author Ateng
 * @since 2026-11-06
 */
class SqlLogModelTest {

    @Test
    @DisplayName("验证 SqlTypeEnum 包含 5 种基础数据库操作类型及其编码与描述")
    void shouldVerifyAllSqlTypeEnumConstants() {
        assertEquals(5, SqlTypeEnum.values().length);

        assertEquals("SELECT", SqlTypeEnum.SELECT.getCode());
        assertEquals("查询", SqlTypeEnum.SELECT.getLabel());
        assertTrue(SqlTypeEnum.SELECT.isQuery());
        assertFalse(SqlTypeEnum.SELECT.isDml());

        assertEquals("INSERT", SqlTypeEnum.INSERT.getCode());
        assertEquals("插入", SqlTypeEnum.INSERT.getLabel());
        assertFalse(SqlTypeEnum.INSERT.isQuery());
        assertTrue(SqlTypeEnum.INSERT.isDml());

        assertEquals("UPDATE", SqlTypeEnum.UPDATE.getCode());
        assertEquals("更新", SqlTypeEnum.UPDATE.getLabel());
        assertFalse(SqlTypeEnum.UPDATE.isQuery());
        assertTrue(SqlTypeEnum.UPDATE.isDml());

        assertEquals("DELETE", SqlTypeEnum.DELETE.getCode());
        assertEquals("删除", SqlTypeEnum.DELETE.getLabel());
        assertFalse(SqlTypeEnum.DELETE.isQuery());
        assertTrue(SqlTypeEnum.DELETE.isDml());

        assertEquals("OTHER", SqlTypeEnum.OTHER.getCode());
        assertEquals("其他", SqlTypeEnum.OTHER.getLabel());
        assertFalse(SqlTypeEnum.OTHER.isQuery());
        assertFalse(SqlTypeEnum.OTHER.isDml());
    }

    @Test
    @DisplayName("验证 SqlTypeEnum.of(code) 安全匹配与兜底降级")
    void shouldMatchSqlTypeEnumByCodeSafely() {
        assertEquals(SqlTypeEnum.SELECT, SqlTypeEnum.of("SELECT"));
        assertEquals(SqlTypeEnum.SELECT, SqlTypeEnum.of("select"));
        assertEquals(SqlTypeEnum.INSERT, SqlTypeEnum.of("INSERT"));
        assertEquals(SqlTypeEnum.UPDATE, SqlTypeEnum.of("UPDATE"));
        assertEquals(SqlTypeEnum.DELETE, SqlTypeEnum.of("delete"));
        assertEquals(SqlTypeEnum.OTHER, SqlTypeEnum.of("OTHER"));

        // 越界与空值安全兜底为 OTHER
        assertEquals(SqlTypeEnum.OTHER, SqlTypeEnum.of(null));
        assertEquals(SqlTypeEnum.OTHER, SqlTypeEnum.of(""));
        assertEquals(SqlTypeEnum.OTHER, SqlTypeEnum.of("   "));
        assertEquals(SqlTypeEnum.OTHER, SqlTypeEnum.of("UNKNOWN_SQL_TYPE"));
    }

    @Test
    @DisplayName("验证 SqlTypeEnum.fromValue 安全反序列化与多源解析")
    void shouldParseSqlTypeFromVariousValues() {
        assertEquals(SqlTypeEnum.OTHER, SqlTypeEnum.fromValue(null));
        assertEquals(SqlTypeEnum.INSERT, SqlTypeEnum.fromValue("INSERT"));
        assertEquals(SqlTypeEnum.UPDATE, SqlTypeEnum.fromValue("update"));
        assertEquals(SqlTypeEnum.DELETE, SqlTypeEnum.fromValue("DELETE"));
        assertEquals(SqlTypeEnum.SELECT, SqlTypeEnum.fromValue("select"));
        assertEquals(SqlTypeEnum.OTHER, SqlTypeEnum.fromValue("INVALID"));
    }

    @Test
    @DisplayName("验证 SqlLogModel 的 Builder 构造、全量字段访问与默认值")
    void shouldBuildAndAccessAllSqlLogModelFields() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> extra = new HashMap<>();
        extra.put("clusterId", "c-1001");

        SqlLogModel model = SqlLogModel.builder()
                .traceId("trace-123456")
                .tenantId("tenant-001")
                .userId("10086")
                .userName("admin")
                .datasource("master")
                .statementId("io.github.atengk.mapper.UserMapper.insert")
                .sql("INSERT INTO sys_user (id, name) VALUES (1, 'ateng')")
                .sqlType(SqlTypeEnum.INSERT)
                .costTime(12L)
                .affectedRows(1L)
                .status(0)
                .errorMsg(null)
                .callLocation("UserServiceImpl.java:42")
                .operateTime(now)
                .extra(extra)
                .build();

        assertEquals("trace-123456", model.getTraceId());
        assertEquals("tenant-001", model.getTenantId());
        assertEquals("10086", model.getUserId());
        assertEquals("admin", model.getUserName());
        assertEquals("master", model.getDatasource());
        assertEquals("io.github.atengk.mapper.UserMapper.insert", model.getStatementId());
        assertEquals("INSERT INTO sys_user (id, name) VALUES (1, 'ateng')", model.getSql());
        assertEquals(SqlTypeEnum.INSERT, model.getSqlType());
        assertEquals(12L, model.getCostTime());
        assertEquals(1L, model.getAffectedRows());
        assertEquals(0, model.getStatus());
        assertNull(model.getErrorMsg());
        assertEquals("UserServiceImpl.java:42", model.getCallLocation());
        assertEquals(now, model.getOperateTime());
        assertEquals("c-1001", model.getExtra().get("clusterId"));
        assertTrue(model.isSuccess());
    }

    @Test
    @DisplayName("验证 isSuccess 状态判定与失败现场")
    void shouldDetermineSuccessStatusCorrectly() {
        SqlLogModel successModel = SqlLogModel.builder().status(0).build();
        assertTrue(successModel.isSuccess());

        SqlLogModel failModel = SqlLogModel.builder()
                .status(1)
                .errorMsg("DuplicateKeyException: Duplicate entry '1' for key 'PRIMARY'")
                .build();
        assertFalse(failModel.isSuccess());
        assertNotNull(failModel.getErrorMsg());

        SqlLogModel nullStatusModel = new SqlLogModel();
        assertFalse(nullStatusModel.isSuccess());
    }

    @Test
    @DisplayName("验证 addExtra 便捷追加扩展属性")
    void shouldAddExtraPropertiesConveniently() {
        SqlLogModel model = new SqlLogModel();
        assertNull(model.getExtra());

        model.addExtra("env", "prod")
                .addExtra("retryCount", 2);

        assertNotNull(model.getExtra());
        assertEquals(2, model.getExtra().size());
        assertEquals("prod", model.getExtra().get("env"));
        assertEquals(2, model.getExtra().get("retryCount"));
    }

    @Test
    @DisplayName("验证 SqlLogModel 的 JSON 序列化与反序列化全要素自洽")
    void shouldSerializeAndDeserializeJsonFidelity() {
        LocalDateTime time = LocalDateTime.of(2026, 11, 6, 16, 50, 0);
        SqlLogModel origin = SqlLogModel.builder()
                .traceId("trace-789")
                .tenantId("tenant-888")
                .userId("user-01")
                .userName("tester")
                .datasource("slave_1")
                .statementId("io.github.atengk.mapper.UserMapper.selectById")
                .sql("SELECT id, name FROM sys_user WHERE id = 1")
                .sqlType(SqlTypeEnum.SELECT)
                .costTime(5L)
                .affectedRows(1L)
                .status(0)
                .callLocation("UserServiceImpl.java:100")
                .operateTime(time)
                .build();
        origin.addExtra("table", "sys_user");

        String json = JsonUtil.toJsonString(origin);
        assertNotNull(json);
        assertTrue(json.contains("\"sqlType\":\"SELECT\""));
        assertTrue(json.contains("\"datasource\":\"slave_1\""));

        SqlLogModel restored = JsonUtil.parseObject(json, SqlLogModel.class);
        assertNotNull(restored);
        assertEquals(origin.getTraceId(), restored.getTraceId());
        assertEquals(origin.getTenantId(), restored.getTenantId());
        assertEquals(origin.getUserId(), restored.getUserId());
        assertEquals(origin.getUserName(), restored.getUserName());
        assertEquals(origin.getDatasource(), restored.getDatasource());
        assertEquals(origin.getStatementId(), restored.getStatementId());
        assertEquals(origin.getSql(), restored.getSql());
        assertEquals(origin.getSqlType(), restored.getSqlType());
        assertEquals(origin.getCostTime(), restored.getCostTime());
        assertEquals(origin.getAffectedRows(), restored.getAffectedRows());
        assertEquals(origin.getStatus(), restored.getStatus());
        assertEquals(origin.getCallLocation(), restored.getCallLocation());
        assertEquals(origin.getOperateTime(), restored.getOperateTime());
        assertEquals("sys_user", restored.getExtra().get("table"));
        assertTrue(restored.isSuccess());
    }

    @Test
    @DisplayName("验证 SqlLogModel 反序列化兼容未知属性 (ignoreUnknown = true)")
    void shouldIgnoreUnknownPropertiesOnDeserialization() {
        String json = """
                {
                    "traceId": "trace-unknown",
                    "status": 0,
                    "sqlType": "UPDATE",
                    "unexpectedField": "some-junk-value",
                    "anotherUnknownObject": {"foo": "bar"}
                }
                """;

        SqlLogModel model = JsonUtil.parseObject(json, SqlLogModel.class);
        assertNotNull(model);
        assertEquals("trace-unknown", model.getTraceId());
        assertEquals(0, model.getStatus());
        assertEquals(SqlTypeEnum.UPDATE, model.getSqlType());
        assertTrue(model.isSuccess());
    }
}

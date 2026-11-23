package io.github.atengk.boot.core.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.audit.OperateLogModel;
import io.github.atengk.boot.core.audit.SqlLogModel;
import io.github.atengk.boot.core.id.SnowflakeIdInfo;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.model.ThreadPoolStatus;
import io.github.atengk.boot.core.util.JsonUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数值保真长整型序列化器及核心契约模型测试
 *
 * @author Ateng
 * @since 2026-11-22
 */
@DisplayName("NumericLongSerializer 与核心模型数值保真测试")
class NumericLongSerializerTest {

    record DemoRecord(Long id, String name) {
    }

    @Test
    @DisplayName("longToString 开启时 Result 与 PageResult 契约数值字段保持 JSON 数字，业务 ID 维持字符串")
    void shouldKeepResultAndPageResultNumericWhenLongToStringEnabled() throws Exception {
        ObjectMapper longToStringMapper = JsonUtil.createLongToStringObjectMapper();

        PageResult<DemoRecord> page = PageResult.of(
                List.of(new DemoRecord(1846123456789012345L, "item-1")),
                25L,
                1L,
                10L
        );
        Result<PageResult<DemoRecord>> result = Result.success(page);

        JsonNode root = longToStringMapper.readTree(longToStringMapper.writeValueAsString(result));
        assertTrue(root.get("timestamp").isNumber(), "Result.timestamp 必须为 JSON number");

        JsonNode data = root.get("data");
        assertTrue(data.get("total").isNumber(), "PageResult.total 必须为 JSON number");
        assertTrue(data.get("size").isNumber(), "PageResult.size 必须为 JSON number");
        assertTrue(data.get("current").isNumber(), "PageResult.current 必须为 JSON number");
        assertTrue(data.get("pages").isNumber(), "PageResult.pages 必须为 JSON number");
        assertEquals(25L, data.get("total").asLong());
        assertEquals(1L, data.get("current").asLong());
        assertEquals(10L, data.get("size").asLong());
        assertEquals(3L, data.get("pages").asLong());

        JsonNode recordNode = data.get("records").get(0);
        assertTrue(recordNode.get("id").isTextual(), "业务雪花 ID 必须保持 JSON string");
        assertEquals("1846123456789012345", recordNode.get("id").asText());
    }

    @Test
    @DisplayName("longToString 开启时 PageQuery、审计模型、线程池快照及雪花元数据非主键字段保持 JSON 数字")
    void shouldKeepCoreMetadataFieldsNumericWhilePreservingSnowflakeIdString() throws Exception {
        ObjectMapper longToStringMapper = JsonUtil.createLongToStringObjectMapper();

        PageQuery query = new PageQuery(2L, 20L);
        JsonNode queryNode = longToStringMapper.readTree(longToStringMapper.writeValueAsString(query));
        assertTrue(queryNode.get("current").isNumber());
        assertTrue(queryNode.get("size").isNumber());

        OperateLogModel operateLog = OperateLogModel.builder().costTime(38L).build();
        JsonNode opNode = longToStringMapper.readTree(longToStringMapper.writeValueAsString(operateLog));
        assertTrue(opNode.get("costTime").isNumber());
        assertEquals(38L, opNode.get("costTime").asLong());

        SqlLogModel sqlLog = SqlLogModel.builder().costTime(12L).affectedRows(5L).build();
        JsonNode sqlNode = longToStringMapper.readTree(longToStringMapper.writeValueAsString(sqlLog));
        assertTrue(sqlNode.get("costTime").isNumber());
        assertTrue(sqlNode.get("affectedRows").isNumber());

        ThreadPoolStatus poolStatus = new ThreadPoolStatus(4, 8, 4, 1, 0, 100, 1024L, false);
        JsonNode poolNode = longToStringMapper.readTree(longToStringMapper.writeValueAsString(poolStatus));
        assertTrue(poolNode.get("completedTaskCount").isNumber());
        assertEquals(1024L, poolNode.get("completedTaskCount").asLong());

        SnowflakeIdInfo idInfo = new SnowflakeIdInfo(
                1846123456789012345L,
                1761000000000L,
                Instant.ofEpochMilli(1761000000000L),
                LocalDateTime.of(2025, 10, 21, 0, 0),
                1L,
                2L,
                15L,
                1700000000000L
        );
        JsonNode idNode = longToStringMapper.readTree(longToStringMapper.writeValueAsString(idInfo));
        assertTrue(idNode.get("id").isTextual(), "SnowflakeIdInfo.id 必须维持 JSON string");
        assertTrue(idNode.get("timestamp").isNumber());
        assertTrue(idNode.get("datacenterId").isNumber());
        assertTrue(idNode.get("workerId").isNumber());
        assertTrue(idNode.get("sequence").isNumber());
        assertTrue(idNode.get("epoch").isNumber());
    }
}

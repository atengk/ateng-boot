package io.github.atengk.boot.mybatisplus.sqllog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.audit.SqlLogModel;
import io.github.atengk.boot.core.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认物理 SQL 操作审计日志存储提供者
 * 基于 SLF4J 输出标准 JSON 格式的 SQL 审计日志，作为未配置外部持久化设施时的开箱即用保底实现。
 *
 * @author Ateng
 * @since 2026-11-06
 */
public class DefaultSqlLogStorageProvider implements SqlLogStorageProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultSqlLogStorageProvider.class);

    private final ObjectMapper objectMapper;

    public DefaultSqlLogStorageProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : JsonUtil.getObjectMapper();
    }

    public DefaultSqlLogStorageProvider() {
        this(JsonUtil.getObjectMapper());
    }

    @Override
    public void store(SqlLogModel logModel) {
        if (logModel == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(logModel);
            log.info("[SQL_LOG] {}", json);
        } catch (Exception e) {
            log.warn("[SQL_LOG] 物理 SQL 审计记录序列化失败: traceId={}, statementId={}, error={}",
                    logModel.getTraceId(), logModel.getStatementId(), e.getMessage());
        }
    }
}

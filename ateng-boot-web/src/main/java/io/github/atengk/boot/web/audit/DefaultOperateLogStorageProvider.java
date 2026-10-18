package io.github.atengk.boot.web.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.audit.OperateLogModel;
import io.github.atengk.boot.core.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认操作审计日志存储提供者
 * 基于 SLF4J 输出标准 JSON 格式的审计日志，作为无外部持久化设施时的开箱即用保底实现
 *
 * @author Ateng
 * @since 2026-10-18
 */
public class DefaultOperateLogStorageProvider implements OperateLogStorageProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultOperateLogStorageProvider.class);

    private final ObjectMapper objectMapper;

    public DefaultOperateLogStorageProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : JsonUtil.getObjectMapper();
    }

    public DefaultOperateLogStorageProvider() {
        this(JsonUtil.getObjectMapper());
    }

    @Override
    public void store(OperateLogModel logModel) {
        if (logModel == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(logModel);
            log.info("[OPERATE_LOG] {}", json);
        } catch (Exception e) {
            log.warn("[OPERATE_LOG] 审计记录序列化失败: traceId={}, module={}, error={}",
                    logModel.getTraceId(), logModel.getModule(), e.getMessage());
        }
    }
}

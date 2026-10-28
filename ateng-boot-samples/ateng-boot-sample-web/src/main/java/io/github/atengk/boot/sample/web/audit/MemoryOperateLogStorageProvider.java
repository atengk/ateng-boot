package io.github.atengk.boot.sample.web.audit;

import io.github.atengk.boot.core.audit.OperateLogModel;
import io.github.atengk.boot.web.audit.OperateLogStorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存操作审计存储提供者实现
 * 接管 OperateLogStorageProvider SPI，在内存中留存审计模型以供场景验证与断言
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Slf4j
@Component
public class MemoryOperateLogStorageProvider implements OperateLogStorageProvider {

    private final List<OperateLogModel> storage = new CopyOnWriteArrayList<>();

    @Override
    public void store(OperateLogModel logModel) {
        if (logModel == null) {
            return;
        }
        storage.add(logModel);
        log.info("[MemoryOperateLogStorageProvider] 接收并存储操作审计: traceId={}, module={}, type={}, status={}",
                logModel.getTraceId(), logModel.getModule(), logModel.getOperateType(), logModel.getStatus());
    }

    /**
     * 获取所有留存的操作审计模型副本
     *
     * @return 不可变审计模型列表
     */
    public List<OperateLogModel> getAllLogs() {
        return Collections.unmodifiableList(new ArrayList<>(storage));
    }

    /**
     * 获取最新一条操作审计模型
     *
     * @return 最新审计模型，无记录时返回 null
     */
    public OperateLogModel getLastLog() {
        if (storage.isEmpty()) {
            return null;
        }
        return storage.get(storage.size() - 1);
    }

    /**
     * 清理内存存储队列
     */
    public void clear() {
        storage.clear();
    }

    /**
     * 当前记录条数
     *
     * @return 记录数
     */
    public int size() {
        return storage.size();
    }
}

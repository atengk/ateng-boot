package io.github.atengk.boot.job.local.storage;

import io.github.atengk.boot.job.local.enums.JobStatus;
import io.github.atengk.boot.job.local.enums.TriggerType;
import io.github.atengk.boot.job.local.model.JobDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于内存的受管任务元数据存储默认实现
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class InMemoryJobMetadataRepository implements JobMetadataRepository {

    private final ConcurrentMap<String, JobDefinition> store = new ConcurrentHashMap<>();

    @Override
    public void save(JobDefinition definition) {
        if (definition != null && definition.getJobName() != null) {
            store.put(definition.getJobName(), definition);
        }
    }

    @Override
    public Optional<JobDefinition> findByJobName(String jobName) {
        if (jobName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(jobName));
    }

    @Override
    public boolean updateStatus(String jobName, JobStatus status) {
        if (jobName == null || status == null) {
            return false;
        }
        JobDefinition def = store.get(jobName);
        if (def != null) {
            def.setStatus(status);
            def.setEnabled(status == JobStatus.RUNNING);
            return true;
        }
        return false;
    }

    @Override
    public boolean updateCron(String jobName, String newCron) {
        if (jobName == null || newCron == null || newCron.isBlank()) {
            return false;
        }
        JobDefinition def = store.get(jobName);
        if (def != null) {
            def.setCron(newCron.trim());
            def.setTriggerType(TriggerType.CRON);
            return true;
        }
        return false;
    }

    @Override
    public List<JobDefinition> findAll() {
        return Collections.unmodifiableList(new ArrayList<>(store.values()));
    }

    @Override
    public boolean delete(String jobName) {
        if (jobName == null) {
            return false;
        }
        return store.remove(jobName) != null;
    }

    /**
     * 清理所有已存储的任务元数据（用于测试重置）
     */
    public void clear() {
        store.clear();
    }
}

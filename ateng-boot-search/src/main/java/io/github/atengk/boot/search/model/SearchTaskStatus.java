package io.github.atengk.boot.search.model;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 强类型不可变搜索引擎异步后台任务状态模型 (Reindex / UpdateByQuery / DeleteByQuery)
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Value
@Builder
@Jacksonized
public class SearchTaskStatus implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务唯一标识（格式如 {@code nodeId:taskId}）
     */
    String taskId;

    /**
     * 任务是否已结束（成功或异常终止均为 true）
     */
    boolean completed;

    /**
     * 任务是否已被人工或系统取消
     */
    boolean cancelled;

    /**
     * 任务描述信息（例如 {@code reindex from [v1] to [v2]}）
     */
    String description;

    /**
     * 任务累计耗时（纳秒）
     */
    long runningTimeNanos;

    /**
     * 预计处理文档总数
     */
    long total;

    /**
     * 已新建文档数
     */
    long created;

    /**
     * 已更新文档数
     */
    long updated;

    /**
     * 已删除文档数
     */
    long deleted;

    /**
     * 版本冲突数
     */
    long versionConflicts;

    /**
     * 任务执行失败详情列表（保底返回不可变空集合）
     */
    @Builder.Default
    List<String> failures = Collections.emptyList();

    /**
     * 若任务因致命错误终止，记录错误原因描述
     */
    String errorReason;

    /**
     * 获取任务失败详情列表（保底返回不可变空集合）
     *
     * @return 失败详情列表
     */
    public List<String> getFailures() {
        return failures != null ? Collections.unmodifiableList(failures) : Collections.emptyList();
    }
}

package io.github.atengk.boot.sample.job.local.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * Cron 表达式未来触发时钟点预演视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CronPreviewVO implements Serializable {

    /**
     * 校验的 Cron 表达式
     */
    private String cron;

    /**
     * 是否为合法 Cron 语法
     */
    private boolean valid;

    /**
     * 未来预期触发的时钟点列表
     */
    private List<Instant> nextExecutionTimes;
}

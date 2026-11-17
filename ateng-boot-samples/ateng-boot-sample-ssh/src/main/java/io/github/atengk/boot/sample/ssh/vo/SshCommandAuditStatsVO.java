package io.github.atengk.boot.sample.ssh.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 自定义命令审计拦截器统计视图对象 (VO)
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshCommandAuditStatsVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 累计审计放行并执行完成的命令次数
     */
    private long executedCount;

    /**
     * 累计审计捕获的异常或被安全策略阻断次数
     */
    private long errorCount;
}

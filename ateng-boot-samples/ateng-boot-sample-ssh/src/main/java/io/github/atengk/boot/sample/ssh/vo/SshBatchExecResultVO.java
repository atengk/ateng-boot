package io.github.atengk.boot.sample.ssh.vo;

import io.github.atengk.boot.ssh.model.SshBatchExecResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 集群多节点批量巡检聚合回执 VO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshBatchExecResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 下发巡检的总节点数
     */
    private Integer totalCount;

    /**
     * 巡检成功的节点数
     */
    private Integer successCount;

    /**
     * 巡检失败的节点数
     */
    private Integer failedCount;

    /**
     * 是否全量节点执行成功
     */
    private Boolean allSuccess;

    /**
     * 批量巡检总耗时毫秒数
     */
    private Long durationMs;

    /**
     * 各节点的详细执行结果字典 (key: 节点标识)
     */
    @Builder.Default
    private Map<String, SshExecResultVO> details = new LinkedHashMap<>();

    /**
     * 执行失败的节点名称列表
     */
    @Builder.Default
    private List<String> failedServers = Collections.emptyList();

    /**
     * 从底层实体转换为对外契约 VO
     *
     * @param batchResult 底层批量巡检聚合载荷
     * @return 业务 VO
     */
    public static SshBatchExecResultVO from(SshBatchExecResult batchResult) {
        if (batchResult == null) {
            return null;
        }
        Map<String, SshExecResultVO> detailMap = new LinkedHashMap<>();
        if (batchResult.getNamedResults() != null) {
            batchResult.getNamedResults().forEach((k, v) -> detailMap.put(k, SshExecResultVO.from(v)));
        } else if (batchResult.getResults() != null) {
            batchResult.getResults().forEach((k, v) -> detailMap.put(k.toSessionKey(), SshExecResultVO.from(v)));
        }
        List<String> failedList = new java.util.ArrayList<>();
        if (batchResult.getFailedServers() != null) {
            batchResult.getFailedServers().forEach(s -> failedList.add(s.toSessionKey()));
        }
        return SshBatchExecResultVO.builder()
                .totalCount(batchResult.getTotalCount())
                .successCount(batchResult.getSuccessCount())
                .failedCount(batchResult.getFailureCount())
                .allSuccess(batchResult.isAllSuccess())
                .durationMs(batchResult.getDuration() != null ? batchResult.getDuration().toMillis() : 0L)
                .details(detailMap)
                .failedServers(failedList)
                .build();
    }
}

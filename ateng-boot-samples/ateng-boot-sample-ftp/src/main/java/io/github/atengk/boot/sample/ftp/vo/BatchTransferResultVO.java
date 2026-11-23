package io.github.atengk.boot.sample.ftp.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量传输强类型聚合回执 VO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchTransferResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 发现的总文件数
     */
    private Integer totalFiles;

    /**
     * 成功传输的文件数
     */
    private Integer successCount;

    /**
     * 传输失败的文件数
     */
    private Integer failureCount;

    /**
     * 跳过的文件数（被过滤或系统隐性文件）
     */
    private Integer skippedCount;

    /**
     * 实际传输的总字节数
     */
    private Long totalBytesTransferred;

    /**
     * 批处理总耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 是否全量成功（零失败）
     */
    private Boolean allSuccess;

    /**
     * 失败条目清单
     */
    @Builder.Default
    private List<TransferFailureItemVO> failures = new ArrayList<>();
}

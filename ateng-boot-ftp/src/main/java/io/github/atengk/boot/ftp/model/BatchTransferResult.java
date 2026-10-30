package io.github.atengk.boot.ftp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * FTP 批量目录传输强类型聚合回执
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchTransferResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 扫描到的待传输文件总数（包含成功、失败与跳过的文件数）
     */
    private int totalFiles;

    /**
     * 成功传输的文件数
     */
    private int successCount;

    /**
     * 传输失败的文件数
     */
    private int failureCount;

    /**
     * 过滤跳过的文件数（如隐藏系统文件或自定义谓词排除）
     */
    private int skippedCount;

    /**
     * 累计成功传输的数据总字节量
     */
    private long transferredBytes;

    /**
     * 整个批量传输过程总耗时
     */
    private Duration duration;

    /**
     * 传输失败明细列表（保底返回非空列表）
     */
    @Builder.Default
    private List<TransferFailureItem> failures = new ArrayList<>();

    /**
     * 获取失败明细列表（空安全保底，绝不返回 null）
     *
     * @return 失败明细列表
     */
    public List<TransferFailureItem> getFailures() {
        return failures != null ? failures : Collections.emptyList();
    }

    /**
     * 判断批次是否完全成功（无任何失败）
     *
     * @return 无失败时返回 true
     */
    public boolean isAllSuccess() {
        return failureCount == 0;
    }

    /**
     * 判断批次是否存在传输失败
     *
     * @return 存在失败时返回 true
     */
    public boolean hasFailure() {
        return failureCount > 0;
    }

    /**
     * 追加单文件失败记录并自动累加失败计数
     *
     * @param failure 失败明细
     */
    public void addFailure(TransferFailureItem failure) {
        if (this.failures == null) {
            this.failures = new ArrayList<>();
        }
        if (failure != null) {
            this.failures.add(failure);
        }
        this.failureCount++;
    }
}

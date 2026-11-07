package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 慢方法告警与受管安全文件治理响应视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlowAlertAndFileVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务业务标识
     */
    private String taskId;

    /**
     * @SlowAlert 慢方法实际执行耗时（毫秒）
     */
    private Long slowMethodCostMillis;

    /**
     * 原始输入文件名（可能包含路径穿越字符）
     */
    private String originalFilename;

    /**
     * 经 FileUtil.cleanFilename 安全清洗后的纯净文件名
     */
    private String cleanedFilename;

    /**
     * 受管临时文件实际读取的内容
     */
    private String readContent;

    /**
     * 有界安全流式读取的字节数
     */
    private Integer readByteCount;

    /**
     * 受管临时目录清理的文件数量（验证确定性清理零残留）
     */
    private Integer cleanedFileCount;

    /**
     * 临时文件在清理前是否存在
     */
    private Boolean fileExistedBeforeClean;

    /**
     * 临时文件在清理后是否已被物理销毁
     */
    private Boolean fileDestroyedAfterClean;

    /**
     * StopWatch 秒表全链路总耗时（毫秒）
     */
    private Long totalCostMillis;

    /**
     * StopWatch 秒表多分段可视化报告
     */
    private String stopWatchReport;
}

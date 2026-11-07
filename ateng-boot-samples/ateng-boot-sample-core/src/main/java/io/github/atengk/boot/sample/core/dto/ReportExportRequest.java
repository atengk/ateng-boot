package io.github.atengk.boot.sample.core.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 多业务线程池报文导出请求对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportExportRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导出的报表业务名称
     */
    @NotBlank(message = "报表名称不能为空")
    private String reportName;

    /**
     * 输出文件格式 (EXCEL / PDF / CSV)
     */
    private String format;
}

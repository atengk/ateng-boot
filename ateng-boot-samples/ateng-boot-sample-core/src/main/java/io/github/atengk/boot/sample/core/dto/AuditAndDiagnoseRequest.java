package io.github.atengk.boot.sample.core.dto;

import io.github.atengk.boot.core.audit.OperateTypeEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 结构化操作/SQL 审计与异常诊断请求传输对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditAndDiagnoseRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务功能模块名称
     */
    @NotBlank(message = "业务功能模块不能为空")
    private String module;

    /**
     * 操作动作类型
     */
    @NotNull(message = "操作动作类型不能为空")
    private OperateTypeEnum operateType;

    /**
     * 操作业务说明描述
     */
    @NotBlank(message = "操作描述不能为空")
    private String description;

    /**
     * 待审计的物理 SQL 语句
     */
    @NotBlank(message = "待审计 SQL 语句不能为空")
    private String sql;

    /**
     * 模拟深层根因异常错误提示文本
     */
    @NotBlank(message = "异常测试文本不能为空")
    private String errorMessage;
}

package io.github.atengk.boot.sample.storage.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 企业电子合同归档审计快照载荷模型
 * 用于演示对象存储 JSON 序列化与反序列化便捷存取契约
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractAuditSnapshot {

    /**
     * 合同唯一编号（如 HT-2026-001）
     */
    @NotBlank(message = "合同编号不能为空")
    private String contractNo;

    /**
     * 合同名称（如 2026年度核心云基础设施采购合同）
     */
    @NotBlank(message = "合同标题不能为空")
    private String title;

    /**
     * 签署负责人姓名
     */
    @NotBlank(message = "签署负责人不能为空")
    private String signer;

    /**
     * 归属业务部门（如 finance / legal / procurement）
     */
    @NotBlank(message = "归属部门不能为空")
    private String department;

    /**
     * 归档审计状态（如 ARCHIVED / VERIFIED）
     */
    private String auditStatus;
}

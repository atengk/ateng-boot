package io.github.atengk.boot.sample.document.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 劳动合同模板渲染请求传输对象
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractRenderRequest {

    /**
     * 劳动者姓名
     */
    @NotBlank(message = "劳动者姓名不能为空")
    private String employeeName;

    /**
     * 身份证号码
     */
    @NotBlank(message = "身份证号码不能为空")
    private String idCard;

    /**
     * 任职部门
     */
    @NotBlank(message = "任职部门不能为空")
    private String department;

    /**
     * 工作岗位
     */
    @NotBlank(message = "工作岗位不能为空")
    private String postName;

    /**
     * 月度综合薪资
     */
    @NotBlank(message = "月度综合薪资不能为空")
    private String salary;

    /**
     * 签署生效日期
     */
    @NotBlank(message = "签署生效日期不能为空")
    private String signDate;

    /**
     * 负责与参与项目经验列表
     */
    private List<ProjectExperience> items;
}

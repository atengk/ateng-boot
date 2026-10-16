package io.github.atengk.boot.excel.model;

import io.github.atengk.boot.excel.annotation.ExcelMerge;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serializable;

/**
 * 单元测试模型：用于测试 @ExcelMerge 单元格纵向合并
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MergeUserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ExcelMerge
    @ExcelProperty(value = "部门", index = 0)
    private String department;

    @ExcelMerge(dependParent = true)
    @ExcelProperty(value = "岗位", index = 1)
    private String role;

    @ExcelProperty(value = "姓名", index = 2)
    private String name;

    @ExcelProperty(value = "薪资", index = 3)
    private Double salary;
}

package io.github.atengk.boot.sample.excel.service;

import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.sample.excel.fixture.SampleDataFixture;
import io.github.atengk.boot.sample.excel.vo.DepartmentUserMergeVO;
import io.github.atengk.boot.sample.excel.vo.I18nUserExcelVO;
import io.github.atengk.boot.sample.excel.vo.UserExcelVO;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 业务场景 6：动态列投影与多维视图渲染场景服务
 * <p>
 * 覆盖强类型 VO 动态列白名单/黑名单裁剪、无实体二维矩阵流式导出、纵向相同值单元格自动合并、多语言国际化表头以及防呆下拉导入模板。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Service
public class DynamicColumnProjectionScenarioService {

    /**
     * 强类型 VO 动态列投影裁剪导出（白名单包含模式）
     *
     * @param out            目标输出流
     * @param includeColumns 动态包含的实体字段名称列表（有序）
     */
    public void exportWithColumnInclude(OutputStream out, Collection<String> includeColumns) {
        List<UserExcelVO> userList = getMockUserList();
        ExcelUtil.export(out, "动态包含列用户清单", userList, UserExcelVO.class, includeColumns, null);
    }

    /**
     * 强类型 VO 动态列投影裁剪导出（黑名单排除模式）
     *
     * @param out            目标输出流
     * @param excludeColumns 动态排除的实体字段名称集合
     */
    public void exportWithColumnExclude(OutputStream out, Collection<String> excludeColumns) {
        List<UserExcelVO> userList = getMockUserList();
        ExcelUtil.export(out, "动态排除列用户清单", userList, UserExcelVO.class, null, excludeColumns);
    }

    /**
     * 无实体动态流式导出（支持任意表头层级与行数据二维矩阵）
     *
     * @param out      目标输出流
     * @param headList 动态表头结构二维列表
     * @param dataList 单元格数据二维矩阵
     */
    public void exportDynamic(OutputStream out, List<List<String>> headList, List<List<Object>> dataList) {
        ExcelUtil.exportDynamic(out, "动态二维报表", headList, dataList);
    }

    /**
     * 流式导出多语言国际化表头用户报表
     * 演示根据系统上下文 Locale 自动解析 @ExcelProperty 中的 i18n 资源键值
     *
     * @param out 目标输出流
     */
    public void exportI18nUsers(OutputStream out) {
        List<I18nUserExcelVO> list = List.of(
                new I18nUserExcelVO(101L, "Alice", "alice@example.com", "研发部"),
                new I18nUserExcelVO(102L, "Bob", "bob@example.com", "市场部")
        );
        ExcelUtil.export(out, list, I18nUserExcelVO.class);
    }

    /**
     * 导出带有防呆下拉选单与表头批注指南的标准化导入模板
     * 演示 @ExcelDropdown 静态/动态下拉列表注入与 @ExcelPrompt 批注提示
     *
     * @param out 目标输出流
     */
    public void exportDropdownTemplate(OutputStream out) {
        ExcelUtil.exportTemplate(out, UserExcelVO.class);
    }

    /**
     * 纵向相邻相同值单元格自动合并流式导出
     * 演示 @ExcelMerge 作用于部门列实现相邻单元格无缝合并
     *
     * @param out 目标输出流
     */
    public void exportMerge(OutputStream out) {
        List<DepartmentUserMergeVO> list = getMockDepartmentMergeList();
        ExcelUtil.export(out, list, DepartmentUserMergeVO.class);
    }

    /**
     * 获取标准测试用户数据
     *
     * @return 用户列表
     */
    public List<UserExcelVO> getMockUserList() {
        return SampleDataFixture.createMockUserList();
    }

    /**
     * 获取部门员工合并测试数据
     *
     * @return 部门员工列表
     */
    public List<DepartmentUserMergeVO> getMockDepartmentMergeList() {
        return SampleDataFixture.createMockDepartmentMergeList();
    }
}

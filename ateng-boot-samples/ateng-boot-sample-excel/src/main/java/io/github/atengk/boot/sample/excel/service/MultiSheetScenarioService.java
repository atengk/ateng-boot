package io.github.atengk.boot.sample.excel.service;

import io.github.atengk.boot.excel.builder.ExcelBuilder;
import io.github.atengk.boot.excel.enums.ExcelThemeEnum;
import io.github.atengk.boot.excel.model.ExcelMultiSheetResult;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.sample.excel.fixture.SampleDataFixture;
import io.github.atengk.boot.sample.excel.vo.DepartmentUserMergeVO;
import io.github.atengk.boot.sample.excel.vo.UserExcelVO;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 业务场景 2：异构多工作表流式编排场景服务
 * <p>
 * 演示使用 {@link ExcelBuilder} 链式导出异构多 Sheet（混合实体模型、单元格合并与动态表头），
 * 以及使用 {@link io.github.atengk.boot.excel.builder.ExcelReaderBuilder} 按 Sheet 绑定模型执行流式全量读取。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Service
public class MultiSheetScenarioService {

    /**
     * 导出包含 3 个异构 Sheet 的复合 Excel 工作簿
     *
     * @param out 目标输出流
     */
    public void exportHeterogeneousMultiSheet(OutputStream out) {
        List<UserExcelVO> userList = getMockUserList();
        List<DepartmentUserMergeVO> mergeList = getMockDepartmentMergeList();
        List<List<String>> dynamicHeaders = List.of(
                List.of("流水号"),
                List.of("交易金额"),
                List.of("交易状态")
        );
        List<List<Object>> dynamicRows = List.of(
                List.of("TXN_2026001", 998.00, "已支付"),
                List.of("TXN_2026002", 1250.50, "处理中")
        );

        try (ExcelBuilder builder = ExcelUtil.builder(out)) {
            // Sheet 1: 用户清单（商务蓝主题 + 斑马纹 + 自动列宽）
            builder.theme(ExcelThemeEnum.BUSINESS_BLUE)
                    .zebraStripe(true)
                    .autoColumnWidth(12, 60)
                    .sheet("用户清单", UserExcelVO.class, userList)
                    // Sheet 2: 部门架构（极简纯白主题 + 纵向单元格合并）
                    .theme(ExcelThemeEnum.MINIMAL_CLEAN)
                    .sheet("部门架构", DepartmentUserMergeVO.class, mergeList)
                    // Sheet 3: 交易流水（无实体动态表头写出）
                    .sheetDynamic("交易流水", dynamicHeaders, dynamicRows);
        }
    }

    /**
     * 流式读取异构多工作表并按 Sheet 反向解析为强类型对象
     *
     * @param in 包含多工作表的 Excel 输入流
     * @return 多工作表解析聚合结果
     */
    public ExcelMultiSheetResult readHeterogeneousMultiSheet(InputStream in) {
        return ExcelUtil.reader(in)
                .sheet(0, UserExcelVO.class)
                .sheet(1, DepartmentUserMergeVO.class)
                .read();
    }

    public List<UserExcelVO> getMockUserList() {
        return SampleDataFixture.createMultiSheetUserList();
    }

    public List<DepartmentUserMergeVO> getMockDepartmentMergeList() {
        return SampleDataFixture.createMockDepartmentMergeList();
    }
}

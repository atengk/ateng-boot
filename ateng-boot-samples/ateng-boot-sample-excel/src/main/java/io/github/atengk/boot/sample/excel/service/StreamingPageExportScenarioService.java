package io.github.atengk.boot.sample.excel.service;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.excel.streaming.StreamingPageExportTemplate;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.sample.excel.enums.SampleGenderEnum;
import io.github.atengk.boot.sample.excel.vo.UserExcelVO;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 业务场景 1：海量数据流式分页导出场景服务
 * <p>
 * 演示恒定低内存游标分页驱动流式写出，并在单工作表数据达到配置上限时自动无缝跨 Sheet 轮转分割。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Service
public class StreamingPageExportScenarioService {

    /**
     * 基于主键游标驱动流式导出海量数据
     *
     * @param out             目标输出流
     * @param totalRows       预期导出的总数据量
     * @param maxRowsPerSheet 单个工作表最大行数上限（用于触发自动分表）
     * @return 实际导出的总记录数
     */
    public long exportBigDataByCursor(OutputStream out, int totalRows, int maxRowsPerSheet) {
        try (StreamingPageExportTemplate<UserExcelVO> template = ExcelUtil.streaming(out, UserExcelVO.class)) {
            template.baseSheetName("销售流水")
                    .maxRowsPerSheet(maxRowsPerSheet);

            return template.exportByCursor(
                    0L,
                    100,
                    (cursorId, limit) -> fetchUsersByCursor(cursorId, limit, totalRows),
                    UserExcelVO::getUserId
            );
        }
    }

    /**
     * 基于标准页码驱动流式分页导出
     *
     * @param out             目标输出流
     * @param totalRows       预期导出的总数据量
     * @param maxRowsPerSheet 单工作表上限
     * @return 实际导出的总记录数
     */
    public long exportBigDataByPage(OutputStream out, int totalRows, int maxRowsPerSheet) {
        try (StreamingPageExportTemplate<UserExcelVO> template = ExcelUtil.streaming(out, UserExcelVO.class)) {
            template.baseSheetName("分页报表")
                    .maxRowsPerSheet(maxRowsPerSheet);

            return template.exportByPage(pageNum -> fetchUsersByPage(pageNum, 100, totalRows));
        }
    }

    private List<UserExcelVO> fetchUsersByCursor(Long cursorId, int limit, int totalRows) {
        long startId = (cursorId != null ? cursorId : 0L) + 1;
        if (startId > totalRows) {
            return Collections.emptyList();
        }
        int fetchCount = (int) Math.min(limit, totalRows - startId + 1);
        List<UserExcelVO> list = new ArrayList<>(fetchCount);
        for (int i = 0; i < fetchCount; i++) {
            long id = startId + i;
            list.add(new UserExcelVO(
                    id,
                    "员工_" + id,
                    "138" + String.format("%08d", id),
                    "user" + id + "@example.com",
                    (id % 2 == 0) ? SampleGenderEnum.FEMALE : SampleGenderEnum.MALE,
                    (id % 3 == 0) ? "市场部" : "研发部",
                    "0"
            ));
        }
        return list;
    }

    private PageResult<UserExcelVO> fetchUsersByPage(int pageNum, int pageSize, int totalRows) {
        int offset = (pageNum - 1) * pageSize;
        if (offset >= totalRows) {
            return PageResult.of(Collections.emptyList(), totalRows, pageNum, pageSize);
        }
        int fetchCount = Math.min(pageSize, totalRows - offset);
        List<UserExcelVO> list = new ArrayList<>(fetchCount);
        for (int i = 0; i < fetchCount; i++) {
            long id = offset + i + 1;
            list.add(new UserExcelVO(
                    id,
                    "用户_" + id,
                    "139" + String.format("%08d", id),
                    "page" + id + "@example.com",
                    (id % 2 == 0) ? SampleGenderEnum.FEMALE : SampleGenderEnum.MALE,
                    "财务部",
                    "0"
            ));
        }
        return PageResult.of(list, totalRows, pageNum, pageSize);
    }
}

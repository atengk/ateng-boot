package io.github.atengk.boot.sample.excel.controller;

import io.github.atengk.boot.excel.builder.ExcelBuilder;
import io.github.atengk.boot.excel.enums.ExcelThemeEnum;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.excel.util.ExcelWebUtil;
import io.github.atengk.boot.sample.excel.enums.EmployeeStatusEnum;
import io.github.atengk.boot.sample.excel.enums.SampleGenderEnum;
import io.github.atengk.boot.sample.excel.vo.DepartmentAttendanceMergeVO;
import io.github.atengk.boot.sample.excel.vo.EmployeeRosterVO;
import io.github.atengk.boot.sample.excel.vo.I18nUserExcelVO;
import io.github.atengk.boot.web.annotation.WebLog;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 员工花名册导出控制器（HR 域示例）
 * <p>
 * 提供 ResponseEntity 异步流式写出、HttpServletResponse 同步写出、动态列投影、
 * 企业主题渲染、多语言国际化及纵向单元格合并能力。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-08
 */
@RestController
@RequestMapping("/sample/excel/roster")
public class EmployeeRosterController {

    /**
     * 端点 1：基于 ResponseEntity&lt;StreamingResponseBody&gt; 异步流式导出花名册（支持动态列裁剪）
     *
     * @param includeColumns 动态包含列白名单（可选）
     * @param excludeColumns 动态排除列黑名单（可选）
     * @param fileName       自定义导出文件名
     * @return 包含异步流的响应实体
     */
    @GetMapping("/export-response-entity")
    @WebLog(value = "花名册 ResponseEntity 异步流式导出", printParams = true, printResult = false, printCostTime = true)
    public ResponseEntity<StreamingResponseBody> exportByResponseEntity(
            @RequestParam(value = "includeColumns", required = false) List<String> includeColumns,
            @RequestParam(value = "excludeColumns", required = false) List<String> excludeColumns,
            @RequestParam(value = "fileName", defaultValue = "员工花名册_异步流式.xlsx") String fileName) {

        HttpHeaders headers = ExcelWebUtil.createDownloadHeaders(fileName);
        StreamingResponseBody body = outputStream -> {
            List<EmployeeRosterVO> data = createSampleRoster();
            ExcelUtil.export(outputStream, "员工花名册", data, EmployeeRosterVO.class, includeColumns, excludeColumns);
        };

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    /**
     * 端点 2：直接写入 HttpServletResponse 同步流式导出员工花名册（内置敏感数据脱敏与枚举转义）
     *
     * @param response HTTP Servlet 响应对象
     * @param fileName 导出文件名
     * @throws IOException IO 异常
     */
    @GetMapping("/export-servlet-response")
    @WebLog(value = "花名册 HttpServletResponse 同步流式导出", printParams = false, printResult = false, printCostTime = true)
    public void exportByServletResponse(
            HttpServletResponse response,
            @RequestParam(value = "fileName", defaultValue = "员工花名册_同步流式.xlsx") String fileName) throws IOException {
        List<EmployeeRosterVO> data = createSampleRoster();
        ExcelWebUtil.export(response, fileName, data, EmployeeRosterVO.class);
    }

    /**
     * 端点 3：应用企业级预设主题渲染导出员工花名册
     *
     * @param response HTTP Servlet 响应对象
     * @param theme    企业主题枚举（默认 BUSINESS_BLUE 商务蓝）
     * @param fileName 导出文件名
     * @throws IOException IO 异常
     */
    @GetMapping("/export-theme")
    @WebLog(value = "花名册主题渲染导出", printParams = true, printResult = false, printCostTime = true)
    public void exportByTheme(
            HttpServletResponse response,
            @RequestParam(value = "theme", required = false, defaultValue = "BUSINESS_BLUE") ExcelThemeEnum theme,
            @RequestParam(value = "fileName", defaultValue = "员工花名册_主题渲染.xlsx") String fileName) {
        List<EmployeeRosterVO> data = createSampleRoster();
        try (ExcelBuilder builder = ExcelWebUtil.builder(response, fileName)) {
            builder.theme(theme)
                    .zebraStripe(true)
                    .autoColumnWidth(12, 60)
                    .sheet("员工花名册", EmployeeRosterVO.class, data);
        }
    }

    /**
     * 端点 4：多语言国际化表头动态转译导出（依据客户端 Accept-Language 标头切换）
     *
     * @param response HTTP Servlet 响应对象
     * @param fileName 导出文件名
     * @throws IOException IO 异常
     */
    @GetMapping("/export-i18n")
    @WebLog(value = "花名册国际化表头导出", printParams = false, printResult = false, printCostTime = true)
    public void exportByI18n(
            HttpServletResponse response,
            @RequestParam(value = "fileName", defaultValue = "员工花名册_多语言国际化.xlsx") String fileName) throws IOException {
        List<I18nUserExcelVO> data = createSampleI18nUsers();
        ExcelWebUtil.export(response, fileName, data, I18nUserExcelVO.class);
    }

    /**
     * 端点 5：部门考勤纵向单元格合并统计表导出（@ExcelMerge）
     *
     * @param response HTTP Servlet 响应对象
     * @param fileName 导出文件名
     * @throws IOException IO 异常
     */
    @GetMapping("/export-merge")
    @WebLog(value = "部门考勤纵向合并导出", printParams = false, printResult = false, printCostTime = true)
    public void exportMerge(
            HttpServletResponse response,
            @RequestParam(value = "fileName", defaultValue = "部门考勤纵向合并统计表.xlsx") String fileName) throws IOException {
        List<DepartmentAttendanceMergeVO> data = createSampleAttendanceMerge();
        ExcelWebUtil.export(response, fileName, data, DepartmentAttendanceMergeVO.class);
    }

    // ==================== 业务模拟数据生成 ====================

    private List<EmployeeRosterVO> createSampleRoster() {
        return List.of(
                EmployeeRosterVO.builder()
                        .id(1001L)
                        .name("张三")
                        .gender(SampleGenderEnum.MALE)
                        .phone("13800138000")
                        .email("zhangsan@example.com")
                        .department("研发部")
                        .status(EmployeeStatusEnum.REGULAR)
                        .salary(new BigDecimal("18500.00"))
                        .entryDate(LocalDate.of(2023, 6, 1))
                        .build(),
                EmployeeRosterVO.builder()
                        .id(1002L)
                        .name("李四")
                        .gender(SampleGenderEnum.FEMALE)
                        .phone("13900139000")
                        .email("lisi@example.com")
                        .department("研发部")
                        .status(EmployeeStatusEnum.PROBATION)
                        .salary(new BigDecimal("12000.00"))
                        .entryDate(LocalDate.of(2026, 9, 15))
                        .build(),
                EmployeeRosterVO.builder()
                        .id(1003L)
                        .name("王五")
                        .gender(SampleGenderEnum.MALE)
                        .phone("13700137000")
                        .email("wangwu@example.com")
                        .department("市场部")
                        .status(EmployeeStatusEnum.REGULAR)
                        .salary(new BigDecimal("16000.00"))
                        .entryDate(LocalDate.of(2024, 3, 10))
                        .build(),
                EmployeeRosterVO.builder()
                        .id(1004L)
                        .name("赵六")
                        .gender(SampleGenderEnum.FEMALE)
                        .phone("13600136000")
                        .email("zhaoliu@example.com")
                        .department("财务部")
                        .status(EmployeeStatusEnum.RESIGNED)
                        .salary(new BigDecimal("15000.00"))
                        .entryDate(LocalDate.of(2022, 1, 1))
                        .build()
        );
    }

    private List<I18nUserExcelVO> createSampleI18nUsers() {
        return List.of(
                new I18nUserExcelVO(101L, "Alice", "alice@example.com", "研发部"),
                new I18nUserExcelVO(102L, "Bob", "bob@example.com", "市场部")
        );
    }

    private List<DepartmentAttendanceMergeVO> createSampleAttendanceMerge() {
        return List.of(
                DepartmentAttendanceMergeVO.builder()
                        .department("研发部")
                        .attendanceDate("2026-11-01")
                        .employeeName("张三")
                        .checkInTime("08:55")
                        .attendanceStatus("正常")
                        .build(),
                DepartmentAttendanceMergeVO.builder()
                        .department("研发部")
                        .attendanceDate("2026-11-01")
                        .employeeName("李雷")
                        .checkInTime("08:58")
                        .attendanceStatus("正常")
                        .build(),
                DepartmentAttendanceMergeVO.builder()
                        .department("市场部")
                        .attendanceDate("2026-11-01")
                        .employeeName("韩梅梅")
                        .checkInTime("09:05")
                        .attendanceStatus("迟到")
                        .build(),
                DepartmentAttendanceMergeVO.builder()
                        .department("市场部")
                        .attendanceDate("2026-11-01")
                        .employeeName("王五")
                        .checkInTime("08:50")
                        .attendanceStatus("正常")
                        .build(),
                DepartmentAttendanceMergeVO.builder()
                        .department("财务部")
                        .attendanceDate("2026-11-01")
                        .employeeName("赵六")
                        .checkInTime("08:45")
                        .attendanceStatus("正常")
                        .build()
        );
    }
}

package io.github.atengk.boot.sample.excel.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.excel.builder.ExcelBuilder;
import io.github.atengk.boot.excel.enums.ExcelThemeEnum;
import io.github.atengk.boot.excel.enums.HeaderValidationStrategy;
import io.github.atengk.boot.excel.model.ExcelErrorRow;
import io.github.atengk.boot.excel.model.ExcelMultiSheetResult;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.excel.util.ExcelWebUtil;
import io.github.atengk.boot.sample.excel.enums.EmployeeStatusEnum;
import io.github.atengk.boot.sample.excel.enums.SampleGenderEnum;
import io.github.atengk.boot.sample.excel.vo.DepartmentAttendanceMergeVO;
import io.github.atengk.boot.sample.excel.vo.EmployeeRosterVO;
import io.github.atengk.boot.sample.excel.vo.OnboardingImportVO;
import io.github.atengk.boot.sample.excel.vo.ProductCatalogVO;
import io.github.atengk.boot.sample.excel.vo.ReimbursementItemVO;
import io.github.atengk.boot.web.annotation.WebLog;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.fesod.sheet.write.metadata.fill.FillConfig;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 复合报表与图文填充控制器
 * <p>
 * 提供多 Sheet 联合导出与异构联合解析、无实体动态表头写出、
 * 基于 Java 2D 离线生成的 @ExcelImage 商品图文画册渲染、
 * 以及标准化费用报销单模板填充与空白模板下载能力。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-08
 */
@RestController
@RequestMapping("/sample/excel/report")
public class WorkbookReportController {

    /**
     * 端点 1：基于 ExcelWebUtil.builder 流式导出包含 3 个异构 Sheet 的复合工作簿
     *
     * @param response HTTP Servlet 响应对象
     * @param fileName 导出文件名
     * @throws IOException IO 异常
     */
    @GetMapping("/multi-sheet")
    @WebLog(value = "异构多 Sheet 联合导出", printParams = false, printResult = false, printCostTime = true)
    public void exportMultiSheet(
            HttpServletResponse response,
            @RequestParam(value = "fileName", defaultValue = "企业综合报表_多工作表.xlsx") String fileName) throws IOException {

        List<EmployeeRosterVO> rosterData = createSampleRoster();
        List<DepartmentAttendanceMergeVO> attendanceData = createSampleAttendance();
        List<List<String>> dynamicHeaders = List.of(
                List.of("流水编号"),
                List.of("结算周期"),
                List.of("收支类别"),
                List.of("交易金额(元)")
        );
        List<List<Object>> dynamicRows = List.of(
                List.of("TXN-20261101-01", "2026-11", "营业收入", 128500.00),
                List.of("TXN-20261101-02", "2026-11", "技术服务费", 64000.00),
                List.of("TXN-20261101-03", "2026-11", "办公运维", -18200.00)
        );

        try (ExcelBuilder builder = ExcelWebUtil.builder(response, fileName)) {
            // Sheet 1: 员工花名册（商务蓝主题 + 斑马纹 + 自适应列宽）
            builder.theme(ExcelThemeEnum.BUSINESS_BLUE)
                    .zebraStripe(true)
                    .autoColumnWidth(12, 60)
                    .sheet("员工花名册", EmployeeRosterVO.class, rosterData)
                    // Sheet 2: 部门考勤（极简白主题 + 纵向单元格合并）
                    .theme(ExcelThemeEnum.MINIMAL_CLEAN)
                    .sheet("部门考勤", DepartmentAttendanceMergeVO.class, attendanceData)
                    // Sheet 3: 财务动态流水（无实体动态表头写出）
                    .sheetDynamic("财务动态流水", dynamicHeaders, dynamicRows);
        }
    }

    /**
     * 端点 1B：基于 ExcelWebUtil.builder(..., password) 流式导出整工作簿 AES-256 强加密多 Sheet 复合报表
     *
     * @param response       HTTP Servlet 响应对象
     * @param password       AES-256 整表加密密码
     * @param includeInvalid 是否在入职清单 Sheet 中混入脏数据（用于联动体验多 Sheet 加密解析校验错误聚合）
     * @param fileName       导出文件名
     */
    @GetMapping("/multi-sheet-encrypt")
    @WebLog(value = "异构多 Sheet AES-256 强加密导出", printParams = false, printResult = false, printCostTime = true)
    public void exportMultiSheetEncrypted(
            HttpServletResponse response,
            @RequestParam(value = "password", defaultValue = "ReportSecret@2026") String password,
            @RequestParam(value = "includeInvalid", defaultValue = "false") boolean includeInvalid,
            @RequestParam(value = "fileName", defaultValue = "企业综合报表_多表AES256加密.xlsx") String fileName) {

        List<OnboardingImportVO> onboardingData = createSampleOnboardingList(includeInvalid);
        List<DepartmentAttendanceMergeVO> attendanceData = createSampleAttendance();
        List<List<String>> dynamicHeaders = List.of(
                List.of("流水编号"),
                List.of("结算周期"),
                List.of("交易金额(元)")
        );
        List<List<Object>> dynamicRows = List.of(
                List.of("TXN-202611-01", "2026-11", 128500.00),
                List.of("TXN-202611-02", "2026-11", 64000.00)
        );

        try (ExcelBuilder builder = ExcelWebUtil.builder(response, fileName, password)) {
            builder.theme(ExcelThemeEnum.BUSINESS_BLUE)
                    .zebraStripe(true)
                    .autoColumnWidth(12, 60)
                    .sheet("入职审批清单", OnboardingImportVO.class, onboardingData)
                    .theme(ExcelThemeEnum.MINIMAL_CLEAN)
                    .sheet("部门考勤", DepartmentAttendanceMergeVO.class, attendanceData)
                    .sheetDynamic("财务动态流水", dynamicHeaders, dynamicRows);
        }
    }

    /**
     * 端点 2B：带密码解密的多 Sheet 异构读取 + JSR-303 行级校验与表头契约聚合解析
     *
     * @param file           上传的多 Sheet Excel 文件（支持明文或 AES-256 密文）
     * @param password       工作簿访问密码（可选）
     * @param headerStrategy 表头契约校验策略（默认 LOOSE）
     * @param maxErrorRows   单表错误熔断阈值（默认 100）
     * @return 多 Sheet 合法数据统计与跨表错误聚合摘要
     * @throws IOException IO 异常
     */
    @PostMapping(value = "/multi-sheet-validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @WebLog(value = "加密多 Sheet JSR-303 校验聚合解析", printParams = false, printResult = true, printCostTime = true)
    public Result<Map<String, Object>> validateAndParseMultiSheet(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "password", required = false) String password,
            @RequestParam(value = "headerStrategy", defaultValue = "LOOSE") HeaderValidationStrategy headerStrategy,
            @RequestParam(value = "maxErrorRows", defaultValue = "100") int maxErrorRows) throws IOException {
        try (InputStream in = file.getInputStream()) {
            ExcelMultiSheetResult result = ExcelUtil.reader(in, password)
                    .validate()
                    .headerStrategy(headerStrategy)
                    .maxErrorRows(maxErrorRows)
                    .sheet(0, OnboardingImportVO.class)
                    .sheet(1, DepartmentAttendanceMergeVO.class)
                    .read();

            List<OnboardingImportVO> onboardingValidList = result.getSheetData(0);
            List<DepartmentAttendanceMergeVO> attendanceList = result.getSheetData(1);
            List<ExcelErrorRow> sheet0Errors = result.getSheetErrors(0);
            List<ExcelErrorRow> sheet1Errors = result.getSheetErrors(1);

            Map<String, Object> summary = new HashMap<>();
            summary.put("sheetCount", result.getSheetCount());
            summary.put("totalRowCount", result.getTotalRowCount());
            summary.put("onboardingValidCount", onboardingValidList.size());
            summary.put("attendanceCount", attendanceList.size());
            summary.put("hasErrors", result.hasErrors());
            summary.put("circuitBroken", result.isCircuitBroken());
            summary.put("sheet0ErrorCount", sheet0Errors.size());
            summary.put("sheet1ErrorCount", sheet1Errors.size());
            summary.put("allErrors", result.getAllErrors());
            return Result.success(summary);
        }
    }

    /**
     * 端点 2：上传包含多工作表的 Excel 文件并执行异构多 Sheet 联合解析
     *
     * @param file 上传的 Excel 文件
     * @return 提取各 Sheet 数据的摘要统计结果
     * @throws IOException IO 异常
     */
    @PostMapping(value = "/multi-sheet-parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @WebLog(value = "异构多 Sheet 联合解析", printParams = false, printResult = true, printCostTime = true)
    public Result<Map<String, Object>> parseMultiSheet(@RequestParam("file") MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            ExcelMultiSheetResult result = ExcelUtil.reader(in)
                    .sheet(0, EmployeeRosterVO.class)
                    .sheet(1, DepartmentAttendanceMergeVO.class)
                    .read();

            List<EmployeeRosterVO> rosters = result.getSheetData(0);
            List<DepartmentAttendanceMergeVO> attendances = result.getSheetData(1);

            Map<String, Object> summary = new HashMap<>();
            summary.put("rosterCount", rosters.size());
            summary.put("attendanceCount", attendances.size());
            summary.put("totalRowCount", result.getTotalRowCount());
            return Result.success(summary);
        }
    }

    /**
     * 端点 3：无实体动态表头与 Map 键值映射动态导出
     *
     * @param response HTTP Servlet 响应对象
     * @param fileName 导出文件名
     * @throws IOException IO 异常
     */
    @GetMapping("/dynamic")
    @WebLog(value = "无实体动态表头导出", printParams = false, printResult = false, printCostTime = true)
    public void exportDynamic(
            HttpServletResponse response,
            @RequestParam(value = "fileName", defaultValue = "无实体动态配置报表.xlsx") String fileName) throws IOException {

        List<String> headList = List.of("指标代号", "统计维度", "当期数值", "同比增幅", "预警等级");
        List<String> dataKeys = List.of("metricCode", "dimension", "currentValue", "growthRate", "alertLevel");
        List<Map<String, Object>> dataList = List.of(
                Map.of("metricCode", "MTR-001", "dimension", "华东大区", "currentValue", 1258000, "growthRate", "18.5%", "alertLevel", "正常"),
                Map.of("metricCode", "MTR-002", "dimension", "华北大区", "currentValue", 982000, "growthRate", "-3.2%", "alertLevel", "关注"),
                Map.of("metricCode", "MTR-003", "dimension", "华南大区", "currentValue", 1540000, "growthRate", "24.1%", "alertLevel", "优秀")
        );

        ExcelWebUtil.exportDynamic(response, fileName, "指标统计", headList, dataKeys, dataList);
    }

    /**
     * 端点 3B：基于 ExcelWebUtil.toResponseEntityDynamic 返回 ResponseEntity 异步动态导出（可选 AES-256 加密）
     *
     * @param password 可选加密密码
     * @param fileName 导出文件名
     * @return 包含动态配置报表的流式响应实体
     */
    @GetMapping("/dynamic-response-entity")
    @WebLog(value = "动态表头 ResponseEntity 导出", printParams = false, printResult = false, printCostTime = true)
    public ResponseEntity<StreamingResponseBody> exportDynamicResponseEntity(
            @RequestParam(value = "password", required = false) String password,
            @RequestParam(value = "fileName", defaultValue = "无实体动态配置报表_流式.xlsx") String fileName) {

        List<String> headList = List.of("指标代号", "统计维度", "当期数值", "同比增幅", "预警等级");
        List<String> dataKeys = List.of("metricCode", "dimension", "currentValue", "growthRate", "alertLevel");
        List<Map<String, Object>> dataList = List.of(
                Map.of("metricCode", "MTR-001", "dimension", "华东大区", "currentValue", 1258000, "growthRate", "18.5%", "alertLevel", "正常"),
                Map.of("metricCode", "MTR-002", "dimension", "华北大区", "currentValue", 982000, "growthRate", "-3.2%", "alertLevel", "关注"),
                Map.of("metricCode", "MTR-003", "dimension", "华南大区", "currentValue", 1540000, "growthRate", "24.1%", "alertLevel", "优秀")
        );

        return ExcelWebUtil.toResponseEntityDynamic(fileName, "指标统计", headList, dataKeys, dataList, password);
    }

    /**
     * 端点 4：商品图文画册导出（基于 Java 2D 内存离线生成图片，@ExcelImage 自适应撑起行高 75pt）
     *
     * @param response HTTP Servlet 响应对象
     * @param fileName 导出文件名
     * @throws IOException IO 异常
     */
    @GetMapping("/image-catalog")
    @WebLog(value = "商品图文画册导出", printParams = false, printResult = false, printCostTime = true)
    public void exportImageCatalog(
            HttpServletResponse response,
            @RequestParam(value = "fileName", defaultValue = "商品图文画册_75pt行高.xlsx") String fileName) throws IOException {

        List<ProductCatalogVO> catalogList = List.of(
                ProductCatalogVO.builder()
                        .id(10001L)
                        .name("旗舰智能手机 Pro")
                        .category("数码通讯")
                        .price(new BigDecimal("6999.00"))
                        .image(generateProductImage("SmartPhone Pro", new Color(41, 128, 185)))
                        .stockStatus("现货充足")
                        .build(),
                ProductCatalogVO.builder()
                        .id(10002L)
                        .name("超轻薄商务笔记本")
                        .category("电脑办公")
                        .price(new BigDecimal("8499.00"))
                        .image(generateProductImage("Ultrabook 14", new Color(39, 174, 96)))
                        .stockStatus("现货充足")
                        .build(),
                ProductCatalogVO.builder()
                        .id(10003L)
                        .name("无线机械键盘 G98")
                        .category("外设配件")
                        .price(new BigDecimal("499.00"))
                        .image(generateProductImage("Mech Keyboard", new Color(142, 68, 173)))
                        .stockStatus("紧缺热销")
                        .build()
        );

        ExcelWebUtil.export(response, fileName, catalogList, ProductCatalogVO.class);
    }

    /**
     * 端点 5：下载费用报销单空白 .xlsx 模板
     *
     * @param response HTTP Servlet 响应对象
     * @throws IOException IO 异常
     */
    @GetMapping("/template-blank")
    @WebLog(value = "下载费用报销单空白模板", printParams = false, printResult = false, printCostTime = true)
    public void downloadBlankTemplate(HttpServletResponse response) throws IOException {
        String fileName = "费用报销单_空白模板.xlsx";
        ExcelWebUtil.prepareWebResponse(response, fileName);
        byte[] templateBytes = loadOrGenerateTemplate();
        response.getOutputStream().write(templateBytes);
        response.getOutputStream().flush();
    }

    /**
     * 端点 6：基于费用报销单模板执行单对象与列表循环复合数据填充导出
     *
     * @param response HTTP Servlet 响应对象
     * @param fileName 导出文件名
     * @throws IOException IO 异常
     */
    @GetMapping("/fill")
    @WebLog(value = "费用报销单复合填充导出", printParams = false, printResult = false, printCostTime = true)
    public void exportFilledReimbursement(
            HttpServletResponse response,
            @RequestParam(value = "fileName", defaultValue = "费用报销单_填充导出.xlsx") String fileName) throws IOException {

        byte[] templateBytes = loadOrGenerateTemplate();

        Map<String, Object> variables = Map.of(
                "reportNo", "EXP-202611-0089",
                "date", "2026-11-08",
                "applicant", "张三",
                "department", "技术研发中心",
                "totalAmount", "¥ 3,450.00",
                "approver", "李四（技术总监）"
        );

        List<ReimbursementItemVO> items = List.of(
                ReimbursementItemVO.builder()
                        .expenseType("差旅交通费")
                        .amount(new BigDecimal("1280.00"))
                        .description("上海客户现场技术架构评审高铁往返")
                        .build(),
                ReimbursementItemVO.builder()
                        .expenseType("差旅住宿费")
                        .amount(new BigDecimal("1420.00"))
                        .description("上海商务酒店住宿 2 晚")
                        .build(),
                ReimbursementItemVO.builder()
                        .expenseType("餐饮招待费")
                        .amount(new BigDecimal("750.00"))
                        .description("客户联合评审工作餐")
                        .build()
        );

        FillConfig fillConfig = FillConfig.builder().forceNewRow(Boolean.TRUE).build();

        try (InputStream templateIn = new ByteArrayInputStream(templateBytes)) {
            ExcelWebUtil.fill(response, fileName, "费用报销单", templateIn, variables, items, fillConfig);
        }
    }

    /**
     * 端点 6B：基于 ExcelWebUtil.toResponseEntityFill 返回 ResponseEntity 复合模板填充（可选 AES-256 加密）
     *
     * @param password 可选 AES-256 加密密码
     * @param fileName 导出文件名
     * @return 包含填充报表的流式响应实体
     */
    @GetMapping("/fill-response-entity")
    @WebLog(value = "费用报销单 ResponseEntity 填充导出", printParams = false, printResult = false, printCostTime = true)
    public ResponseEntity<StreamingResponseBody> exportFilledResponseEntity(
            @RequestParam(value = "password", required = false) String password,
            @RequestParam(value = "fileName", defaultValue = "费用报销单_流式填充.xlsx") String fileName) {

        byte[] templateBytes = loadOrGenerateTemplate();
        Map<String, Object> variables = Map.of(
                "reportNo", "EXP-202611-0099",
                "date", "2026-11-13",
                "applicant", "李四",
                "department", "平台架构部",
                "totalAmount", "¥ 3,450.00",
                "approver", "王五（架构总监）"
        );
        List<ReimbursementItemVO> items = List.of(
                ReimbursementItemVO.builder()
                        .expenseType("差旅交通费")
                        .amount(new BigDecimal("1280.00"))
                        .description("北京技术大会差旅往返")
                        .build(),
                ReimbursementItemVO.builder()
                        .expenseType("差旅住宿费")
                        .amount(new BigDecimal("2170.00"))
                        .description("会议期间住宿费")
                        .build()
        );
        FillConfig fillConfig = FillConfig.builder().forceNewRow(Boolean.TRUE).build();

        return ExcelWebUtil.toResponseEntityFill(
                fileName,
                "费用报销单",
                new ByteArrayInputStream(templateBytes),
                variables,
                items,
                fillConfig,
                password
        );
    }

    // ==================== 离线与辅助方法 ====================

    /**
     * 基于 Java 2D 纯离线生成 PNG 格式商品效果图字节数组（零外网依赖）
     */
    public static byte[] generateProductImage(String text, Color bgColor) {
        int width = 120;
        int height = 80;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(bgColor);
            g.fillRect(0, 0, width, height);

            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            FontMetrics fm = g.getFontMetrics();
            int x = (width - fm.stringWidth(text)) / 2;
            int y = (height - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(text, x, y);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "PNG", baos);
        } catch (IOException e) {
            throw new IllegalStateException("生成图片失败", e);
        }
        return baos.toByteArray();
    }

    /**
     * 获取类路径模板，若未打包则动态生成标准化费用报销单模板字节流
     */
    public static byte[] loadOrGenerateTemplate() {
        try (InputStream in = WorkbookReportController.class.getResourceAsStream("/templates/expense_reimbursement_template.xlsx")) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (IOException ignored) {
        }
        return generateReimbursementTemplateBytes();
    }

    /**
     * 动态生成标准化费用报销单 Excel 模板字节
     */
    public static byte[] generateReimbursementTemplateBytes() {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("费用报销单");

            Row titleRow = sheet.createRow(0);
            titleRow.createCell(0).setCellValue("费用报销审批单");

            Row metaRow1 = sheet.createRow(1);
            metaRow1.createCell(0).setCellValue("报销单号: {reportNo}");
            metaRow1.createCell(2).setCellValue("填报日期: {date}");

            Row metaRow2 = sheet.createRow(2);
            metaRow2.createCell(0).setCellValue("报销人员: {applicant}");
            metaRow2.createCell(2).setCellValue("所属部门: {department}");

            Row headRow = sheet.createRow(3);
            headRow.createCell(0).setCellValue("费用科目");
            headRow.createCell(1).setCellValue("报销金额");
            headRow.createCell(2).setCellValue("用途说明");

            Row itemRow = sheet.createRow(4);
            itemRow.createCell(0).setCellValue("{.expenseType}");
            itemRow.createCell(1).setCellValue("{.amount}");
            itemRow.createCell(2).setCellValue("{.description}");

            Row footRow = sheet.createRow(5);
            footRow.createCell(0).setCellValue("合计金额: {totalAmount}");
            footRow.createCell(2).setCellValue("审批领导: {approver}");

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("动态构建报销模板失败", e);
        }
    }

    private List<EmployeeRosterVO> createSampleRoster() {
        return List.of(
                EmployeeRosterVO.builder()
                        .id(1001L)
                        .name("张三")
                        .gender(SampleGenderEnum.MALE)
                        .phone("13800138000")
                        .email("zhangsan@example.com")
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
                        .status(EmployeeStatusEnum.PROBATION)
                        .salary(new BigDecimal("14000.00"))
                        .entryDate(LocalDate.of(2026, 10, 15))
                        .build()
        );
    }

    private List<DepartmentAttendanceMergeVO> createSampleAttendance() {
        return List.of(
                DepartmentAttendanceMergeVO.builder()
                        .department("技术研发中心")
                        .attendanceDate("2026-11-01")
                        .employeeName("张三")
                        .checkInTime("08:55:12")
                        .attendanceStatus("正常")
                        .build(),
                DepartmentAttendanceMergeVO.builder()
                        .department("技术研发中心")
                        .attendanceDate("2026-11-01")
                        .employeeName("李雷")
                        .checkInTime("08:58:44")
                        .attendanceStatus("正常")
                        .build(),
                DepartmentAttendanceMergeVO.builder()
                        .department("市场运营部")
                        .attendanceDate("2026-11-01")
                        .employeeName("韩梅梅")
                        .checkInTime("09:02:10")
                        .attendanceStatus("迟到")
                        .build()
        );
    }

    private List<OnboardingImportVO> createSampleOnboardingList(boolean includeInvalid) {
        OnboardingImportVO valid1 = OnboardingImportVO.builder()
                .name("张三")
                .phone("13800138000")
                .email("zhangsan@example.com")
                .gender(SampleGenderEnum.MALE)
                .department("研发部")
                .status("1")
                .expectedSalary(new BigDecimal("18000.00"))
                .entryDate(LocalDate.of(2026, 11, 1))
                .build();
        OnboardingImportVO valid2 = OnboardingImportVO.builder()
                .name("李四")
                .phone("13900139000")
                .email("lisi@example.com")
                .gender(SampleGenderEnum.FEMALE)
                .department("市场部")
                .status("2")
                .expectedSalary(new BigDecimal("15000.00"))
                .entryDate(LocalDate.of(2026, 11, 5))
                .build();
        if (!includeInvalid) {
            return List.of(valid1, valid2);
        }
        OnboardingImportVO invalid = OnboardingImportVO.builder()
                .name("")
                .phone("110")
                .email("bad-email")
                .gender(SampleGenderEnum.MALE)
                .department("研发部")
                .status("1")
                .expectedSalary(new BigDecimal("-500.00"))
                .entryDate(LocalDate.of(2026, 11, 1))
                .build();
        return List.of(valid1, invalid);
    }
}

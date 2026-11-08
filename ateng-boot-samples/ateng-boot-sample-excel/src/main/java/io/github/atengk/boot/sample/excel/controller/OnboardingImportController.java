package io.github.atengk.boot.sample.excel.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.excel.enums.HeaderValidationStrategy;
import io.github.atengk.boot.excel.model.ExcelErrorRow;
import io.github.atengk.boot.excel.model.ExcelReadResult;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.excel.util.ExcelWebUtil;
import io.github.atengk.boot.sample.excel.enums.SampleGenderEnum;
import io.github.atengk.boot.sample.excel.vo.OnboardingImportVO;
import io.github.atengk.boot.web.annotation.WebLog;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 员工入职导入与校验防呆控制器（入职防呆域示例）
 * <p>
 * 覆盖智能防呆模板生成、纯 cURL 体验样本下载、严格/宽松表头契约校验、
 * JSR-303 行级校验复合结果、差错剥离导出、红字批注错误回写及大数据量分批消费。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-08
 */
@RestController
@RequestMapping("/sample/excel/onboarding")
public class OnboardingImportController {

    /**
     * 端点 1：下载员工入职防呆导入模板（含下拉选单 @ExcelDropdown 与表头悬浮批注 @ExcelPrompt）
     *
     * @param response HTTP Servlet 响应对象
     * @param fileName 模板文件名
     * @throws IOException IO 异常
     */
    @GetMapping("/template")
    @WebLog(value = "下载入职防呆导入模板", printParams = false, printResult = false, printCostTime = true)
    public void downloadTemplate(
            HttpServletResponse response,
            @RequestParam(value = "fileName", defaultValue = "员工入职防呆导入模板.xlsx") String fileName) throws IOException {
        ExcelWebUtil.exportTemplate(response, fileName, OnboardingImportVO.class);
    }

    /**
     * 端点 2：下载纯合规模拟数据 Excel 文件（供客户端或 cURL 一键体验校验成功导入）
     *
     * @param response HTTP Servlet 响应对象
     * @param fileName 数据文件名
     * @throws IOException IO 异常
     */
    @GetMapping("/sample-valid")
    @WebLog(value = "下载合规测试样本", printParams = false, printResult = false, printCostTime = true)
    public void downloadValidSample(
            HttpServletResponse response,
            @RequestParam(value = "fileName", defaultValue = "员工入职合规测试样本.xlsx") String fileName) throws IOException {
        List<OnboardingImportVO> data = createValidSampleList();
        ExcelWebUtil.export(response, fileName, data, OnboardingImportVO.class);
    }

    /**
     * 端点 3：下载包含脏数据的模拟测试 Excel 文件（供客户端或 cURL 一键体验校验失败与错误回写）
     *
     * @param response HTTP Servlet 响应对象
     * @param fileName 数据文件名
     * @throws IOException IO 异常
     */
    @GetMapping("/sample-dirty")
    @WebLog(value = "下载脏数据测试样本", printParams = false, printResult = false, printCostTime = true)
    public void downloadDirtySample(
            HttpServletResponse response,
            @RequestParam(value = "fileName", defaultValue = "员工入职含错测试样本.xlsx") String fileName) throws IOException {
        List<OnboardingImportVO> data = createDirtySampleList();
        ExcelWebUtil.export(response, fileName, data, OnboardingImportVO.class);
    }

    /**
     * 端点 4：快速前置表头契约校验（仅扫描表头行即刻关闭，不解析后续数据行）
     *
     * @param file     上传的 Excel 文件
     * @param strategy 校验策略（STRICT / LOOSE / IGNORE_ORDER，默认 STRICT）
     * @return 校验通过提示
     * @throws IOException IO 异常
     */
    @PostMapping(value = "/validate-header", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @WebLog(value = "前置表头契约校验", printParams = true, printResult = true, printCostTime = true)
    public Result<String> validateHeader(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "strategy", defaultValue = "STRICT") HeaderValidationStrategy strategy) throws IOException {
        try (InputStream in = file.getInputStream()) {
            ExcelUtil.validateHeader(in, OnboardingImportVO.class, strategy);
            return Result.success("表头契约校验通过 [" + strategy.name() + "]");
        }
    }

    /**
     * 端点 5：强类型同步读取与 JSR-303 格式校验（返回合法数据与错误明细，不抛异常）
     *
     * @param file           上传的 Excel 文件
     * @param headerStrategy 表头校验策略（默认 LOOSE）
     * @param maxErrorRows   最大错误熔断行数（默认 100）
     * @return 复合校验读取结果
     * @throws IOException IO 异常
     */
    @PostMapping(value = "/import-result", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @WebLog(value = "入职数据读取与行级校验", printParams = false, printResult = true, printCostTime = true)
    public Result<ExcelReadResult<OnboardingImportVO>> importWithResult(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "headerStrategy", defaultValue = "LOOSE") HeaderValidationStrategy headerStrategy,
            @RequestParam(value = "maxErrorRows", defaultValue = "100") int maxErrorRows) throws IOException {
        try (InputStream in = file.getInputStream()) {
            ExcelReadResult<OnboardingImportVO> result = ExcelUtil.readWithResult(in, OnboardingImportVO.class, headerStrategy, maxErrorRows);
            return Result.success(result);
        }
    }

    /**
     * 端点 6：提取导入失败行并单独导出为纯数据报表（差错剥离）
     *
     * @param file     上传的 Excel 文件
     * @param response HTTP Servlet 响应对象
     * @param fileName 导出文件名
     * @throws IOException IO 异常
     */
    @PostMapping(value = "/export-failed-rows", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @WebLog(value = "导入差错数据剥离导出", printParams = false, printResult = false, printCostTime = true)
    public void exportFailedRows(
            @RequestParam("file") MultipartFile file,
            HttpServletResponse response,
            @RequestParam(value = "fileName", defaultValue = "员工入职导入失败明细.xlsx") String fileName) throws IOException {
        try (InputStream in = file.getInputStream()) {
            ExcelReadResult<OnboardingImportVO> result = ExcelUtil.readWithResult(in, OnboardingImportVO.class);
            ExcelWebUtil.exportFailedRows(response, fileName, OnboardingImportVO.class, result.getFailedData(), result.getErrors());
        }
    }

    /**
     * 端点 7：生成带红字高亮与悬浮批注的原样反馈错误 Excel（回写错题集）
     *
     * @param file     上传的 Excel 文件
     * @param response HTTP Servlet 响应对象
     * @param fileName 导出文件名
     * @throws IOException IO 异常
     */
    @PostMapping(value = "/export-feedback", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @WebLog(value = "导入错误原样标红批注回写反馈", printParams = false, printResult = false, printCostTime = true)
    public void exportErrorFeedback(
            @RequestParam("file") MultipartFile file,
            HttpServletResponse response,
            @RequestParam(value = "fileName", defaultValue = "员工入职导入错误反馈_标红批注.xlsx") String fileName) throws IOException {
        byte[] bytes = file.getBytes();
        ExcelReadResult<OnboardingImportVO> result;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            result = ExcelUtil.readWithResult(in, OnboardingImportVO.class);
        }
        try (InputStream originalIn = new ByteArrayInputStream(bytes)) {
            ExcelWebUtil.exportErrorFeedback(response, fileName, originalIn, result.getErrors());
        }
    }

    /**
     * 端点 8：大数据量分批流式校验与消费（防内存溢出 OOM）
     *
     * @param file      上传的 Excel 文件
     * @param batchSize 单批次消费大小（默认 100）
     * @return 批处理聚合摘要统计
     * @throws IOException IO 异常
     */
    @PostMapping(value = "/import-batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @WebLog(value = "入职数据流式分批校验消费", printParams = true, printResult = true, printCostTime = true)
    public Result<Map<String, Object>> importBatch(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "batchSize", defaultValue = "100") int batchSize) throws IOException {
        AtomicInteger totalSuccess = new AtomicInteger(0);
        List<ExcelErrorRow> errorRows = new CopyOnWriteArrayList<>();

        try (InputStream in = file.getInputStream()) {
            ExcelUtil.readBatchWithValidation(
                    in,
                    OnboardingImportVO.class,
                    batchSize,
                    batchList -> totalSuccess.addAndGet(batchList.size()),
                    errorRows::addAll
            );
        }

        return Result.success(Map.of(
                "totalSuccess", totalSuccess.get(),
                "totalErrors", errorRows.size(),
                "errors", errorRows
        ));
    }

    // ==================== 业务模拟数据生成 ====================

    private List<OnboardingImportVO> createValidSampleList() {
        return List.of(
                OnboardingImportVO.builder()
                        .name("张三")
                        .phone("13800138000")
                        .email("zhangsan@example.com")
                        .gender(SampleGenderEnum.MALE)
                        .department("研发部")
                        .status("1")
                        .expectedSalary(new BigDecimal("18000.00"))
                        .entryDate(LocalDate.of(2026, 11, 1))
                        .build(),
                OnboardingImportVO.builder()
                        .name("李四")
                        .phone("13900139000")
                        .email("lisi@example.com")
                        .gender(SampleGenderEnum.FEMALE)
                        .department("市场部")
                        .status("2")
                        .expectedSalary(new BigDecimal("15000.00"))
                        .entryDate(LocalDate.of(2026, 11, 5))
                        .build(),
                OnboardingImportVO.builder()
                        .name("王五")
                        .phone("13700137000")
                        .email("wangwu@example.com")
                        .gender(SampleGenderEnum.MALE)
                        .department("财务部")
                        .status("1")
                        .expectedSalary(new BigDecimal("16000.00"))
                        .entryDate(LocalDate.of(2026, 11, 10))
                        .build()
        );
    }

    private List<OnboardingImportVO> createDirtySampleList() {
        return List.of(
                OnboardingImportVO.builder()
                        .name("合规候选人")
                        .phone("13800000000")
                        .email("pass@example.com")
                        .gender(SampleGenderEnum.MALE)
                        .department("研发部")
                        .status("1")
                        .expectedSalary(new BigDecimal("20000.00"))
                        .entryDate(LocalDate.of(2026, 11, 1))
                        .build(),
                OnboardingImportVO.builder()
                        .name("") // 姓名为空
                        .phone("110") // 手机号格式不合法
                        .email("bad-email") // 邮箱格式错误
                        .gender(SampleGenderEnum.FEMALE)
                        .department("研发部")
                        .status("1")
                        .expectedSalary(new BigDecimal("-1000.00")) // 薪资必须大于 0
                        .entryDate(LocalDate.of(2026, 11, 1))
                        .build(),
                OnboardingImportVO.builder()
                        .name("李雷")
                        .phone("123456") // 手机号格式错误
                        .email("invalid@") // 邮箱格式错误
                        .gender(null) // 性别为空
                        .department("") // 部门为空
                        .status("1")
                        .expectedSalary(new BigDecimal("0.00")) // 薪资非正数
                        .entryDate(LocalDate.of(2026, 12, 1))
                        .build()
        );
    }
}

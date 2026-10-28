package io.github.atengk.boot.sample.excel.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.excel.model.ExcelReadResult;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.excel.util.ExcelWebUtil;
import io.github.atengk.boot.sample.excel.service.DynamicColumnProjectionScenarioService;
import io.github.atengk.boot.sample.excel.service.ValidationAndFeedbackScenarioService;
import io.github.atengk.boot.sample.excel.vo.UserExcelVO;
import io.github.atengk.boot.web.annotation.WebLog;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Excel Web 契约控制器（生产就绪最小接入边界）
 * <p>
 * 严格收敛为 ≤ 3 个具有代表性的 Web 端点，专职验证 RFC 5987 / RFC 6266 标准响应头、
 * 异步流式写入（StreamingResponseBody）、同步 Servlet 写入与客户端 Multipart 导入校验契约。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@RestController
@RequestMapping("/api/excel/web-contract")
public class ExcelWebContractController {

    private final DynamicColumnProjectionScenarioService scenarioService;
    private final ValidationAndFeedbackScenarioService validationService;

    public ExcelWebContractController(DynamicColumnProjectionScenarioService scenarioService,
                                      ValidationAndFeedbackScenarioService validationService) {
        this.scenarioService = scenarioService;
        this.validationService = validationService;
    }

    /**
     * 端点 1：返回标准 ResponseEntity&lt;StreamingResponseBody&gt; 异步流式下载
     * 演示标准的 RFC 5987 / RFC 6266 文件头与动态列投影支持
     *
     * @param includeColumns 动态包含的列白名单（可选，逗号分割）
     * @return 包含异步流的响应实体
     */
    @GetMapping("/response-entity")
    @WebLog(value = "ResponseEntity 异步流式导出", printParams = true, printResult = false, printCostTime = true)
    public ResponseEntity<StreamingResponseBody> exportByResponseEntity(
            @RequestParam(value = "includeColumns", required = false) List<String> includeColumns) {
        String fileName = "用户名单_异步流式.xlsx";
        HttpHeaders headers = ExcelWebUtil.createDownloadHeaders(fileName);

        StreamingResponseBody responseBody = outputStream -> {
            List<UserExcelVO> data = scenarioService.getMockUserList();
            ExcelUtil.export(outputStream, "用户清单", data, UserExcelVO.class, includeColumns, null);
        };

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(responseBody);
    }

    /**
     * 端点 2：直接写入 HttpServletResponse 同步流式下载
     * 演示底层原生适配与 ExcelWebUtil 响应防卫门面
     *
     * @param response HTTP Servlet 响应对象
     * @throws IOException IO 异常
     */
    @GetMapping("/servlet-response")
    @WebLog(value = "HttpServletResponse 同步流式导出", printParams = false, printResult = false, printCostTime = true)
    public void exportByServletResponse(HttpServletResponse response) throws IOException {
        List<UserExcelVO> data = scenarioService.getMockUserList();
        ExcelWebUtil.export(response, "用户名单_同步流式.xlsx", data, UserExcelVO.class);
    }

    /**
     * 端点 3：接收 MultipartFile 上传并执行流式读取与 JSR-303 格式校验
     * 演示显式强类型 Result&lt;ExcelReadResult&gt; 契约自洽
     *
     * @param file 上传的 Excel 文件
     * @return 导入结果详情（成功数据列表或行级校验错误报告）
     * @throws IOException IO 异常
     */
    @PostMapping(value = "/upload-validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @WebLog(value = "Excel 文件上传与校验", printParams = false, printResult = true, printCostTime = true)
    public Result<ExcelReadResult<UserExcelVO>> uploadAndValidate(@RequestParam("file") MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            return Result.success(validationService.readAndValidate(inputStream));
        }
    }
}

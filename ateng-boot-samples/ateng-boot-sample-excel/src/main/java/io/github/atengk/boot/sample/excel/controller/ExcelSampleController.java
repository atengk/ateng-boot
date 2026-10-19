package io.github.atengk.boot.sample.excel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.excel.async.ExportTask;
import io.github.atengk.boot.excel.model.ExcelReadResult;
import io.github.atengk.boot.sample.excel.service.ExcelSampleService;
import io.github.atengk.boot.sample.excel.vo.UserExcelVO;
import io.github.atengk.boot.web.annotation.WebLog;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 示例 Excel 导入导出控制层
 * 显式遵循 Result 契约与流式无侵入响应规范，覆盖流式导出、多 Sheet 编排、单元格合并、防呆模板下载、JSR-303 导入校验与异步导出任务中心
 *
 * @author Ateng
 * @since 2026-10-17
 */
@RestController
@RequestMapping("/api/excel")
public class ExcelSampleController {

    private final ExcelSampleService excelSampleService;
    private final ObjectMapper objectMapper;

    public ExcelSampleController(ExcelSampleService excelSampleService, ObjectMapper objectMapper) {
        this.excelSampleService = excelSampleService;
        this.objectMapper = objectMapper;
    }

    /**
     * 流式导出用户数据端点
     * 演示 @ExcelSensitive 动态脱敏、@ExcelEnum 枚举转义与 @ExcelDict 字典转义
     *
     * @param response HTTP Servlet 响应对象
     */
    @GetMapping("/export")
    @WebLog(value = "流式导出用户数据", printParams = false, printResult = false, printCostTime = true)
    public void exportUsers(HttpServletResponse response) {
        excelSampleService.exportUsers(response);
    }

    /**
     * Fluent 多 Sheet 复杂流式导出端点
     * 演示 ExcelBuilder 链式编排多工作表、主题切换与动态表头流式写出
     *
     * @param response HTTP Servlet 响应对象
     */
    @GetMapping("/export-multi-sheet")
    @WebLog(value = "多工作表链式流式导出", printParams = false, printResult = false, printCostTime = true)
    public void exportMultiSheet(HttpServletResponse response) {
        excelSampleService.exportMultiSheet(response);
    }

    /**
     * 纵向单元格合并流式导出端点
     * 演示 @ExcelMerge 相邻相同值纵向自动合并
     *
     * @param response HTTP Servlet 响应对象
     */
    @GetMapping("/export-merge")
    @WebLog(value = "纵向单元格合并导出", printParams = false, printResult = false, printCostTime = true)
    public void exportMerge(HttpServletResponse response) {
        excelSampleService.exportMerge(response);
    }

    /**
     * 防呆导入模板下载端点
     * 演示 @ExcelDropdown 静态与动态下拉选单、@ExcelPrompt 表头批注指南自动注入
     *
     * @param response HTTP Servlet 响应对象
     */
    @GetMapping("/template")
    @WebLog(value = "下载用户导入模板", printParams = false, printResult = false, printCostTime = true)
    public void downloadTemplate(HttpServletResponse response) {
        excelSampleService.downloadTemplate(response);
    }

    /**
     * 数据导入与行级校验端点（结构化返回）
     * 演示 BatchValidationListener 行级校验与全量错误行聚合
     *
     * @param file 上传的 Excel 文件
     * @return 导入结果详情（成功数据列表或校验错误提示）
     * @throws IOException 读取流异常
     */
    @PostMapping("/import")
    @WebLog(value = "导入用户数据并校验", printParams = false, printResult = true, printCostTime = true)
    public Result<List<UserExcelVO>> importUsers(@RequestParam("file") MultipartFile file) throws IOException {
        ExcelReadResult<UserExcelVO> result = excelSampleService.readAndValidate(file.getInputStream());
        if (!result.isSuccess()) {
            return Result.fail(CommonResultCode.BAD_REQUEST, "Excel 数据校验不通过，共 " + result.getErrorCount() + " 行错误");
        }
        return Result.success(result.getData());
    }

    /**
     * 数据导入校验失败生成错题集反馈端点
     * 校验失败时将单元格标红并插入悬浮批注回写给客户端，全部通过时写回强类型成功 Result
     *
     * @param file 上传的 Excel 文件
     * @param response HTTP Servlet 响应对象
     * @throws IOException 流操作异常
     */
    @PostMapping("/import-feedback")
    @WebLog(value = "导入并生成错误反馈 Excel", printParams = false, printResult = false, printCostTime = true)
    public void importWithFeedback(@RequestParam("file") MultipartFile file, HttpServletResponse response) throws IOException {
        byte[] fileBytes = file.getBytes();
        ExcelReadResult<UserExcelVO> result = excelSampleService.readAndValidate(file.getInputStream());
        if (!result.isSuccess()) {
            excelSampleService.renderErrorFeedback(fileBytes, response);
            return;
        }
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Result.success(result.getData().size()));
    }

    /**
     * 无实体动态多列导出端点
     * 演示基于动态表头与 Map/List 动态数据流式写出
     *
     * @param response HTTP Servlet 响应对象
     */
    @GetMapping("/export-dynamic")
    @WebLog(value = "无实体动态导出", printParams = false, printResult = false, printCostTime = true)
    public void exportDynamic(HttpServletResponse response) {
        excelSampleService.exportDynamic(response);
    }

    /**
     * 提交后台受管异步导出任务端点
     *
     * @param taskName 任务名称
     * @return 生成的任务 ID
     */
    @PostMapping("/async-export")
    @WebLog(value = "提交异步导出任务", printParams = true, printResult = true, printCostTime = true)
    public Result<String> submitAsyncExport(@RequestParam(value = "taskName", defaultValue = "全量用户导出") String taskName) {
        String taskId = excelSampleService.submitAsyncExport(taskName);
        return Result.success(taskId);
    }

    /**
     * 查询异步导出任务详情端点
     *
     * @param taskId 任务 ID
     * @return 任务对象
     */
    @GetMapping("/async-task/{taskId}")
    @WebLog(value = "查询异步导出任务状态", printParams = true, printResult = true, printCostTime = true)
    public Result<ExportTask> getAsyncTask(@PathVariable("taskId") String taskId) {
        ExportTask task = excelSampleService.getAsyncTask(taskId);
        if (task == null) {
            return Result.fail(CommonResultCode.NOT_FOUND, "未查询到目标导出任务: " + taskId);
        }
        return Result.success(task);
    }
}

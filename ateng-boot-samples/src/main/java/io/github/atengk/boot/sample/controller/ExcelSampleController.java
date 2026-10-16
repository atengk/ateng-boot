package io.github.atengk.boot.sample.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.excel.listener.BatchValidationListener;
import io.github.atengk.boot.excel.model.ExcelErrorRow;
import io.github.atengk.boot.excel.model.ExcelReadResult;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.sample.enums.SampleGenderEnum;
import io.github.atengk.boot.sample.vo.UserExcelVO;
import io.github.atengk.boot.web.annotation.WebLog;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Validator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 示例 Excel 导入导出控制层
 * 显式遵循 Result 契约与流式无侵入响应规范，覆盖流式导出、动态脱敏、防呆模板下载、JSR-303 导入校验与无实体动态导出
 *
 * @author Ateng
 * @since 2026-10-17
 */
@RestController
@RequestMapping("/api/excel")
public class ExcelSampleController {

    private final Validator validator;
    private final ObjectMapper objectMapper;

    public ExcelSampleController(Validator validator, ObjectMapper objectMapper) {
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    /**
     * 流式导出用户数据端点
     * 演示 @ExcelSensitive 动态脱敏、@ExcelEnum 枚举转义与 @ExcelDict 字典转义
     *
     * @param response HTTP Servlet 响应对象
     * @throws IOException 导出 IO 异常
     */
    @GetMapping("/export")
    @WebLog(value = "流式导出用户数据", printParams = false, printResult = false, printCostTime = true)
    public void exportUsers(HttpServletResponse response) throws IOException {
        List<UserExcelVO> userList = getMockUserList();
        ExcelUtil.export(response, "用户列表.xlsx", UserExcelVO.class, userList);
    }

    /**
     * 防呆导入模板下载端点
     * 演示 @ExcelDropdown 下拉选单与 @ExcelPrompt 表头批注指南自动注入
     *
     * @param response HTTP Servlet 响应对象
     * @throws IOException 模板下载 IO 异常
     */
    @GetMapping("/template")
    @WebLog(value = "下载用户导入模板", printParams = false, printResult = false, printCostTime = true)
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        ExcelUtil.exportTemplate(response, "用户导入模板.xlsx", UserExcelVO.class);
    }

    /**
     * 数据导入与行级校验端点（结构化返回）
     * 演示 BatchValidationListener 行级校验与全量错误行聚合
     *
     * @param file 上传的 Excel 文件
     * @return 导入结果详情（成功数据列表或校验错误行列表）
     * @throws IOException 读取流异常
     */
    @PostMapping("/import")
    @WebLog(value = "导入用户数据并校验", printParams = false, printResult = true, printCostTime = true)
    public Result<List<UserExcelVO>> importUsers(@RequestParam("file") MultipartFile file) throws IOException {
        ExcelReadResult<UserExcelVO> result = readAndValidate(file.getInputStream());
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
        ExcelReadResult<UserExcelVO> result;
        try (InputStream inputStream = new ByteArrayInputStream(fileBytes)) {
            result = readAndValidate(inputStream);
        }
        if (!result.isSuccess()) {
            try (InputStream templateStream = new ByteArrayInputStream(fileBytes)) {
                ExcelUtil.exportErrorFeedback(response, "导入错误反馈.xlsx", templateStream, result.getErrors());
            }
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
     * @throws IOException 导出 IO 异常
     */
    @GetMapping("/export-dynamic")
    @WebLog(value = "无实体动态导出", printParams = false, printResult = false, printCostTime = true)
    public void exportDynamic(HttpServletResponse response) throws IOException {
        List<List<String>> headList = List.of(
                List.of("报表日期"),
                List.of("销售大区"),
                List.of("业绩金额")
        );
        List<List<Object>> dataList = List.of(
                List.of("2026-10-17", "华东大区", 88888.88),
                List.of("2026-10-17", "华南大区", 66666.66),
                List.of("2026-10-17", "华北大区", 99999.99)
        );
        ExcelUtil.exportDynamic(response, "动态销售报表.xlsx", headList, dataList);
    }

    /**
     * 流式解析并执行 JSR-303 行级校验辅助方法
     *
     * @param inputStream 输入流
     * @return 读取与校验聚合结果
     */
    private ExcelReadResult<UserExcelVO> readAndValidate(InputStream inputStream) {
        BatchValidationListener<UserExcelVO> listener = new BatchValidationListener<>(UserExcelVO.class, validator, false);
        ExcelUtil.read(inputStream, UserExcelVO.class, listener);
        return listener.toResult();
    }

    /**
     * 构建模拟用户初始数据集
     *
     * @return 示例用户列表
     */
    private List<UserExcelVO> getMockUserList() {
        List<UserExcelVO> list = new ArrayList<>();
        list.add(new UserExcelVO(1001L, "张三", "13800138000", "zhangsan@example.com",
                SampleGenderEnum.MALE, "研发部", "0"));
        list.add(new UserExcelVO(1002L, "李四", "13900139000", "lisi@example.com",
                SampleGenderEnum.FEMALE, "市场部", "1"));
        list.add(new UserExcelVO(1003L, "王五", "13700137000", "wangwu@example.com",
                SampleGenderEnum.UNKNOWN, "财务部", "0"));
        return list;
    }
}

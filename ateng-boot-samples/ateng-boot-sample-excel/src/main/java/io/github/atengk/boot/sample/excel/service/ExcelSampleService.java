package io.github.atengk.boot.sample.excel.service;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.excel.async.AsyncExportTaskService;
import io.github.atengk.boot.excel.async.ExportTask;
import io.github.atengk.boot.excel.async.storage.ExportFileStorage;
import io.github.atengk.boot.excel.builder.ExcelBuilder;
import io.github.atengk.boot.excel.enums.ExcelThemeEnum;
import io.github.atengk.boot.excel.listener.BatchValidationListener;
import io.github.atengk.boot.excel.model.ExcelErrorRow;
import io.github.atengk.boot.excel.model.ExcelReadResult;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.sample.excel.enums.SampleGenderEnum;
import io.github.atengk.boot.sample.excel.vo.DepartmentUserMergeVO;
import io.github.atengk.boot.sample.excel.vo.I18nUserExcelVO;
import io.github.atengk.boot.sample.excel.vo.SecurityUserExcelVO;
import io.github.atengk.boot.sample.excel.vo.UserExcelVO;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 示例 Excel 核心业务服务层
 * 封装流式导出、多 Sheet 链式编排、纵向单元格合并、行级校验及异步任务调度
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Service
public class ExcelSampleService {

    private static final Logger log = LoggerFactory.getLogger(ExcelSampleService.class);

    private final Validator validator;
    private final AsyncExportTaskService asyncExportTaskService;
    private final ExportFileStorage exportFileStorage;

    public ExcelSampleService(Validator validator,
                              @Autowired(required = false) AsyncExportTaskService asyncExportTaskService,
                              @Autowired(required = false) ExportFileStorage exportFileStorage) {
        this.validator = validator;
        this.asyncExportTaskService = asyncExportTaskService;
        this.exportFileStorage = exportFileStorage;
    }

    /**
     * 获取示例用户初始模拟数据集
     *
     * @return 示例用户列表
     */
    public List<UserExcelVO> getMockUserList() {
        List<UserExcelVO> list = new ArrayList<>();
        list.add(new UserExcelVO(1001L, "张三", "13800138000", "zhangsan@example.com",
                SampleGenderEnum.MALE, "研发部", "0"));
        list.add(new UserExcelVO(1002L, "李四", "13900139000", "lisi@example.com",
                SampleGenderEnum.FEMALE, "市场部", "1"));
        list.add(new UserExcelVO(1003L, "王五", "13700137000", "wangwu@example.com",
                SampleGenderEnum.UNKNOWN, "财务部", "0"));
        return list;
    }

    /**
     * 获取示例部门员工相邻合并模拟数据集
     *
     * @return 部门员工列表
     */
    public List<DepartmentUserMergeVO> getMockDepartmentMergeList() {
        List<DepartmentUserMergeVO> list = new ArrayList<>();
        list.add(new DepartmentUserMergeVO("研发部", "张三", "Java 架构师"));
        list.add(new DepartmentUserMergeVO("研发部", "李雷", "前端工程师"));
        list.add(new DepartmentUserMergeVO("市场部", "韩梅梅", "商务经理"));
        list.add(new DepartmentUserMergeVO("市场部", "王五", "运营专员"));
        list.add(new DepartmentUserMergeVO("财务部", "赵六", "总账会计"));
        return list;
    }

    /**
     * 流式导出用户数据至 HTTP 响应
     *
     * @param response HTTP 响应
     */
    public void exportUsers(HttpServletResponse response) {
        List<UserExcelVO> userList = getMockUserList();
        ExcelUtil.export(response, "用户列表.xlsx", UserExcelVO.class, userList);
    }

    /**
     * 使用 ExcelBuilder Fluent 流式编排多 Sheet 报表导出
     * Sheet 1: 用户清单（商务蓝主题 + 斑马纹）
     * Sheet 2: 销售汇总（极简白主题 + 动态表头）
     *
     * @param response HTTP 响应
     */
    public void exportMultiSheet(HttpServletResponse response) {
        List<UserExcelVO> userList = getMockUserList();

        List<List<String>> headList = List.of(
                List.of("销售大区"),
                List.of("季度目标"),
                List.of("实际完成")
        );
        List<List<Object>> dataList = List.of(
                List.of("华东大区", 1000000.00, 1250000.00),
                List.of("华北大区", 800000.00, 830000.00),
                List.of("华南大区", 900000.00, 960000.00)
        );

        try (ExcelBuilder builder = ExcelUtil.builder(response, "综合多工作表报表.xlsx")) {
            builder.theme(ExcelThemeEnum.BUSINESS_BLUE)
                    .zebraStripe(true)
                    .sheet("用户清单", UserExcelVO.class, userList)
                    .theme(ExcelThemeEnum.MINIMAL_CLEAN)
                    .sheetDynamic("销售汇总", headList, dataList);
            log.info("成功流式写出多 Sheet 报表: sheets=[用户清单, 销售汇总]");
        }
    }

    /**
     * 纵向相邻单元格合并导出
     *
     * @param response HTTP 响应
     */
    public void exportMerge(HttpServletResponse response) {
        List<DepartmentUserMergeVO> list = getMockDepartmentMergeList();
        ExcelUtil.export(response, "部门员工合并报表.xlsx", DepartmentUserMergeVO.class, list);
        log.info("成功流式写出纵向合并报表: rows={}", list.size());
    }

    /**
     * 下载防呆导入模板
     *
     * @param response HTTP 响应
     */
    public void downloadTemplate(HttpServletResponse response) {
        ExcelUtil.exportTemplate(response, "用户导入模板.xlsx", UserExcelVO.class);
    }

    /**
     * 流式解析并执行 JSR-303 行级校验
     *
     * @param inputStream 输入流
     * @return 校验聚合结果
     */
    public ExcelReadResult<UserExcelVO> readAndValidate(InputStream inputStream) {
        BatchValidationListener<UserExcelVO> listener = new BatchValidationListener<>(UserExcelVO.class, validator, false);
        ExcelUtil.read(inputStream, UserExcelVO.class, listener);
        return listener.toResult();
    }

    /**
     * 校验失败生成带标红单元格与浮动批注的错题集反馈 Excel
     *
     * @param fileBytes 原始文件字节
     * @param errors    行级校验错误记录
     * @param response  HTTP 响应
     * @throws IOException IO 异常
     */
    public void renderErrorFeedback(byte[] fileBytes, List<ExcelErrorRow> errors, HttpServletResponse response) throws IOException {
        try (InputStream templateStream = new ByteArrayInputStream(fileBytes)) {
            ExcelUtil.exportErrorFeedback(response, "导入错误反馈.xlsx", templateStream, errors);
        }
    }

    /**
     * 校验失败生成带标红单元格与浮动批注的错题集反馈 Excel
     *
     * @param fileBytes 原始文件字节
     * @param response  HTTP 响应
     * @throws IOException IO 异常
     */
    public void renderErrorFeedback(byte[] fileBytes, HttpServletResponse response) throws IOException {
        ExcelReadResult<UserExcelVO> result;
        try (InputStream in = new ByteArrayInputStream(fileBytes)) {
            result = readAndValidate(in);
        }
        if (!result.isSuccess()) {
            renderErrorFeedback(fileBytes, result.getErrors(), response);
        }
    }

    /**
     * 无实体动态流式导出
     *
     * @param response HTTP 响应
     */
    public void exportDynamic(HttpServletResponse response) {
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
     * 提交后台受管异步导出任务
     *
     * @param taskName 任务名称
     * @return 任务标识
     */
    public String submitAsyncExport(String taskName) {
        if (asyncExportTaskService == null) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "异步导出任务中心未就绪");
        }
        return asyncExportTaskService.submit(taskName, out -> {
            List<UserExcelVO> list = getMockUserList();
            ExcelUtil.export(out, list, UserExcelVO.class);
        });
    }

    /**
     * 查询异步导出任务详情
     *
     * @param taskId 任务 ID
     * @return 任务实体
     */
    public ExportTask getAsyncTask(String taskId) {
        if (asyncExportTaskService == null) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "异步导出任务中心未就绪");
        }
        return asyncExportTaskService.getTask(taskId);
    }

    /**
     * 流式分页与游标导出海量数据
     * 演示 StreamingPageExportTemplate 游标驱动流式写出
     *
     * @param response HTTP 响应
     */
    public void exportStreamingPage(HttpServletResponse response) {
        ExcelUtil.exportStreamingByCursor(
                response,
                "流式游标用户导出.xlsx",
                UserExcelVO.class,
                0L,
                100,
                (cursorId, size) -> {
                    if (cursorId >= 300) {
                        return List.of();
                    }
                    List<UserExcelVO> list = new ArrayList<>(size);
                    for (int i = 1; i <= size; i++) {
                        long currentId = cursorId + i;
                        list.add(new UserExcelVO(
                                currentId,
                                "游标用户_" + currentId,
                                "1380000" + String.format("%04d", currentId % 10000),
                                "user" + currentId + "@example.com",
                                (currentId % 2 == 0) ? SampleGenderEnum.MALE : SampleGenderEnum.FEMALE,
                                "研发部",
                                "0"
                        ));
                    }
                    return list;
                },
                UserExcelVO::getUserId
        );
    }

    /**
     * 下载已完成的异步导出任务文件
     *
     * @param taskId   任务 ID
     * @param response HTTP 响应
     * @throws IOException IO 异常
     */
    public void downloadAsyncTask(String taskId, HttpServletResponse response) throws IOException {
        ExportTask task = getAsyncTask(taskId);
        if (task == null) {
            throw new BusinessException(CommonResultCode.NOT_FOUND.getCode(), "未找到已完成的异步导出任务: " + taskId);
        }

        // 优先通过持久化存储 SPI 获取输入流，实现与存储介质物理透明解耦
        InputStream inputStream = null;
        if (exportFileStorage != null) {
            String storageKey = task.getStorageKey() != null ? task.getStorageKey() : task.getFilePath();
            inputStream = exportFileStorage.getInputStream(taskId, storageKey);
        }
        if (inputStream == null && task.getFilePath() != null) {
            File file = new File(task.getFilePath());
            if (file.exists()) {
                inputStream = new FileInputStream(file);
            }
        }
        if (inputStream == null) {
            throw new BusinessException(CommonResultCode.NOT_FOUND.getCode(), "目标导出文件物理不存在或无法读取: " + taskId);
        }

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String encodedName = URLEncoder.encode(task.getTaskName() + ".xlsx", StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedName + "\"; filename*=utf-8''" + encodedName);
        try (InputStream in = inputStream; OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    /**
     * 带有错误熔断阈值的流式解析校验
     *
     * @param inputStream 输入流
     * @param maxErrorRows 最大错误熔断行数
     * @return 校验聚合结果
     */
    public ExcelReadResult<UserExcelVO> readWithCircuitBreaker(InputStream inputStream, int maxErrorRows) {
        BatchValidationListener<UserExcelVO> listener = new BatchValidationListener<>(UserExcelVO.class, validator, false, maxErrorRows);
        ExcelUtil.read(inputStream, UserExcelVO.class, listener);
        return listener.toResult();
    }

    /**
     * 导出带有公式注入安全防御与列级防呆锁定的模板
     *
     * @param response HTTP 响应
     */
    public void downloadSecurityTemplate(HttpServletResponse response) {
        List<SecurityUserExcelVO> list = List.of(
                new SecurityUserExcelVO(9001L, "=cmd|'/C calc'!A0", "=SUM(A1:B1)", "P7"),
                new SecurityUserExcelVO(9002L, "+8613800000000", "=AVERAGE(C1:C10)", "P8")
        );
        ExcelUtil.export(response, "安全防呆员工名单.xlsx", SecurityUserExcelVO.class, list);
    }

    /**
     * 流式导出多语言国际化表头用户数据
     *
     * @param response HTTP 响应
     */
    public void exportI18nUsers(HttpServletResponse response) {
        List<I18nUserExcelVO> list = List.of(
                new I18nUserExcelVO(101L, "Alice", "alice@example.com", "研发部"),
                new I18nUserExcelVO(102L, "Bob", "bob@example.com", "市场部")
        );
        ExcelUtil.export(response, "多语言用户列表.xlsx", I18nUserExcelVO.class, list);
    }
}

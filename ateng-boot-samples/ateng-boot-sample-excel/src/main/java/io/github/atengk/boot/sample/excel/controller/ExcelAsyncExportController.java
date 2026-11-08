package io.github.atengk.boot.sample.excel.controller;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.excel.async.AsyncExportTaskService;
import io.github.atengk.boot.excel.async.ExportTask;
import io.github.atengk.boot.excel.async.ExportTaskStatus;
import io.github.atengk.boot.excel.async.storage.ExportFileStorage;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.sample.excel.vo.AsyncExportStatusVO;
import io.github.atengk.boot.sample.excel.vo.OrderBillVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Excel 异步大报表任务中心控制器
 * <p>
 * 提供解耦 HTTP 短连接的海量大账单异步提交、四维上下文 (X-Trace-Id) 跨线程透传校验、
 * 任务进度与状态轮询、任务主动取消释放、以及持久化产物受管流式下载端点。
 * 支持在离线自洽 (!local) 纯内存模式与分布式 (local) Redis 模式之间自适应无缝切换。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-08
 */
@RestController
@RequestMapping("/sample/excel/async")
public class ExcelAsyncExportController {

    private static final Logger log = LoggerFactory.getLogger(ExcelAsyncExportController.class);

    private final AsyncExportTaskService taskService;
    private final ExportFileStorage fileStorage;

    private final ConcurrentMap<String, String> taskCallerTraceMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> taskWorkerTraceMap = new ConcurrentHashMap<>();

    public ExcelAsyncExportController(AsyncExportTaskService taskService, ExportFileStorage fileStorage) {
        this.taskService = taskService;
        this.fileStorage = fileStorage;
    }

    /**
     * 1. 提交异步海量订单导出任务
     *
     * @param taskName 任务名称
     * @param count    模拟生成的账单数据条数
     * @param request  HTTP Servlet 请求对象（用于提取追踪头）
     * @return 包含 taskId 与提交方 traceId 的任务凭证
     */
    @PostMapping("/submit")
    public Result<Map<String, Object>> submitTask(
            @RequestParam(defaultValue = "海量交易账单异步导出") String taskName,
            @RequestParam(defaultValue = "300") int count,
            HttpServletRequest request) {

        String callerTraceId = MDC.get("traceId");
        if (callerTraceId == null || callerTraceId.isBlank()) {
            callerTraceId = request.getHeader("X-Trace-Id");
        }

        final String finalCallerTraceId = callerTraceId;
        final AtomicReference<String> taskIdHolder = new AtomicReference<>();

        String taskId = taskService.submit(taskName, out -> {
            String workerTraceId = MDC.get("traceId");
            log.info("[AsyncExport] 后台工作线程开始流式生成报表: taskName={}, count={}, workerTraceId={}",
                    taskName, count, workerTraceId);

            // 等待外层设置 taskIdHolder 以精准映射
            int retries = 0;
            while (taskIdHolder.get() == null && retries < 50) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
                retries++;
            }

            String tid = taskIdHolder.get();
            if (tid != null && workerTraceId != null) {
                taskWorkerTraceMap.put(tid, workerTraceId);
            }

            // 模拟流式生成大账单数据并写出
            List<OrderBillVO> dataList = OrderStreamingController.generateMockOrders(count);
            ExcelUtil.export(out, "交易账单", dataList, OrderBillVO.class);
            log.info("[AsyncExport] 后台工作线程完成报表流式写出: taskId={}, count={}", tid, dataList.size());
        });

        taskIdHolder.set(taskId);
        if (finalCallerTraceId != null) {
            taskCallerTraceMap.put(taskId, finalCallerTraceId);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("taskId", taskId);
        resp.put("taskName", taskName);
        resp.put("requestTraceId", finalCallerTraceId);
        resp.put("status", ExportTaskStatus.PENDING.name());
        return Result.success(resp);
    }

    /**
     * 2. 查询异步导出任务执行状态与全链路追踪详情
     *
     * @param taskId 任务唯一标识 ID
     * @return 包含状态、进度、文件大小、下载链接与跨线程 traceId 的详情 VO
     */
    @GetMapping("/status")
    public Result<AsyncExportStatusVO> getTaskStatus(@RequestParam("taskId") String taskId) {
        ExportTask task = taskService.getTask(taskId);
        if (task == null) {
            throw new BusinessException("异步导出任务不存在: " + taskId);
        }

        String requestTrace = taskCallerTraceMap.get(taskId);
        String workerTrace = taskWorkerTraceMap.get(taskId);

        AsyncExportStatusVO vo = AsyncExportStatusVO.builder()
                .taskId(task.getTaskId())
                .taskName(task.getTaskName())
                .status(task.getStatus() != null ? task.getStatus().name() : null)
                .progress(task.getProgress())
                .fileSize(task.getFileSize())
                .downloadUrl(task.getDownloadUrl())
                .errorMsg(task.getErrorMsg())
                .createTime(task.getCreateTime())
                .finishTime(task.getFinishTime())
                .requestTraceId(requestTrace)
                .workerTraceId(workerTrace)
                .build();

        return Result.success(vo);
    }

    /**
     * 3. 取消指定的异步导出任务并释放资源
     *
     * @param taskId 任务唯一标识 ID
     * @return 取消操作结果
     */
    @PostMapping("/cancel")
    public Result<Map<String, Object>> cancelTask(@RequestParam("taskId") String taskId) {
        boolean cancelled = taskService.cancel(taskId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("taskId", taskId);
        resp.put("cancelled", cancelled);
        return Result.success(resp);
    }

    /**
     * 4. 受管流式文件下载
     *
     * @param taskId   任务唯一标识 ID
     * @param response HTTP Servlet 响应对象
     * @throws Exception 下载流式异常
     */
    @GetMapping("/download")
    public void downloadExportFile(
            @RequestParam("taskId") String taskId,
            HttpServletResponse response) throws Exception {

        ExportTask task = taskService.getTask(taskId);
        if (task == null) {
            throw new BusinessException("异步导出任务不存在: " + taskId);
        }
        if (task.getStatus() != ExportTaskStatus.SUCCESS) {
            throw new BusinessException("异步导出任务尚未完成，当前状态: " + task.getStatus());
        }

        try (InputStream in = fileStorage.getInputStream(taskId, task.getStorageKey())) {
            if (in == null) {
                throw new BusinessException("异步导出文件不存在或已被自动清理: " + taskId);
            }

            String downloadFileName = (task.getTaskName() != null && !task.getTaskName().isBlank())
                    ? task.getTaskName()
                    : "异步导出报表";
            if (!downloadFileName.endsWith(".xlsx")) {
                downloadFileName += ".xlsx";
            }

            String encodedFileName = URLEncoder.encode(downloadFileName, StandardCharsets.UTF_8).replace("+", "%20");
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + encodedFileName + "\"");
            if (task.getFileSize() != null && task.getFileSize() > 0) {
                response.setContentLengthLong(task.getFileSize());
            }

            in.transferTo(response.getOutputStream());
            response.flushBuffer();
        }
    }
}

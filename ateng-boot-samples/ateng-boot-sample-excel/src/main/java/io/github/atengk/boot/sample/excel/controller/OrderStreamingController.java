package io.github.atengk.boot.sample.excel.controller;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.excel.streaming.StreamingPageExportTemplate;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.excel.util.ExcelWebUtil;
import io.github.atengk.boot.sample.excel.vo.OrderBillVO;
import io.github.atengk.boot.web.annotation.WebLog;
import jakarta.servlet.http.HttpServletResponse;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 海量数据流式分页与跨 Sheet 轮转控制器
 * <p>
 * 提供基于 PageResult 的流式分页导出、基于主键游标的零深分页导出、
 * StreamingPageExportTemplate 模板显式流式写控制，
 * 以及 readBatch 大体积文件恒定常数级内存分批消费解析。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-08
 */
@RestController
@RequestMapping("/sample/excel/streaming")
public class OrderStreamingController {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 端点 1：基于 PageResult 驱动流式分页写出，并在单表达到上限时自动无缝跨 Sheet 轮转
     *
     * @param response        HTTP Servlet 响应对象
     * @param totalRows       预期导出的总数据量（默认 250 行）
     * @param pageSize        每页拉取步长（默认 100 行）
     * @param maxRowsPerSheet 单个工作表最大行数上限（默认 100 行，触发自动分表）
     * @param fileName        导出文件名
     * @throws IOException IO 异常
     */
    @GetMapping("/export-page")
    @WebLog(value = "PageResult 流式分页跨表轮转导出", printParams = true, printResult = false, printCostTime = true)
    public void exportStreamingByPage(
            HttpServletResponse response,
            @RequestParam(value = "totalRows", defaultValue = "250") int totalRows,
            @RequestParam(value = "pageSize", defaultValue = "100") int pageSize,
            @RequestParam(value = "maxRowsPerSheet", defaultValue = "100") int maxRowsPerSheet,
            @RequestParam(value = "fileName", defaultValue = "订单流水_分页跨表轮转.xlsx") String fileName) {

        try (StreamingPageExportTemplate<OrderBillVO> template = ExcelWebUtil.streaming(response, fileName, OrderBillVO.class)) {
            template.baseSheetName("订单流水")
                    .maxRowsPerSheet(maxRowsPerSheet);

            template.exportByPage(pageNum -> fetchOrdersByPage(pageNum, pageSize, totalRows));
        }
    }

    /**
     * 端点 2：基于主键游标（Cursor）驱动海量数据流式导出（防深分页 OOM）
     *
     * @param response        HTTP Servlet 响应对象
     * @param totalRows       预期导出的总记录数（默认 250 行）
     * @param pageSize        每次游标拉取步长（默认 100 行）
     * @param maxRowsPerSheet 单工作表上限（默认 100 行）
     * @param fileName        导出文件名
     * @throws IOException IO 异常
     */
    @GetMapping("/export-cursor")
    @WebLog(value = "主键游标流式分页跨表导出", printParams = true, printResult = false, printCostTime = true)
    public void exportStreamingByCursor(
            HttpServletResponse response,
            @RequestParam(value = "totalRows", defaultValue = "250") int totalRows,
            @RequestParam(value = "pageSize", defaultValue = "100") int pageSize,
            @RequestParam(value = "maxRowsPerSheet", defaultValue = "100") int maxRowsPerSheet,
            @RequestParam(value = "fileName", defaultValue = "订单流水_游标分片导出.xlsx") String fileName) {

        try (StreamingPageExportTemplate<OrderBillVO> template = ExcelWebUtil.streaming(response, fileName, OrderBillVO.class)) {
            template.baseSheetName("交易账单")
                    .maxRowsPerSheet(maxRowsPerSheet);

            template.exportByCursor(
                    0L,
                    pageSize,
                    (cursorId, limit) -> fetchOrdersByCursor(cursorId, limit, totalRows),
                    OrderBillVO::getId
            );
        }
    }

    /**
     * 端点 1B：基于 ExcelWebUtil.toResponseEntityStreaming 返回 ResponseEntity 异步分页流式导出
     *
     * @param totalRows 预期导出的总数据量（默认 250 行）
     * @param pageSize  每页拉取步长（默认 100 行）
     * @param fileName  导出文件名
     * @return Spring 标准异步流式响应实体
     */
    @GetMapping("/export-page-entity")
    @WebLog(value = "PageResult ResponseEntity 流式分页导出", printParams = true, printResult = false, printCostTime = true)
    public ResponseEntity<StreamingResponseBody> exportStreamingByPageEntity(
            @RequestParam(value = "totalRows", defaultValue = "250") int totalRows,
            @RequestParam(value = "pageSize", defaultValue = "100") int pageSize,
            @RequestParam(value = "fileName", defaultValue = "订单流水_分页流式实体.xlsx") String fileName) {
        return ExcelWebUtil.toResponseEntityStreaming(
                fileName,
                "订单流水",
                OrderBillVO.class,
                pageNum -> fetchOrdersByPage(pageNum, pageSize, totalRows)
        );
    }

    /**
     * 端点 2B：基于 ExcelWebUtil.toResponseEntityStreamingByCursor 返回 ResponseEntity 主键游标流式导出
     *
     * @param totalRows 预期导出的总记录数（默认 250 行）
     * @param pageSize  每次游标拉取步长（默认 100 行）
     * @param fileName  导出文件名
     * @return Spring 标准异步流式响应实体
     */
    @GetMapping("/export-cursor-entity")
    @WebLog(value = "主键游标 ResponseEntity 流式导出", printParams = true, printResult = false, printCostTime = true)
    public ResponseEntity<StreamingResponseBody> exportStreamingByCursorEntity(
            @RequestParam(value = "totalRows", defaultValue = "250") int totalRows,
            @RequestParam(value = "pageSize", defaultValue = "100") int pageSize,
            @RequestParam(value = "fileName", defaultValue = "订单流水_游标流式实体.xlsx") String fileName) {
        return ExcelWebUtil.toResponseEntityStreamingByCursor(
                fileName,
                "交易账单",
                OrderBillVO.class,
                0L,
                pageSize,
                (cursorId, limit) -> fetchOrdersByCursor(cursorId, limit, totalRows),
                OrderBillVO::getId
        );
    }

    /**
     * 端点 3：显式使用 StreamingPageExportTemplate 模板执行精准流式控制导出（可选 AES-256 整表加密）
     *
     * @param response HTTP Servlet 响应对象
     * @param password 可选 AES-256 加密密码
     * @param fileName 导出文件名
     */
    @GetMapping("/export-template")
    @WebLog(value = "流式分页模板显式写控制", printParams = false, printResult = false, printCostTime = true)
    public void exportByTemplate(
            HttpServletResponse response,
            @RequestParam(value = "password", required = false) String password,
            @RequestParam(value = "fileName", defaultValue = "订单流水_模板控制导出.xlsx") String fileName) {

        try (StreamingPageExportTemplate<OrderBillVO> template = ExcelWebUtil.streaming(response, fileName, OrderBillVO.class)) {
            template.baseSheetName("标准账单")
                    .maxRowsPerSheet(200);
            if (password != null && !password.isBlank()) {
                template.encrypt(password);
            }

            template.exportByPage(pageNum -> fetchOrdersByPage(pageNum, 50, 150));
        }
    }

    /**
     * 端点 4：下载模拟订单账单 Excel 文件（默认 150 条），供快速体验流式上传分批解析
     *
     * @param response HTTP Servlet 响应对象
     * @param count    数据条数（默认 150）
     * @param fileName 导出文件名
     * @throws IOException IO 异常
     */
    @GetMapping("/sample-order-bill")
    @WebLog(value = "下载模拟订单账单样本", printParams = false, printResult = false, printCostTime = true)
    public void downloadSampleOrderBill(
            HttpServletResponse response,
            @RequestParam(value = "count", defaultValue = "150") int count,
            @RequestParam(value = "fileName", defaultValue = "订单账单流水_模拟样本.xlsx") String fileName) throws IOException {
        List<OrderBillVO> data = generateOrderBills(count);
        ExcelWebUtil.export(response, fileName, data, OrderBillVO.class);
    }

    /**
     * 端点 5：readBatch 海量文件恒定内存分批流式消费解析
     *
     * @param file      上传的 Excel 文件
     * @param batchSize 单批次消费大小（默认 50）
     * @return 消费总数摘要
     * @throws IOException IO 异常
     */
    @PostMapping(value = "/import-batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @WebLog(value = "海量订单分批流式消费", printParams = true, printResult = true, printCostTime = true)
    public Result<Map<String, Object>> importBatch(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "batchSize", defaultValue = "50") int batchSize) throws IOException {

        AtomicInteger totalRead = new AtomicInteger(0);

        try (InputStream in = file.getInputStream()) {
            ExcelUtil.readBatch(in, OrderBillVO.class, batchSize, batchList -> {
                // 每拉取 batchSize 条数据触发一次业务回调，消费后即刻清空内部引用
                totalRead.addAndGet(batchList.size());
            });
        }

        return Result.success(Map.of("totalRead", totalRead.get()));
    }

    // ==================== 业务模拟数据生成 ====================

    private PageResult<OrderBillVO> fetchOrdersByPage(int pageNum, int pageSize, int totalRows) {
        int offset = (pageNum - 1) * pageSize;
        if (offset >= totalRows) {
            return PageResult.of(Collections.emptyList(), totalRows, pageNum, pageSize);
        }
        int fetchCount = Math.min(pageSize, totalRows - offset);
        List<OrderBillVO> list = new ArrayList<>(fetchCount);
        String nowStr = LocalDateTime.now().format(TIME_FORMATTER);

        for (int i = 0; i < fetchCount; i++) {
            long id = offset + i + 1;
            list.add(OrderBillVO.builder()
                    .id(id)
                    .orderNo("ORD-" + String.format("%08d", id))
                    .customerName("客户_" + id)
                    .amount(new BigDecimal("100.00").add(BigDecimal.valueOf(id * 2.5)))
                    .paymentMethod((id % 2 == 0) ? "微信支付" : "支付宝")
                    .status((id % 5 == 0) ? "已退款" : "支付成功")
                    .createTime(nowStr)
                    .build());
        }

        return PageResult.of(list, totalRows, pageNum, pageSize);
    }

    private List<OrderBillVO> fetchOrdersByCursor(Long cursorId, int limit, int totalRows) {
        long startId = (cursorId != null ? cursorId : 0L) + 1;
        if (startId > totalRows) {
            return Collections.emptyList();
        }
        int fetchCount = (int) Math.min(limit, totalRows - startId + 1);
        return generateOrderBillsRange(startId, fetchCount);
    }

    /**
     * 按起始主键与条数按需生成企业订单账单样本，避免全量构建后切片导致的冗余内存开销
     *
     * @param startId 起始主键（含）
     * @param count   生成条数
     * @return 订单账单列表
     */
    public static List<OrderBillVO> generateOrderBillsRange(long startId, int count) {
        if (count <= 0) {
            return Collections.emptyList();
        }
        List<OrderBillVO> list = new ArrayList<>(count);
        String nowStr = LocalDateTime.now().format(TIME_FORMATTER);
        for (int i = 0; i < count; i++) {
            long id = startId + i;
            list.add(OrderBillVO.builder()
                    .id(id)
                    .orderNo("ORD-" + String.format("%08d", id))
                    .customerName("企业客户_" + id)
                    .amount(new BigDecimal("299.00").add(BigDecimal.valueOf(id * 10)))
                    .paymentMethod((id % 3 == 0) ? "对公转账" : "企业网银")
                    .status((id % 5 == 0) ? "已退款" : "已结算")
                    .createTime(nowStr)
                    .build());
        }
        return list;
    }

    /**
     * 生成指定数量的订单账单样本列表
     *
     * @param count 生成条数
     * @return 订单账单列表
     */
    public static List<OrderBillVO> generateOrderBills(int count) {
        return generateOrderBillsRange(1L, count);
    }
}

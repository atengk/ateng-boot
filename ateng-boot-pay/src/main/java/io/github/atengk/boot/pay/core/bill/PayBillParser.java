package io.github.atengk.boot.pay.core.bill;

import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.exception.PayException;
import io.github.atengk.boot.pay.model.bill.PayBillEntry;
import io.github.atengk.boot.pay.model.bill.PayBillResult;
import io.github.atengk.boot.pay.model.bill.PayBillSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 财务对账单流式解压与强类型结构化解析引擎
 * <p>
 * 面向高并发与海量对账明细场景设计，具备以下工业级特性：
 * <ol>
 *     <li><b>内存自适应魔数探测与解压</b>：自动识别 GZIP（微信支付标准）与 ZIP（支付宝标准）压缩格式，纯内存解压，绝不产生磁盘临时文件；</li>
 *     <li><b>双轨消费模式（Dual Modes）</b>：既支持全量收集为内存 {@link PayBillResult}，更提供 {@code Consumer<PayBillEntry>} 流式逐行消费，彻底杜绝海量对账单 OOM 隐患；</li>
 *     <li><b>表头自适应容错映射</b>：动态索引 CSV 列名，无缝兼容微信支付 V3 与支付宝标准业务对账单、退款账单；</li>
 *     <li><b>明细与汇总自洽分离</b>：精确剥离末尾汇总行与说明元数据，汇总缺失时自动执行流式保底累计统计。</li>
 * </ol>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
public class PayBillParser {

    private static final Logger log = LoggerFactory.getLogger(PayBillParser.class);

    private static final byte[] GZIP_MAGIC = new byte[]{(byte) 0x1f, (byte) 0x8b};
    private static final byte[] ZIP_MAGIC = new byte[]{0x50, 0x4b};

    /**
     * 解析财务对账单流为内存全量结果（默认字符集探测）
     *
     * @param inputStream 对账单原始输入流（支持 GZIP、ZIP 或未压缩 CSV）
     * @return 结构化对账单结果对象（包含汇总和明细列表）
     */
    public PayBillResult parse(InputStream inputStream) {
        return parse(inputStream, (Charset) null);
    }

    /**
     * 解析财务对账单流为内存全量结果（指定字符集）
     *
     * @param inputStream    对账单原始输入流
     * @param defaultCharset 显式指定字符集（留空默认自适应 UTF-8 / GBK）
     * @return 结构化对账单结果对象
     */
    public PayBillResult parse(InputStream inputStream, Charset defaultCharset) {
        List<PayBillEntry> entries = new ArrayList<>();
        PayBillSummary summary = parse(inputStream, defaultCharset, entries::add);
        return PayBillResult.builder()
                .summary(summary)
                .entries(entries)
                .build();
    }

    /**
     * 流式逐行解析财务对账单（零 OOM 模式，默认字符集探测）
     *
     * @param inputStream   对账单原始输入流
     * @param entryConsumer 明细行消费回调（每解析一行即刻回调，内存常驻 O(1)）
     * @return 结构化汇总表头对象
     */
    public PayBillSummary parse(InputStream inputStream, Consumer<PayBillEntry> entryConsumer) {
        return parse(inputStream, null, entryConsumer);
    }

    /**
     * 流式逐行解析财务对账单（零 OOM 模式，指定字符集）
     *
     * @param inputStream    对账单原始输入流
     * @param defaultCharset 显式指定字符集
     * @param entryConsumer  明细行消费回调
     * @return 结构化汇总表头对象
     */
    public PayBillSummary parse(InputStream inputStream, Charset defaultCharset, Consumer<PayBillEntry> entryConsumer) {
        if (inputStream == null) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "对账单输入流不能为空");
        }
        Consumer<PayBillEntry> consumer = entryConsumer != null ? entryConsumer : entry -> {};

        try {
            // 使用 BufferedInputStream 支持 mark / reset 探测魔数
            BufferedInputStream bis = new BufferedInputStream(inputStream);
            bis.mark(4);
            byte[] header = new byte[4];
            int read = bis.read(header);
            bis.reset();

            if (read >= 2 && header[0] == GZIP_MAGIC[0] && header[1] == GZIP_MAGIC[1]) {
                // 1. GZIP 格式（微信支付标准）
                log.debug("Detected GZIP compressed bill stream");
                return parseGzipStream(bis, defaultCharset, consumer);
            } else if (read >= 2 && header[0] == ZIP_MAGIC[0] && header[1] == ZIP_MAGIC[1]) {
                // 2. ZIP 格式（支付宝标准）
                log.debug("Detected ZIP compressed bill archive");
                return parseZipStream(bis, defaultCharset, consumer);
            } else {
                // 3. 原始未压缩 CSV 文本流
                log.debug("Detected plain uncompressed CSV bill stream");
                Charset charset = defaultCharset != null ? defaultCharset : StandardCharsets.UTF_8;
                return parseCsvStream(bis, charset, consumer);
            }
        } catch (PayException e) {
            throw e;
        } catch (Exception e) {
            throw new PayException(PayResultCode.PAY_BILL_PARSE_ERROR, "解析财务对账单流失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解压并解析 GZIP 输入流
     */
    private PayBillSummary parseGzipStream(InputStream in, Charset defaultCharset, Consumer<PayBillEntry> consumer) throws IOException {
        Charset charset = defaultCharset != null ? defaultCharset : StandardCharsets.UTF_8;
        try (GZIPInputStream gzipIn = new GZIPInputStream(in)) {
            return parseCsvStream(gzipIn, charset, consumer);
        }
    }

    /**
     * 解压并解析 ZIP 压缩包输入流（自动遍历内含的 CSV 文件）
     */
    private PayBillSummary parseZipStream(InputStream in, Charset defaultCharset, Consumer<PayBillEntry> consumer) throws IOException {
        Charset charset = defaultCharset != null ? defaultCharset : Charset.forName("GBK");
        PayBillSummary primarySummary = null;

        try (ZipInputStream zipIn = new ZipInputStream(in, charset)) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!name.toLowerCase().endsWith(".csv")) {
                    continue;
                }
                log.debug("Unpacking ZIP entry: {}", name);
                NonClosingInputStream ncis = new NonClosingInputStream(zipIn);
                PayBillSummary s = parseCsvStream(ncis, charset, consumer);
                if (primarySummary == null || (s.getTotalCount() != null && s.getTotalCount() > 0)) {
                    primarySummary = s;
                }
                zipIn.closeEntry();
            }
        }
        return primarySummary != null ? primarySummary : PayBillSummary.builder().build();
    }

    /**
     * 核心 CSV 文本流状态机解析逻辑
     */
    private PayBillSummary parseCsvStream(InputStream in, Charset charset, Consumer<PayBillEntry> consumer) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, charset));
        Map<String, Integer> headerMap = null;
        Map<String, Integer> summaryHeaderMap = null;
        PayChannel channel = null;
        boolean summaryMode = false;

        PayBillSummary summary = PayBillSummary.builder().build();

        long computedCount = 0;
        BigDecimal computedTotalAmount = BigDecimal.ZERO;
        BigDecimal computedSettlementAmount = BigDecimal.ZERO;
        BigDecimal computedRefundAmount = BigDecimal.ZERO;
        BigDecimal computedServiceFee = BigDecimal.ZERO;

        String rawLine;
        while ((rawLine = reader.readLine()) != null) {
            // 剥离可能的 UTF-8 BOM 标记
            if (rawLine.startsWith("\uFEFF")) {
                rawLine = rawLine.substring(1);
            }
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            // 过滤注释与分隔行（如支付宝的 # 业务明细列表、#----------------等）
            if (line.startsWith("#")) {
                parseCommentSummary(line, summary);
                continue;
            }

            // 检查是否进入了汇总表头行（如微信或支付宝末尾汇总）
            if (line.contains("总交易单数") || line.contains("总交易笔数") || line.contains("总笔数")) {
                summaryMode = true;
                summaryHeaderMap = parseHeaderMap(line);
                continue;
            }

            if (summaryMode) {
                // 汇总数值行
                parseSummaryDataLine(line, summaryHeaderMap, summary, channel);
                continue;
            }

            // 检查是否是明细表头行
            if (headerMap == null) {
                if (isDetailHeaderLine(line)) {
                    headerMap = parseHeaderMap(line);
                    channel = detectChannel(headerMap);
                    summary.setChannel(channel);
                }
                continue;
            }

            // 明细数据行解析
            PayBillEntry entry = parseEntryLine(line, headerMap, channel);
            if (entry != null) {
                computedCount++;
                if (entry.getTotalAmount() != null) {
                    computedTotalAmount = computedTotalAmount.add(entry.getTotalAmount().abs());
                }
                if (entry.getSettlementAmount() != null) {
                    computedSettlementAmount = computedSettlementAmount.add(entry.getSettlementAmount().abs());
                }
                if (entry.getRefundAmount() != null) {
                    computedRefundAmount = computedRefundAmount.add(entry.getRefundAmount().abs());
                }
                if (entry.getServiceFee() != null) {
                    computedServiceFee = computedServiceFee.add(entry.getServiceFee());
                }
                consumer.accept(entry);
            }
        }

        // 汇总保底兜底赋值
        if (summary.getTotalCount() == null) {
            summary.setTotalCount(computedCount);
        }
        if (summary.getTotalAmount() == null && computedCount > 0) {
            summary.setTotalAmount(computedTotalAmount);
        }
        if (summary.getSettlementAmount() == null && computedCount > 0) {
            summary.setSettlementAmount(computedSettlementAmount);
        }
        if (summary.getRefundAmount() == null && computedCount > 0) {
            summary.setRefundAmount(computedRefundAmount);
        }
        if (summary.getServiceFee() == null && computedCount > 0) {
            summary.setServiceFee(computedServiceFee);
        }

        return summary;
    }

    /**
     * 判断是否为明细表头行
     */
    private boolean isDetailHeaderLine(String line) {
        return line.contains("商户订单号")
                || line.contains("微信订单号")
                || line.contains("支付宝交易号")
                || line.contains("交易订单号")
                || line.contains("out_trade_no");
    }

    /**
     * 探测账单归属渠道
     */
    private PayChannel detectChannel(Map<String, Integer> headerMap) {
        if (headerMap.containsKey("微信订单号") || headerMap.containsKey("公众账号ID") || headerMap.containsKey("特约商户号")) {
            return PayChannel.WECHAT_PAY;
        }
        if (headerMap.containsKey("支付宝交易号") || headerMap.containsKey("支付宝红包（元）")) {
            return PayChannel.ALIPAY;
        }
        return null;
    }

    /**
     * 构建表头列名与索引映射字典
     */
    private Map<String, Integer> parseHeaderMap(String headerLine) {
        List<String> columns = parseCsvLine(headerLine);
        Map<String, Integer> map = new HashMap<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            String col = columns.get(i).trim();
            if (!col.isEmpty()) {
                map.put(col, i);
            }
        }
        return map;
    }

    /**
     * 解析明细行转换为强类型实体
     */
    private PayBillEntry parseEntryLine(String line, Map<String, Integer> headerMap, PayChannel channel) {
        List<String> tokens = parseCsvLine(line);
        if (tokens.isEmpty() || (tokens.size() == 1 && tokens.get(0).isEmpty())) {
            return null;
        }

        String transactionId = findToken(tokens, headerMap, "微信订单号", "支付宝交易号", "交易订单号", "流水号", "trade_no", "transaction_id");
        String outTradeNo = findToken(tokens, headerMap, "商户订单号", "业务流水号", "out_trade_no");
        String refundId = findToken(tokens, headerMap, "微信退款单号", "refund_id");
        String outRefundNo = findToken(tokens, headerMap, "商户退款单号", "退款批次号/SBN", "退款批次号", "out_refund_no");
        String tradeTime = findToken(tokens, headerMap, "交易时间", "完成时间", "创建时间", "trade_time");
        String tradeType = findToken(tokens, headerMap, "交易类型", "业务类型", "交易分类", "trade_type");
        String tradeState = findToken(tokens, headerMap, "交易状态", "trade_state");
        String subject = findToken(tokens, headerMap, "商品名称", "商品说明", "subject");
        String feeRate = findToken(tokens, headerMap, "费率", "服务费率", "fee_rate");

        BigDecimal totalAmount = parseBigDecimal(findToken(tokens, headerMap, "订单金额（元）", "订单金额", "金额(元)", "金额", "total_amount"));
        BigDecimal settlementAmount = parseBigDecimal(findToken(tokens, headerMap, "应结订单金额", "商家实收（元）", "实收金额（元）", "商家实收", "settlement_amount"));
        BigDecimal refundAmount = parseBigDecimal(findToken(tokens, headerMap, "退款金额", "退款金额（元）", "申请退款金额", "refund_amount"));
        BigDecimal serviceFee = parseBigDecimal(findToken(tokens, headerMap, "手续费", "手续费（元）", "服务费（元）", "服务费", "service_fee"));

        // 针对支付宝未显式下发 tradeState 字段的默认归一化
        if (tradeState == null || tradeState.isBlank()) {
            if (tradeType != null && tradeType.contains("退款")) {
                tradeState = "REFUND";
            } else {
                tradeState = "SUCCESS";
            }
        }

        // 收集原始映射
        Map<String, String> rawValues = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : headerMap.entrySet()) {
            int idx = entry.getValue();
            if (idx < tokens.size()) {
                rawValues.put(entry.getKey(), tokens.get(idx));
            }
        }

        return PayBillEntry.builder()
                .channel(channel)
                .tradeTime(tradeTime)
                .transactionId(transactionId)
                .outTradeNo(outTradeNo)
                .refundId(refundId)
                .outRefundNo(outRefundNo)
                .tradeType(tradeType)
                .tradeState(tradeState)
                .totalAmount(totalAmount)
                .settlementAmount(settlementAmount)
                .refundAmount(refundAmount)
                .serviceFee(serviceFee)
                .feeRate(feeRate)
                .subject(subject)
                .rawValues(rawValues)
                .build();
    }

    /**
     * 解析末尾汇总数据行
     */
    private void parseSummaryDataLine(String line, Map<String, Integer> summaryHeaderMap, PayBillSummary summary, PayChannel channel) {
        List<String> tokens = parseCsvLine(line);
        if (tokens.isEmpty()) {
            return;
        }

        Long totalCount = parseLong(findToken(tokens, summaryHeaderMap, "总交易单数", "总交易笔数", "总笔数"));
        BigDecimal totalAmount = parseBigDecimal(findToken(tokens, summaryHeaderMap, "订单总金额", "总交易金额（元）", "总交易金额", "总金额"));
        BigDecimal settlementAmount = parseBigDecimal(findToken(tokens, summaryHeaderMap, "应结订单总金额", "总商家实收（元）", "总商家实收", "实收总额"));
        BigDecimal refundAmount = parseBigDecimal(findToken(tokens, summaryHeaderMap, "退款总金额", "总退款金额（元）", "总退款金额"));
        BigDecimal serviceFee = parseBigDecimal(findToken(tokens, summaryHeaderMap, "手续费总金额", "总服务费（元）", "总服务费"));

        if (totalCount != null) {
            summary.setTotalCount(totalCount);
        }
        if (totalAmount != null) {
            summary.setTotalAmount(totalAmount);
        }
        if (settlementAmount != null) {
            summary.setSettlementAmount(settlementAmount);
        }
        if (refundAmount != null) {
            summary.setRefundAmount(refundAmount);
        }
        if (serviceFee != null) {
            summary.setServiceFee(serviceFee);
        }
        if (channel != null && summary.getChannel() == null) {
            summary.setChannel(channel);
        }

        if (summaryHeaderMap != null) {
            for (Map.Entry<String, Integer> entry : summaryHeaderMap.entrySet()) {
                int idx = entry.getValue();
                if (idx < tokens.size()) {
                    summary.getRawSummary().put(entry.getKey(), tokens.get(idx));
                }
            }
        }
    }

    /**
     * 针对部分包含在注释中的汇总信息进行正则/文本轻量抽取
     */
    private void parseCommentSummary(String line, PayBillSummary summary) {
        if (!line.contains("：") && !line.contains(":")) {
            return;
        }
        String content = line.substring(1).trim();
        String[] parts = content.split("[:：]", 2);
        if (parts.length == 2) {
            String key = parts[0].trim();
            String val = parts[1].trim();
            summary.getRawSummary().put(key, val);
            if (key.contains("总交易笔数") || key.contains("总笔数")) {
                summary.setTotalCount(parseLong(val));
            } else if (key.contains("总交易金额")) {
                summary.setTotalAmount(parseBigDecimal(val));
            }
        }
    }

    /**
     * 依据优先列别名在行数据中查找单元格文本
     */
    private String findToken(List<String> tokens, Map<String, Integer> headerMap, String... candidateNames) {
        if (headerMap == null || tokens == null) {
            return null;
        }
        for (String candidate : candidateNames) {
            Integer idx = headerMap.get(candidate);
            if (idx != null && idx < tokens.size()) {
                String val = tokens.get(idx);
                if (val != null && !val.isBlank()) {
                    return val.trim();
                }
            }
        }
        return null;
    }

    /**
     * 纯原生高性能无依赖 CSV 行解析状态机（支持双引号包裹与逗号转义、自动剥离反引号）
     */
    private static List<String> parseCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                tokens.add(cleanCell(sb.toString()));
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        tokens.add(cleanCell(sb.toString()));
        return tokens;
    }

    /**
     * 清理单元格内容：剥离前后空格、前后双引号以及微信特有的前缀反引号（`）
     */
    private static String cleanCell(String cell) {
        if (cell == null) {
            return "";
        }
        String s = cell.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.startsWith("`")) {
            s = s.substring(1).trim();
        }
        return s;
    }

    private static BigDecimal parseBigDecimal(String val) {
        if (val == null || val.isBlank() || "--".equals(val) || "-".equals(val)) {
            return null;
        }
        try {
            return new BigDecimal(val.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Long parseLong(String val) {
        if (val == null || val.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(val.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 内部包装流：在遍历 ZipInputStream 的各 ZipEntry 时，防止 reader.close() 关闭外层 zipIn 流
     */
    private static class NonClosingInputStream extends FilterInputStream {
        protected NonClosingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // 保持静默，不关闭底层的 ZipInputStream
        }
    }
}

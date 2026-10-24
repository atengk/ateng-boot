package io.github.atengk.boot.pay.core.bill;

import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.exception.PayException;
import io.github.atengk.boot.pay.model.bill.PayBillEntry;
import io.github.atengk.boot.pay.model.bill.PayBillResult;
import io.github.atengk.boot.pay.model.bill.PayBillSummary;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 财务对账单流式解压与结构化解析引擎单元测试
 *
 * @author Ateng
 * @since 2026-10-24
 */
class PayBillParserTest {

    private static final Path FIXTURES_DIR = Path.of("src/test/resources/fixtures/bill");
    private final PayBillParser parser = new PayBillParser();

    @BeforeAll
    static void setUpFixtures() throws Exception {
        Files.createDirectories(FIXTURES_DIR);

        Path wechatCsv = FIXTURES_DIR.resolve("wechat_trade_bill.csv");
        Path wechatGz = FIXTURES_DIR.resolve("wechat_trade_bill.csv.gz");
        if (Files.exists(wechatCsv) && !Files.exists(wechatGz)) {
            try (InputStream in = Files.newInputStream(wechatCsv);
                 OutputStream out = new GZIPOutputStream(Files.newOutputStream(wechatGz))) {
                in.transferTo(out);
            }
        }

        Path alipayCsv = FIXTURES_DIR.resolve("alipay_trade_bill.csv");
        Path alipayZip = FIXTURES_DIR.resolve("alipay_trade_bill.zip");
        if (Files.exists(alipayCsv) && !Files.exists(alipayZip)) {
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(alipayZip), StandardCharsets.UTF_8)) {
                zos.putNextEntry(new ZipEntry("2088000000000000_20261024_业务明细.csv"));
                Files.copy(alipayCsv, zos);
                zos.closeEntry();
            }
        }
    }

    @Test
    @DisplayName("微信 GZIP 对账单流式解压与内存解析: 正确解压并映射明细和汇总")
    void testParse_wechatGzipStream() throws Exception {
        Path wechatGz = FIXTURES_DIR.resolve("wechat_trade_bill.csv.gz");
        assertTrue(Files.exists(wechatGz), "微信 GZIP 测试夹具必须存在");

        try (InputStream in = Files.newInputStream(wechatGz)) {
            PayBillResult result = parser.parse(in);

            assertNotNull(result);
            assertEquals(3, result.size());
            assertFalse(result.isEmpty());

            // 1. 验证明细 1
            PayBillEntry entry1 = result.getEntries().get(0);
            assertEquals(PayChannel.WECHAT_PAY, entry1.getChannel());
            assertEquals("4200000000202610240000000001", entry1.getTransactionId());
            assertEquals("WX_OUT_001", entry1.getOutTradeNo());
            assertEquals("JSAPI", entry1.getTradeType());
            assertEquals("SUCCESS", entry1.getTradeState());
            assertEquals(new BigDecimal("100.00"), entry1.getTotalAmount());
            assertEquals(new BigDecimal("100.00"), entry1.getSettlementAmount());
            assertEquals(new BigDecimal("0.60"), entry1.getServiceFee());
            assertEquals("0.60%", entry1.getFeeRate());
            assertEquals("测试商品A", entry1.getSubject());

            // 2. 验证退款明细 3
            PayBillEntry entry3 = result.getEntries().get(2);
            assertEquals("4200000000202610240000000003", entry3.getTransactionId());
            assertEquals("WX_OUT_003", entry3.getOutTradeNo());
            assertEquals("5000000000202610240000000001", entry3.getRefundId());
            assertEquals("WX_REF_001", entry3.getOutRefundNo());
            assertEquals("REFUND", entry3.getTradeState());
            assertEquals(new BigDecimal("20.00"), entry3.getRefundAmount());
            assertEquals(new BigDecimal("-0.12"), entry3.getServiceFee());

            // 3. 验证汇总
            PayBillSummary summary = result.getSummary();
            assertNotNull(summary);
            assertEquals(PayChannel.WECHAT_PAY, summary.getChannel());
            assertEquals(3L, summary.getTotalCount());
            assertEquals(new BigDecimal("150.00"), summary.getSettlementAmount());
            assertEquals(new BigDecimal("20.00"), summary.getRefundAmount());
            assertEquals(new BigDecimal("0.78"), summary.getServiceFee());
            assertEquals(new BigDecimal("170.00"), summary.getTotalAmount());
        }
    }

    @Test
    @DisplayName("支付宝 ZIP 对账单流式解压与零 OOM 逐行消费: 纯内存流式回调")
    void testParse_alipayZipStreamConsumer() throws Exception {
        Path alipayZip = FIXTURES_DIR.resolve("alipay_trade_bill.zip");
        assertTrue(Files.exists(alipayZip), "支付宝 ZIP 测试夹具必须存在");

        List<PayBillEntry> streamedEntries = new ArrayList<>();
        PayBillSummary summary;

        try (InputStream in = Files.newInputStream(alipayZip)) {
            summary = parser.parse(in, StandardCharsets.UTF_8, streamedEntries::add);
        }

        assertNotNull(summary);
        assertEquals(3, streamedEntries.size());

        // 验证支付宝第一笔正向交易
        PayBillEntry entry1 = streamedEntries.get(0);
        assertEquals(PayChannel.ALIPAY, entry1.getChannel());
        assertEquals("2026102422001412340000123456", entry1.getTransactionId());
        assertEquals("ALI_OUT_001", entry1.getOutTradeNo());
        assertEquals("交易", entry1.getTradeType());
        assertEquals("SUCCESS", entry1.getTradeState());
        assertEquals(new BigDecimal("28.00"), entry1.getTotalAmount());
        assertEquals(new BigDecimal("27.83"), entry1.getSettlementAmount());
        assertEquals(new BigDecimal("0.17"), entry1.getServiceFee());
        assertEquals("拿铁咖啡", entry1.getSubject());

        // 验证支付宝退款明细
        PayBillEntry refundEntry = streamedEntries.get(2);
        assertEquals("2026102422001412340000123458", refundEntry.getTransactionId());
        assertEquals("ALI_OUT_003", refundEntry.getOutTradeNo());
        assertEquals("ALI_REF_001", refundEntry.getOutRefundNo());
        assertEquals("退款", refundEntry.getTradeType());
        assertEquals("REFUND", refundEntry.getTradeState());
        assertEquals(new BigDecimal("-28.00"), refundEntry.getTotalAmount());
        assertEquals(new BigDecimal("-0.17"), refundEntry.getServiceFee());

        // 验证汇总行解析
        assertEquals(3L, summary.getTotalCount());
        assertEquals(new BigDecimal("50.00"), summary.getTotalAmount());
        assertEquals(new BigDecimal("49.70"), summary.getSettlementAmount());
        assertEquals(new BigDecimal("28.00"), summary.getRefundAmount());
        assertEquals(new BigDecimal("0.30"), summary.getServiceFee());
    }

    @Test
    @DisplayName("未压缩纯 CSV 文本流: 自动探测并正确解析")
    void testParse_plainCsvStream() throws Exception {
        Path wechatCsv = FIXTURES_DIR.resolve("wechat_trade_bill.csv");
        try (InputStream in = Files.newInputStream(wechatCsv)) {
            PayBillResult result = parser.parse(in);
            assertNotNull(result);
            assertEquals(3, result.size());
            assertEquals(PayChannel.WECHAT_PAY, result.getSummary().getChannel());
        }
    }

    @Test
    @DisplayName("空流与异常防御: 输入流为 null 时 Fail-Fast")
    void testParse_nullStreamThrows() {
        PayException ex = assertThrows(PayException.class, () -> parser.parse(null));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, ex.getResultCode());
    }

    @Test
    @DisplayName("空文本流: 返回保底空结果，不抛异常")
    void testParse_emptyStream() {
        ByteArrayInputStream emptyStream = new ByteArrayInputStream(new byte[0]);
        PayBillResult result = parser.parse(emptyStream);
        assertNotNull(result);
        assertEquals(0, result.size());
        assertTrue(result.isEmpty());
        assertNotNull(result.getEntries());
        assertEquals(0L, result.getSummary().getTotalCount());
    }
}

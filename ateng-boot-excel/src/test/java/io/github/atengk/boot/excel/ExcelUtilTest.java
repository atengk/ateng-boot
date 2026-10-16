package io.github.atengk.boot.excel;

import io.github.atengk.boot.excel.model.DemoUser;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExcelUtil 核心门面测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
class ExcelUtilTest {

    private List<DemoUser> mockUsers(int count) {
        Date truncatedNow = new Date((System.currentTimeMillis() / 1000) * 1000);
        List<DemoUser> list = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            list.add(DemoUser.builder()
                    .id((long) i)
                    .username("用户_" + i)
                    .age(20 + (i % 10))
                    .balance(new BigDecimal("100.50").add(BigDecimal.valueOf(i)))
                    .createTime(truncatedNow)
                    .build());
        }
        return list;
    }

    @Test
    @DisplayName("测试基础流式导出到 OutputStream 并成功反向解析")
    void testExportToOutputStreamAndRead() {
        List<DemoUser> expected = mockUsers(10);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 导出
        ExcelUtil.export(out, expected, DemoUser.class);
        byte[] bytes = out.toByteArray();
        assertTrue(bytes.length > 0, "导出的 Excel 二进制内容不应为空");

        // 反向读取
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        List<DemoUser> actual = ExcelUtil.read(in, DemoUser.class);

        assertNotNull(actual);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).getId(), actual.get(i).getId());
            assertEquals(expected.get(i).getUsername(), actual.get(i).getUsername());
            assertEquals(expected.get(i).getAge(), actual.get(i).getAge());
            assertEquals(expected.get(i).getBalance().stripTrailingZeros(), actual.get(i).getBalance().stripTrailingZeros());
            assertNotNull(actual.get(i).getCreateTime());
            assertEquals(expected.get(i).getCreateTime().getTime() / 1000, actual.get(i).getCreateTime().getTime() / 1000);
        }
    }

    @Test
    @DisplayName("测试 Web 环境导出：校验 Content-Type、防乱码头部与流式内容")
    void testExportToWebResponse() throws Exception {
        List<DemoUser> expected = mockUsers(5);
        MockHttpServletResponse response = new MockHttpServletResponse();
        String fileName = "用户导出清单";

        // Web 导出
        ExcelUtil.export(response, fileName, expected, DemoUser.class);

        // 断言响应头
        assertTrue(response.getContentType().contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        assertEquals("UTF-8", response.getCharacterEncoding());

        String contentDisposition = response.getHeader("Content-Disposition");
        assertNotNull(contentDisposition);
        assertTrue(contentDisposition.contains("attachment"));
        assertTrue(contentDisposition.contains("filename"));
        // 校验 RFC 5987 标准的 UTF-8 转码标记
        assertTrue(contentDisposition.contains("filename*=") || contentDisposition.contains("filename="));

        // 反向校验响应内容
        byte[] content = response.getContentAsByteArray();
        assertTrue(content.length > 0);

        List<DemoUser> actual = ExcelUtil.read(new ByteArrayInputStream(content), DemoUser.class);
        assertEquals(5, actual.size());
        assertEquals("用户_1", actual.get(0).getUsername());
    }

    @Test
    @DisplayName("测试流式分批读取与即时清空缓冲区")
    void testReadBatch() {
        List<DemoUser> expected = mockUsers(25);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, expected, DemoUser.class);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        List<DemoUser> collected = new ArrayList<>();
        AtomicInteger batchCount = new AtomicInteger();

        // 每次 10 条进行分批消费
        ExcelUtil.readBatch(in, DemoUser.class, 10, batch -> {
            batchCount.incrementAndGet();
            collected.addAll(batch);
        });

        // 25 条数据，按 10 分批，应消费 3 批（10, 10, 5）
        assertEquals(3, batchCount.get(), "总批次数量应为 3");
        assertEquals(25, collected.size(), "总读取记录数应为 25");
        assertEquals("用户_25", collected.get(24).getUsername());

        // 测试默认批次读取重载
        ByteArrayInputStream inDefault = new ByteArrayInputStream(out.toByteArray());
        List<DemoUser> collectedDefault = new ArrayList<>();
        ExcelUtil.readBatch(inDefault, DemoUser.class, collectedDefault::addAll);
        assertEquals(25, collectedDefault.size(), "默认批次消费总记录数应为 25");
    }

    @Test
    @DisplayName("测试防御性参数校验：空参数阻断")
    void testDefensiveNullChecks() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);

        assertThrows(IllegalArgumentException.class, () -> ExcelUtil.export(null, List.of(), DemoUser.class));
        assertThrows(IllegalArgumentException.class, () -> ExcelUtil.export(out, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> ExcelUtil.read(null, DemoUser.class));
        assertThrows(IllegalArgumentException.class, () -> ExcelUtil.read(in, null));
    }
}

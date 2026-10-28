package io.github.atengk.boot.excel;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.excel.model.DemoUser;
import io.github.atengk.boot.excel.util.ExcelSecurityUtil;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.excel.util.ExcelWebUtil;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ExcelWebUtil 响应门面与客户端中断防卫单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("ExcelWebUtil 响应门面与客户端中断防卫测试")
class ExcelWebUtilTest {

    private List<DemoUser> mockUsers(int count) {
        Date now = new Date(System.currentTimeMillis() / 1000 * 1000);
        List<DemoUser> list = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            list.add(DemoUser.builder()
                    .id((long) i)
                    .username("用户_" + i)
                    .age(20 + (i % 10))
                    .balance(new BigDecimal("100.50").add(BigDecimal.valueOf(i)))
                    .createTime(now)
                    .build());
        }
        return list;
    }

    // ==================== 1. HTTP 响应头装配测试 ====================

    @Test
    @DisplayName("测试 ASCII 文件名下载响应头生成与 RFC 5987 / RFC 6266 标准")
    void testCreateDownloadHeaders_AsciiFileName() {
        HttpHeaders headers = ExcelWebUtil.createDownloadHeaders("export_user.xlsx");

        assertNotNull(headers);
        assertEquals(ExcelWebUtil.XLSX_MEDIA_TYPE, headers.getContentType());
        assertEquals("no-cache, no-store, must-revalidate", headers.getCacheControl());
        assertEquals("no-cache", headers.getPragma());
        assertEquals(0L, headers.getExpires());

        String disposition = headers.getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(disposition);
        assertTrue(disposition.contains("attachment"));
        assertTrue(disposition.contains("filename*=") && disposition.contains("export_user.xlsx"));
    }

    @Test
    @DisplayName("测试中文文件名自动补全后缀与 RFC 5987 UTF-8 编码格式")
    void testCreateDownloadHeaders_ChineseFileName() {
        HttpHeaders headers = ExcelWebUtil.createDownloadHeaders("用户数据报表");

        assertNotNull(headers);
        String disposition = headers.getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(disposition);
        assertTrue(disposition.contains("attachment"));
        assertTrue(disposition.contains("filename*="));
        assertTrue(disposition.contains("UTF-8''"));
        assertTrue(disposition.contains(".xlsx"));
    }

    @Test
    @DisplayName("测试 prepareWebResponse 配置 HttpServletResponse 响应属性")
    void testPrepareWebResponse() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ExcelWebUtil.prepareWebResponse(response, "业务清单");

        assertEquals(ExcelWebUtil.XLSX_CONTENT_TYPE, response.getContentType());
        assertEquals(StandardCharsets.UTF_8.name(), response.getCharacterEncoding());
        String disposition = response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(disposition);
        assertTrue(disposition.contains("attachment"));
        assertTrue(disposition.contains("filename*=") || disposition.contains("filename="));
        assertTrue(disposition.contains(".xlsx"));
        assertEquals("no-cache, no-store, must-revalidate", response.getHeader(HttpHeaders.CACHE_CONTROL));
        assertEquals("no-cache", response.getHeader(HttpHeaders.PRAGMA));
    }

    // ==================== 2. ResponseEntity<StreamingResponseBody> 导出测试 ====================

    @Test
    @DisplayName("测试 toResponseEntity 基础数据导出与反向解析验证")
    void testToResponseEntity_BasicListExport() throws Exception {
        List<DemoUser> expected = mockUsers(10);
        ResponseEntity<StreamingResponseBody> entity = ExcelWebUtil.toResponseEntity("user_list.xlsx", expected, DemoUser.class);

        assertEquals(HttpStatus.OK, entity.getStatusCode());
        assertEquals(ExcelWebUtil.XLSX_MEDIA_TYPE, entity.getHeaders().getContentType());
        assertNotNull(entity.getBody());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        entity.getBody().writeTo(baos);
        byte[] bytes = baos.toByteArray();
        assertTrue(bytes.length > 0, "导出字节不应为空");

        List<DemoUser> actual = ExcelUtil.read(new ByteArrayInputStream(bytes), DemoUser.class);
        assertEquals(expected.size(), actual.size());
        assertEquals("用户_1", actual.get(0).getUsername());
    }

    @Test
    @DisplayName("测试 toResponseEntity 动态列投影裁剪导出")
    void testToResponseEntity_ColumnProjection() throws Exception {
        List<DemoUser> expected = mockUsers(5);
        ResponseEntity<StreamingResponseBody> entity = ExcelWebUtil.toResponseEntity(
                "projection.xlsx", "UserSheet", expected, DemoUser.class, List.of("id", "username"), null);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        entity.getBody().writeTo(baos);
        byte[] bytes = baos.toByteArray();
        assertTrue(bytes.length > 0);

        List<DemoUser> actual = ExcelUtil.read(new ByteArrayInputStream(bytes), DemoUser.class);
        assertEquals(5, actual.size());
        assertNotNull(actual.get(0).getId());
        assertNotNull(actual.get(0).getUsername());
    }

    @Test
    @DisplayName("测试 toResponseEntity 整工作簿 AES-256 强加密导出与解密验证")
    void testToResponseEntity_EncryptedExport(@TempDir Path tempDir) throws Exception {
        List<DemoUser> expected = mockUsers(3);
        String password = "WebSecretPassword@2026";
        ResponseEntity<StreamingResponseBody> entity = ExcelWebUtil.toResponseEntity(
                "secret.xlsx", "Confidential", expected, DemoUser.class, password);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        entity.getBody().writeTo(baos);
        byte[] cipherBytes = baos.toByteArray();
        assertTrue(cipherBytes.length > 0);

        // 验证无密码或错误密码无法直接通过 Fesod 打开
        assertThrows(Exception.class, () -> ExcelUtil.read(new ByteArrayInputStream(cipherBytes), DemoUser.class));

        // 验证使用正确密码解密后可正常读取
        ByteArrayOutputStream plainBaos = new ByteArrayOutputStream();
        ExcelSecurityUtil.decryptWorkbook(new ByteArrayInputStream(cipherBytes), plainBaos, password);
        List<DemoUser> actual = ExcelUtil.read(new ByteArrayInputStream(plainBaos.toByteArray()), DemoUser.class);
        assertEquals(3, actual.size());
        assertEquals("用户_1", actual.get(0).getUsername());
    }

    @Test
    @DisplayName("测试 toResponseEntityDynamic 动态 Map 导出")
    void testToResponseEntityDynamic() throws Exception {
        List<String> headList = List.of("用户编号", "用户姓名");
        List<String> dataKeys = List.of("id", "name");
        List<Map<String, Object>> dataList = List.of(
                Map.of("id", "1001", "name", "张三"),
                Map.of("id", "1002", "name", "李四")
        );

        ResponseEntity<StreamingResponseBody> entity = ExcelWebUtil.toResponseEntityDynamic(
                "dynamic_map.xlsx", "Sheet1", headList, dataKeys, dataList);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        entity.getBody().writeTo(baos);
        assertTrue(baos.toByteArray().length > 0);
    }

    @Test
    @DisplayName("测试 toResponseEntityTemplate 空白导入模板导出")
    void testToResponseEntityTemplate() throws Exception {
        ResponseEntity<StreamingResponseBody> entity = ExcelWebUtil.toResponseEntityTemplate("import_tpl.xlsx", DemoUser.class);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        entity.getBody().writeTo(baos);
        assertTrue(baos.toByteArray().length > 0);
    }

    @Test
    @DisplayName("测试 toResponseEntityWithWatermark 水印导出")
    void testToResponseEntityWithWatermark() throws Exception {
        List<DemoUser> expected = mockUsers(3);
        ResponseEntity<StreamingResponseBody> entity = ExcelWebUtil.toResponseEntityWithWatermark(
                "watermark.xlsx", expected, DemoUser.class, "内部绝密 严禁外传");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        entity.getBody().writeTo(baos);
        assertTrue(baos.toByteArray().length > 0);
    }

    // ==================== 3. 确定性生命周期与资源清理钩子 (cleanupHook) ====================

    @Test
    @DisplayName("测试正常完成流式写出后无条件触发 cleanupHook")
    void testCleanupHook_OnSuccess() throws Exception {
        AtomicBoolean cleanupRan = new AtomicBoolean(false);
        ResponseEntity<StreamingResponseBody> entity = ExcelWebUtil.toResponseEntity(
                "hook_success.xlsx",
                out -> out.write("dummy-excel-bytes".getBytes(StandardCharsets.UTF_8)),
                () -> cleanupRan.set(true)
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        entity.getBody().writeTo(baos);

        assertTrue(cleanupRan.get(), "成功完成后 cleanupHook 必须执行");
    }

    @Test
    @DisplayName("测试客户端网络中断时抑制异常并依然确定性执行 cleanupHook")
    void testCleanupHook_OnClientAbort() {
        AtomicBoolean cleanupRan = new AtomicBoolean(false);
        ResponseEntity<StreamingResponseBody> entity = ExcelWebUtil.toResponseEntity(
                "hook_abort.xlsx",
                out -> {
                    throw new IOException("Broken pipe");
                },
                () -> cleanupRan.set(true)
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // 不向外抛出异常，优雅抑制
        assertDoesNotThrow(() -> entity.getBody().writeTo(baos));
        assertTrue(cleanupRan.get(), "客户端中断时 cleanupHook 必须执行");
    }

    @Test
    @DisplayName("测试普通业务异常抛出时仍确定性执行 cleanupHook 并向上抛出异常")
    void testCleanupHook_OnBusinessError() {
        AtomicBoolean cleanupRan = new AtomicBoolean(false);
        ResponseEntity<StreamingResponseBody> entity = ExcelWebUtil.toResponseEntity(
                "hook_error.xlsx",
                out -> {
                    throw new IllegalStateException("内部存储不可用");
                },
                () -> cleanupRan.set(true)
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assertThrows(IllegalStateException.class, () -> entity.getBody().writeTo(baos));
        assertTrue(cleanupRan.get(), "业务异常中止时 cleanupHook 必须执行");
    }

    // ==================== 4. 客户端中断多容器异常识别矩阵 ====================

    @Test
    @DisplayName("测试 isClientAbortException 特征识别矩阵")
    void testIsClientAbortExceptionMatrix() {
        assertFalse(ExcelWebUtil.isClientAbortException(null));

        // 1. 底层管道与连接中断关键字（不区分大小写）
        assertTrue(ExcelWebUtil.isClientAbortException(new IOException("Broken pipe")));
        assertTrue(ExcelWebUtil.isClientAbortException(new IOException("Connection reset by peer")));
        assertTrue(ExcelWebUtil.isClientAbortException(new IOException("An established connection was aborted by software")));
        assertTrue(ExcelWebUtil.isClientAbortException(new IOException("connection was aborted")));
        assertTrue(ExcelWebUtil.isClientAbortException(new IOException("Closed by the remote host")));
        assertTrue(ExcelWebUtil.isClientAbortException(new IOException("stream is closed")));
        assertTrue(ExcelWebUtil.isClientAbortException(new IOException("client abort occurred")));
        assertTrue(ExcelWebUtil.isClientAbortException(new IOException("Premature end of chunk coded message")));

        // 2. 嵌套在 RuntimeException / BusinessException 根因链中
        assertTrue(ExcelWebUtil.isClientAbortException(new RuntimeException("包装异常", new IOException("Broken pipe"))));
        assertTrue(ExcelWebUtil.isClientAbortException(new BusinessException(500, "中间件抛错", new SocketException("Connection reset"))));

        // 3. 知名类名模拟
        class ClientAbortException extends IOException {
            ClientAbortException(String msg) { super(msg); }
        }
        class EofException extends IOException {
            EofException(String msg) { super(msg); }
        }
        class AsyncRequestNotUsableException extends RuntimeException {
            AsyncRequestNotUsableException(String msg) { super(msg); }
        }

        assertTrue(ExcelWebUtil.isClientAbortException(new ClientAbortException("Tomcat client abort")));
        assertTrue(ExcelWebUtil.isClientAbortException(new EofException("Jetty EOF")));
        assertTrue(ExcelWebUtil.isClientAbortException(new AsyncRequestNotUsableException("Spring async unavailable")));

        // 4. 非网络中断普通异常
        assertFalse(ExcelWebUtil.isClientAbortException(new IOException("Disk quota exceeded")));
        assertFalse(ExcelWebUtil.isClientAbortException(new IllegalArgumentException("参数非法")));
        assertFalse(ExcelWebUtil.isClientAbortException(new NullPointerException("NPE")));
    }

    // ==================== 5. HttpServletResponse 直接导出防卫与存量向后兼容 ====================

    @Test
    @DisplayName("测试 HttpServletResponse 导出遇到客户端中断时静默抑制")
    void testHttpServletResponseExport_ClientAbortSuppressed() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        ServletOutputStream brokenOut = new ServletOutputStream() {
            @Override
            public boolean isReady() { return false; }
            @Override
            public void setWriteListener(WriteListener writeListener) {}
            @Override
            public void write(int b) throws IOException {
                throw new IOException("Connection reset by peer");
            }
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("Connection reset by peer");
            }
        };
        when(response.getOutputStream()).thenReturn(brokenOut);

        // 验证执行 export 时不抛出异常（客户端中断被优雅捕获并记录日志）
        assertDoesNotThrow(() -> ExcelWebUtil.export(response, "test_abort.xlsx", mockUsers(3), DemoUser.class));
    }

    @Test
    @DisplayName("测试 ExcelUtil.export(HttpServletResponse, ...) 存量方法平滑委托并兼容")
    void testExcelUtilDelegationCompatibility() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        List<DemoUser> expected = mockUsers(5);

        // 调用 ExcelUtil 存量 API
        ExcelUtil.export(response, "delegation_test.xlsx", expected, DemoUser.class);

        byte[] content = response.getContentAsByteArray();
        assertTrue(content.length > 0, "委托写出的 Excel 字节不应为空");
        List<DemoUser> actual = ExcelUtil.read(new ByteArrayInputStream(content), DemoUser.class);
        assertEquals(expected.size(), actual.size());
        assertEquals("用户_1", actual.get(0).getUsername());
    }

    @Test
    @DisplayName("测试客户端中断时整工作簿加密临时文件确定性被清理")
    void testDeterministicTempFileCleanupOnClientAbort(@TempDir Path tempDir) {
        HttpServletResponse response = mock(HttpServletResponse.class);
        ServletOutputStream abortOut = new ServletOutputStream() {
            @Override
            public boolean isReady() { return false; }
            @Override
            public void setWriteListener(WriteListener writeListener) {}
            @Override
            public void write(int b) throws IOException {
                throw new IOException("Broken pipe");
            }
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("Broken pipe");
            }
        };
        try {
            when(response.getOutputStream()).thenReturn(abortOut);
        } catch (IOException ignored) {}

        // 记录系统临时目录中已有的 ateng-excel 临时文件数量
        File systemTempDir = new File(System.getProperty("java.io.tmpdir"));
        File[] beforeFiles = systemTempDir.listFiles((dir, name) -> name.startsWith("ateng-excel-"));
        int beforeCount = (beforeFiles != null) ? beforeFiles.length : 0;

        // 执行带密码加密导出，在向响应输出写出密文时遭遇 Broken pipe
        assertDoesNotThrow(() -> ExcelWebUtil.export(
                response, "encrypted_abort.xlsx", "Sheet1", mockUsers(10), DemoUser.class,
                null, null, "StrongPassword@123"));

        // 验证无论写出是否因中断失败，临时明文文件在 finally 中均已被物理删除
        File[] afterFiles = systemTempDir.listFiles((dir, name) -> name.startsWith("ateng-excel-enc-plain-") || name.startsWith("ateng-excel-plain-"));
        int afterCount = (afterFiles != null) ? afterFiles.length : 0;
        assertEquals(0, afterCount, "流式中断后产生的临时中间文件必须确定性销毁");
    }
}

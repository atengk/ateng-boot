package io.github.atengk.boot.storage.template;

import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StorageTemplate 多文件流式打包 ZIP 测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("StorageTemplate 多文件打包 ZIP 测试")
class StorageTemplateZipTest {

    @TempDir
    Path tempDir;

    private StorageTemplate template;

    @BeforeEach
    void setUp() {
        LocalDriver driver = new LocalDriver(tempDir.toString());
        template = new StorageTemplate(driver);
    }

    @Test
    @DisplayName("多文件正常流式打包为 ZIP 并验证内容与结构")
    void testDownloadAsZip_Normal() throws Exception {
        byte[] doc1 = "Content of Doc 1".getBytes(StandardCharsets.UTF_8);
        byte[] doc2 = "Content of Doc 2".getBytes(StandardCharsets.UTF_8);
        template.upload("docs/doc1.txt", new ByteArrayInputStream(doc1));
        template.upload("docs/doc2.txt", new ByteArrayInputStream(doc2));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        template.downloadAsZip(List.of("docs/doc1.txt", "docs/doc2.txt"), out);

        byte[] zipBytes = out.toByteArray();
        assertTrue(zipBytes.length > 0);

        Map<String, byte[]> unzipped = readZipEntries(zipBytes);
        assertEquals(2, unzipped.size());
        assertArrayEquals(doc1, unzipped.get("docs/doc1.txt"));
        assertArrayEquals(doc2, unzipped.get("docs/doc2.txt"));
    }

    @Test
    @DisplayName("使用 Map 自定义 ZIP 内部文件名与相对路径")
    void testDownloadAsZip_CustomEntryNames() throws Exception {
        byte[] data1 = "Report A".getBytes(StandardCharsets.UTF_8);
        byte[] data2 = "Report B".getBytes(StandardCharsets.UTF_8);
        template.upload("raw/a.pdf", new ByteArrayInputStream(data1));
        template.upload("raw/b.pdf", new ByteArrayInputStream(data2));

        Map<String, String> pathMap = new LinkedHashMap<>();
        pathMap.put("raw/a.pdf", "reports/2026-A.pdf");
        pathMap.put("raw/b.pdf", "reports/2026-B.pdf");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        template.downloadAsZip(pathMap, out);

        Map<String, byte[]> unzipped = readZipEntries(out.toByteArray());
        assertEquals(2, unzipped.size());
        assertArrayEquals(data1, unzipped.get("reports/2026-A.pdf"));
        assertArrayEquals(data2, unzipped.get("reports/2026-B.pdf"));
    }

    @Test
    @DisplayName("打包同名条目时自动去重并安全重命名")
    void testDownloadAsZip_DuplicateEntryNames() throws Exception {
        byte[] file1 = "File 1".getBytes(StandardCharsets.UTF_8);
        byte[] file2 = "File 2".getBytes(StandardCharsets.UTF_8);
        template.upload("folder1/report.txt", new ByteArrayInputStream(file1));
        template.upload("folder2/report.txt", new ByteArrayInputStream(file2));

        Map<String, String> pathMap = new LinkedHashMap<>();
        pathMap.put("folder1/report.txt", "report.txt");
        pathMap.put("folder2/report.txt", "report.txt");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        template.downloadAsZip(pathMap, out);

        Map<String, byte[]> unzipped = readZipEntries(out.toByteArray());
        assertEquals(2, unzipped.size());
        assertTrue(unzipped.containsKey("report.txt"));
        assertTrue(unzipped.containsKey("report (1).txt"));
        assertArrayEquals(file1, unzipped.get("report.txt"));
        assertArrayEquals(file2, unzipped.get("report (1).txt"));
    }

    @Test
    @DisplayName("传入包含重复源路径的列表时自动防重命名生成两个压缩条目")
    void testDownloadAsZip_DuplicatePathsInList() throws Exception {
        byte[] doc = "Same Template Content".getBytes(StandardCharsets.UTF_8);
        template.upload("template.docx", new ByteArrayInputStream(doc));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        template.downloadAsZip(List.of("template.docx", "template.docx"), out);

        Map<String, byte[]> unzipped = readZipEntries(out.toByteArray());
        assertEquals(2, unzipped.size());
        assertTrue(unzipped.containsKey("template.docx"));
        assertTrue(unzipped.containsKey("template (1).docx"));
        assertArrayEquals(doc, unzipped.get("template.docx"));
        assertArrayEquals(doc, unzipped.get("template (1).docx"));
    }

    @Test
    @DisplayName("存在缺失文件时严格阻断抛出 OBJECT_NOT_FOUND 异常")
    void testDownloadAsZip_MissingFileStrictDefense() {
        template.uploadString("exist.txt", "I am here");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StorageException ex = assertThrows(StorageException.class, () ->
                template.downloadAsZip(List.of("exist.txt", "not-exist.txt"), out)
        );

        assertEquals(StorageResultCode.OBJECT_NOT_FOUND.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("not-exist.txt"));
    }

    @Test
    @DisplayName("Web 响应式流式打包下载自动填充响应头")
    void testDownloadAsZip_WebResponse() throws Exception {
        template.uploadString("web/file1.txt", "Hello Web 1");
        template.uploadString("web/file2.txt", "Hello Web 2");

        MockHttpServletResponse response = new MockHttpServletResponse();
        template.downloadAsZip(List.of("web/file1.txt", "web/file2.txt"), response, "批量文档打包");

        assertEquals(200, response.getStatus());
        assertEquals("application/zip", response.getContentType());
        String disposition = response.getHeader("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(disposition.contains("attachment"));
        assertTrue(disposition.contains(".zip"));

        byte[] zipBytes = response.getContentAsByteArray();
        Map<String, byte[]> unzipped = readZipEntries(zipBytes);
        assertEquals(2, unzipped.size());
    }

    private Map<String, byte[]> readZipEntries(byte[] zipBytes) throws IOException {
        Map<String, byte[]> result = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    result.put(entry.getName(), zis.readAllBytes());
                }
                zis.closeEntry();
            }
        }
        return result;
    }
}

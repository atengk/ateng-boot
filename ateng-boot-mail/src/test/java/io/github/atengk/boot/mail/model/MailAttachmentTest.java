package io.github.atengk.boot.mail.model;

import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 邮件附件领域模型单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
class MailAttachmentTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("测试文件名安全清洗与路径穿越防御")
    void testCleanFilename() {
        assertEquals("report.pdf", MailAttachment.cleanFilename("report.pdf"));
        assertEquals("report.pdf", MailAttachment.cleanFilename("/var/log/report.pdf"));
        assertEquals("report.pdf", MailAttachment.cleanFilename("C:\\Users\\admin\\report.pdf"));
        assertEquals("report.pdf", MailAttachment.cleanFilename("../../report.pdf"));
        assertEquals("attachment.dat", MailAttachment.cleanFilename(""));
        assertEquals("attachment.dat", MailAttachment.cleanFilename(null));
        assertEquals("attachment.dat", MailAttachment.cleanFilename(".."));
        assertEquals("attachment.dat", MailAttachment.cleanFilename("."));
        assertEquals("report.pdf", MailAttachment.cleanFilename("..\0/..\r\n/report\0.pdf"));
        assertEquals("attachment.dat", MailAttachment.cleanFilename(".DS_Store"));
        assertEquals("attachment.dat", MailAttachment.cleanFilename("C:\\Windows\\desktop.ini"));
    }

    @Test
    @DisplayName("测试识别系统隐性垃圾文件标记")
    void testSystemJunkDetection(@TempDir Path dir) throws IOException {
        Path dsStore = dir.resolve(".DS_Store");
        Files.writeString(dsStore, "junk");
        assertTrue(MailAttachment.of(dsStore.toFile()).isSystemJunk());
        assertTrue(MailAttachment.of("desktop.ini", new byte[]{1}).isSystemJunk());
        assertTrue(MailAttachment.of("._hidden.pdf", new byte[]{1}).isSystemJunk());
        assertFalse(MailAttachment.of("invoice.pdf", new byte[]{1}).isSystemJunk());
    }

    @Test
    @DisplayName("测试基于本地临时文件构建附件及MIME推导")
    void testOfFile(@TempDir Path tempDir) throws IOException {
        Path filePath = tempDir.resolve("financial-report.xlsx");
        Files.writeString(filePath, "test content");
        File file = filePath.toFile();

        MailAttachment attachment = MailAttachment.of(file);
        assertEquals("financial-report.xlsx", attachment.getFilename());
        assertNotNull(attachment.getSource());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", attachment.getContentType());
        assertEquals(file.length(), attachment.getSize());
    }

    @Test
    @DisplayName("测试基于字节数组构建附件")
    void testOfByteArray() {
        byte[] data = "Hello World".getBytes();
        MailAttachment attachment = MailAttachment.of("hello.txt", data);

        assertEquals("hello.txt", attachment.getFilename());
        assertEquals("text/plain", attachment.getContentType());
        assertEquals((long) data.length, attachment.getSize());
    }

    @Test
    @DisplayName("测试基于流与自定义回调的确定性资源释放")
    void testCloseWithCleanupCallback() {
        AtomicBoolean closed = new AtomicBoolean(false);
        ByteArrayInputStream bais = new ByteArrayInputStream("stream content".getBytes()) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };

        MailAttachment attachment = MailAttachment.of("stream.dat", bais);
        assertFalse(closed.get());

        attachment.close();
        assertTrue(closed.get(), "close() 必须可靠触发底层流的关闭");
    }

    @Test
    @DisplayName("测试自定义 onCleanup 钩子")
    void testCustomCleanupHook() {
        AtomicBoolean cleaned = new AtomicBoolean(false);
        MailAttachment attachment = MailAttachment.of("test.pdf", new ByteArrayResource("data".getBytes()))
                .onCleanup(() -> cleaned.set(true));

        assertFalse(cleaned.get());
        attachment.close();
        assertTrue(cleaned.get(), "close() 必须执行自定义注册的 cleanup 钩子");
    }

    @Test
    @DisplayName("测试 repeatable 标记与 ofBuffered 有界缓冲构造")
    void testRepeatableAndOfBuffered(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.txt");
        Files.writeString(file, "abc");
        assertTrue(MailAttachment.of(file.toFile()).isRepeatable());
        assertTrue(MailAttachment.of("a.txt", "abc".getBytes()).isRepeatable());
        assertTrue(MailAttachment.of("b.txt", new ByteArrayResource("abc".getBytes())).isRepeatable());
        assertFalse(MailAttachment.of("c.txt", new ByteArrayInputStream("abc".getBytes())).isRepeatable());
        assertFalse(MailAttachment.of("d.txt", new InputStreamResource(new ByteArrayInputStream("abc".getBytes()))).isRepeatable());

        AtomicBoolean closed = new AtomicBoolean(false);
        ByteArrayInputStream bais = new ByteArrayInputStream("buffered-ok".getBytes()) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        MailAttachment buffered = MailAttachment.ofBuffered("buf.txt", bais, 64);
        assertTrue(buffered.isRepeatable());
        assertEquals(11L, buffered.getSize());
        assertTrue(closed.get(), "ofBuffered 读取完毕后应立即关闭原输入流");

        MailSendException ex = assertThrows(MailSendException.class, () ->
                MailAttachment.ofBuffered("too-big.bin", new ByteArrayInputStream(new byte[100]), 50));
        assertEquals(MailResultCode.MAIL_ATTACHMENT_SIZE_EXCEEDED, ex.getResultCode());
    }
}

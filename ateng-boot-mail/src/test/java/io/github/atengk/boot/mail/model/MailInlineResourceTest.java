package io.github.atengk.boot.mail.model;

import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 邮件内联资源模型单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
class MailInlineResourceTest {

    @Test
    @DisplayName("测试 Content-ID 清洗与防卫拦截")
    void testCleanContentId() {
        assertEquals("companyLogo", MailInlineResource.cleanContentId("companyLogo"));
        assertEquals("companyLogo", MailInlineResource.cleanContentId("<companyLogo>"));
        assertEquals("header_banner", MailInlineResource.cleanContentId("  <header_banner>  "));

        assertThrows(IllegalArgumentException.class, () -> MailInlineResource.cleanContentId(null));
        assertThrows(IllegalArgumentException.class, () -> MailInlineResource.cleanContentId("   "));
        assertEquals("logoX-Evil: 1", MailInlineResource.cleanContentId("<logo\r\nX-Evil: 1\0>"));
        assertThrows(IllegalArgumentException.class, () -> MailInlineResource.cleanContentId("\r\n\0"));
    }

    @Test
    @DisplayName("测试基于本地图片文件构建内联资源")
    void testOfFile(@TempDir Path tempDir) throws IOException {
        Path imagePath = tempDir.resolve("banner.png");
        Files.write(imagePath, new byte[]{1, 2, 3, 4});
        File file = imagePath.toFile();

        MailInlineResource inline = MailInlineResource.of("<bannerImage>", file);
        assertEquals("bannerImage", inline.getContentId());
        assertEquals("image/png", inline.getContentType());
        assertEquals(file.length(), inline.getSize());
        assertNotNull(inline.getSource());
    }

    @Test
    @DisplayName("测试基于流与清理回调构建内联资源")
    void testOfInputStreamWithCleanup() {
        AtomicBoolean closed = new AtomicBoolean(false);
        ByteArrayInputStream bais = new ByteArrayInputStream(new byte[]{1, 2, 3}) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };

        MailInlineResource inline = MailInlineResource.of("qrCode", bais, "image/jpeg");
        assertEquals("qrCode", inline.getContentId());
        assertEquals("image/jpeg", inline.getContentType());
        assertFalse(closed.get());

        inline.close();
        assertTrue(closed.get(), "close() 必须可靠释放底层流");
    }

    @Test
    @DisplayName("测试内联资源 repeatable 标记与 ofBuffered 有界缓冲工厂")
    void testInlineRepeatableAndOfBuffered() {
        assertTrue(MailInlineResource.of("cid1", new byte[]{1, 2}, "image/png").isRepeatable());
        assertTrue(MailInlineResource.of("cid2", new ByteArrayResource(new byte[]{1}), "image/png").isRepeatable());
        assertFalse(MailInlineResource.of("cid3", new ByteArrayInputStream(new byte[]{1}), "image/png").isRepeatable());

        MailInlineResource buffered = MailInlineResource.ofBuffered(
                "cid4", new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), "image/png", 16);
        assertTrue(buffered.isRepeatable());
        assertEquals(4L, buffered.getSize());

        MailSendException ex = assertThrows(MailSendException.class, () ->
                MailInlineResource.ofBuffered("cid5", new ByteArrayInputStream(new byte[30]), "image/png", 10));
        assertEquals(MailResultCode.MAIL_ATTACHMENT_SIZE_EXCEEDED, ex.getResultCode());

        AtomicBoolean leakedCheckClosed = new AtomicBoolean(false);
        ByteArrayInputStream invalidCidStream = new ByteArrayInputStream(new byte[]{1, 2}) {
            @Override
            public void close() throws IOException {
                leakedCheckClosed.set(true);
                super.close();
            }
        };
        assertThrows(IllegalArgumentException.class, () ->
                MailInlineResource.ofBuffered("   ", invalidCidStream, "image/png", 16));
        assertTrue(leakedCheckClosed.get(), "ofBuffered 前置 contentId 校验失败时仍须关闭入参流");
    }
}

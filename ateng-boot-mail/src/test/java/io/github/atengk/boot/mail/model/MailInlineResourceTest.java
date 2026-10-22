package io.github.atengk.boot.mail.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}

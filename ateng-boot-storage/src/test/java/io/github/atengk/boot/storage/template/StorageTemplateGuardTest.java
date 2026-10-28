package io.github.atengk.boot.storage.template;

import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.guard.StorageGuard;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.properties.StorageGuardProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StorageTemplate 整合 StorageGuard 安全防卫与特征魔数拦截集成测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
class StorageTemplateGuardTest {

    @TempDir
    Path tempDir;

    private LocalDriver driver;
    private StorageTemplate template;

    private static final byte[] PNG_HEADER = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
    private static final byte[] JPEG_HEADER = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
    private static final byte[] PDF_HEADER = "%PDF-1.7\nSample content".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EXE_HEADER = new byte[]{0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00}; // MZ
    private static final byte[] SHELL_HEADER = "#!/bin/bash\necho hack".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        driver = new LocalDriver(tempDir.toString());
        template = new StorageTemplate(driver);
    }

    @Test
    @DisplayName("测试真实文件上传通过且流数据零截断无损写入本地磁盘")
    void testUploadValidFileZeroTruncation() throws Exception {
        StorageGuardProperties props = new StorageGuardProperties();
        props.setEnabled(true);
        template.setStorageGuard(new StorageGuard(props));

        // 构造包含 8KB 数据的测试图片
        byte[] original = new byte[8192];
        System.arraycopy(PNG_HEADER, 0, original, 0, PNG_HEADER.length);
        for (int i = PNG_HEADER.length; i < original.length; i++) {
            original[i] = (byte) (i & 0xFF);
        }
        String originalMd5 = md5Hex(original);

        StorageObject obj = template.upload("images/avatar.png", new ByteArrayInputStream(original));
        assertThat(obj).isNotNull();
        assertThat(obj.getPath()).isEqualTo("images/avatar.png");
        assertThat(obj.getSize()).isEqualTo(8192L);
        assertThat(obj.getContentType()).isEqualTo("image/png");

        // 从存储端下载验证 MD5 与原始内容 100% 相同
        try (InputStream downloaded = template.download("images/avatar.png")) {
            byte[] downloadedBytes = downloaded.readAllBytes();
            assertThat(md5Hex(downloadedBytes)).isEqualTo(originalMd5);
        }
    }

    @Test
    @DisplayName("测试伪装可执行文件上传被精准拦截并抛出 STORAGE_FILE_TYPE_MISMATCH")
    void testUploadDisguisedExecutableBlocked() {
        StorageGuardProperties props = new StorageGuardProperties();
        props.setEnabled(true);
        props.setStrictMagicCheck(false); // 即使非严格模式，只要伪装高危二进制就必须拦截！
        template.setStorageGuard(new StorageGuard(props));

        // 1. EXE 伪装为 PNG
        assertThatThrownBy(() -> template.upload("uploads/fake.png", new ByteArrayInputStream(EXE_HEADER)))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> {
                    StorageException se = (StorageException) e;
                    assertThat(se.getResultCode()).isEqualTo(StorageResultCode.STORAGE_FILE_TYPE_MISMATCH);
                });

        // 2. Shell 脚本伪装为 JPG
        assertThatThrownBy(() -> template.upload("uploads/script.jpg", new ByteArrayInputStream(SHELL_HEADER)))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> {
                    StorageException se = (StorageException) e;
                    assertThat(se.getResultCode()).isEqualTo(StorageResultCode.STORAGE_FILE_TYPE_MISMATCH);
                });
    }

    @Test
    @DisplayName("测试白名单拦截：未在 allowedExtensions 中抛出 STORAGE_FILE_NOT_ALLOWED")
    void testUploadWhitelistRestriction() {
        StorageGuardProperties props = new StorageGuardProperties();
        props.setEnabled(true);
        props.setAllowedExtensions(Set.of("png", "jpg"));
        template.setStorageGuard(new StorageGuard(props));

        // PDF 即使是合法文件也不在白名单中
        assertThatThrownBy(() -> template.upload("docs/manual.pdf", new ByteArrayInputStream(PDF_HEADER)))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> {
                    StorageException se = (StorageException) e;
                    assertThat(se.getResultCode()).isEqualTo(StorageResultCode.STORAGE_FILE_NOT_ALLOWED);
                });

        // JPG 在白名单中，正常放行
        StorageObject obj = template.upload("docs/pic.jpg", new ByteArrayInputStream(JPEG_HEADER));
        assertThat(obj).isNotNull();
    }

    @Test
    @DisplayName("测试严格模式强校验：文本内容命名为 PNG 触发 STORAGE_FILE_TYPE_MISMATCH")
    void testStrictMagicCheckMismatch() {
        StorageGuardProperties props = new StorageGuardProperties();
        props.setEnabled(true);
        props.setStrictMagicCheck(true);
        template.setStorageGuard(new StorageGuard(props));

        byte[] textContent = "Just some text, not a PNG".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> template.upload("docs/not_an_image.png", new ByteArrayInputStream(textContent)))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> {
                    StorageException se = (StorageException) e;
                    assertThat(se.getResultCode()).isEqualTo(StorageResultCode.STORAGE_FILE_TYPE_MISMATCH);
                });
    }

    @Test
    @DisplayName("测试默认防卫关闭状态下保持完全向后兼容")
    void testDefaultDisabledBackwardCompatibility() {
        // template 默认没有配置 guard 或 guard.enabled=false
        StorageObject obj = template.upload("files/script.txt", new ByteArrayInputStream("hello world".getBytes(StandardCharsets.UTF_8)));
        assertThat(obj).isNotNull();
        assertThat(template.exists("files/script.txt")).isTrue();
    }

    private String md5Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        return HexFormat.of().formatHex(md.digest(data));
    }
}

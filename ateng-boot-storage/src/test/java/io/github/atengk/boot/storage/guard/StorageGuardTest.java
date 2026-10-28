package io.github.atengk.boot.storage.guard;

import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.properties.StorageGuardProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StorageGuard 魔数特征嗅探与防伪装安全拦截单元测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
class StorageGuardTest {

    // === 真实魔数测试桩字节序列 ===
    private static final byte[] PNG_HEADER = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
    private static final byte[] JPEG_HEADER = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
    private static final byte[] GIF_HEADER = "GIF89aSomeImageData".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BMP_HEADER = new byte[]{0x42, 0x4D, 0x36, 0x00, 0x00, 0x00};
    private static final byte[] PDF_HEADER = "%PDF-1.7\nSomeContent".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP_HEADER = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x14, 0x00};
    private static final byte[] GZIP_HEADER = new byte[]{0x1F, (byte) 0x8B, 0x08, 0x00};
    private static final byte[] SEVEN_Z_HEADER = new byte[]{0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};
    private static final byte[] MP4_HEADER = new byte[]{0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D};
    private static final byte[] MP3_HEADER = new byte[]{0x49, 0x44, 0x33, 0x04, 0x00, 0x00};
    private static final byte[] OLE2_HEADER = new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final byte[] XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><root/>".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SVG_HEADER = "<svg xmlns=\"http://www.w3.org/2000/svg\"><circle/></svg>".getBytes(StandardCharsets.UTF_8);

    // === 高危恶意文件测试桩 ===
    private static final byte[] EXE_PE_HEADER = new byte[]{0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00}; // MZ
    private static final byte[] ELF_HEADER = new byte[]{0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01}; // .ELF
    private static final byte[] SHELL_HEADER = "#!/bin/bash\nrm -rf /".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CLASS_HEADER = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0x00, 0x00};

    @Test
    @DisplayName("测试常见 15+ 种标准文件头特征字节嗅探识别")
    void testSniffStandardMagicNumbers() throws Exception {
        assertThat(StorageGuard.sniff(PNG_HEADER)).isEqualTo(StorageFileType.PNG);
        assertThat(StorageGuard.sniff(JPEG_HEADER)).isEqualTo(StorageFileType.JPEG);
        assertThat(StorageGuard.sniff(GIF_HEADER)).isEqualTo(StorageFileType.GIF);
        assertThat(StorageGuard.sniff(BMP_HEADER)).isEqualTo(StorageFileType.BMP);
        assertThat(StorageGuard.sniff(PDF_HEADER)).isEqualTo(StorageFileType.PDF);
        assertThat(StorageGuard.sniff(ZIP_HEADER)).isEqualTo(StorageFileType.ZIP);
        assertThat(StorageGuard.sniff(GZIP_HEADER)).isEqualTo(StorageFileType.GZIP);
        assertThat(StorageGuard.sniff(SEVEN_Z_HEADER)).isEqualTo(StorageFileType.SEVEN_Z);
        assertThat(StorageGuard.sniff(MP4_HEADER)).isEqualTo(StorageFileType.MP4);
        assertThat(StorageGuard.sniff(MP3_HEADER)).isEqualTo(StorageFileType.MP3);
        assertThat(StorageGuard.sniff(OLE2_HEADER)).isEqualTo(StorageFileType.OLE2);
        assertThat(StorageGuard.sniff(XML_HEADER)).isEqualTo(StorageFileType.XML);
        assertThat(StorageGuard.sniff(SVG_HEADER)).isEqualTo(StorageFileType.SVG);

        // RIFF 族 (WEBP, WAV, AVI)
        byte[] webp = new byte[]{0x52, 0x49, 0x46, 0x46, 0x20, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50};
        assertThat(StorageGuard.sniff(webp)).isEqualTo(StorageFileType.WEBP);

        byte[] wav = new byte[]{0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x41, 0x56, 0x45};
        assertThat(StorageGuard.sniff(wav)).isEqualTo(StorageFileType.WAV);

        byte[] avi = new byte[]{0x52, 0x49, 0x46, 0x46, 0x50, 0x00, 0x00, 0x00, 0x41, 0x56, 0x49, 0x20};
        assertThat(StorageGuard.sniff(avi)).isEqualTo(StorageFileType.AVI);

        // 高危可执行
        assertThat(StorageGuard.sniff(EXE_PE_HEADER)).isEqualTo(StorageFileType.PE_EXE);
        assertThat(StorageGuard.sniff(ELF_HEADER)).isEqualTo(StorageFileType.ELF);
        assertThat(StorageGuard.sniff(SHELL_HEADER)).isEqualTo(StorageFileType.SHELL);
        assertThat(StorageGuard.sniff(CLASS_HEADER)).isEqualTo(StorageFileType.JAVA_CLASS);

        // 未知或空
        assertThat(StorageGuard.sniff((byte[]) null)).isNull();
        assertThat(StorageGuard.sniff((InputStream) null)).isNull();
        assertThat(StorageGuard.sniff(new byte[0])).isNull();
        assertThat(StorageGuard.sniff(new byte[]{0x01, 0x02, 0x03})).isNull();
    }

    @Test
    @DisplayName("测试基于输入流嗅探零截断回退不变量")
    void testSniffInputStreamZeroTruncation() throws IOException {
        byte[] fullContent = new byte[1024];
        System.arraycopy(PNG_HEADER, 0, fullContent, 0, PNG_HEADER.length);
        for (int i = PNG_HEADER.length; i < fullContent.length; i++) {
            fullContent[i] = (byte) (i % 256);
        }

        ByteArrayInputStream in = new ByteArrayInputStream(fullContent);
        StorageFileType type = StorageGuard.sniff(in);
        assertThat(type).isEqualTo(StorageFileType.PNG);

        // 验证读取后流指针完全复位，读取到的内容与原始数组 100% 一致
        byte[] readBack = in.readAllBytes();
        assertThat(readBack).isEqualTo(fullContent);
    }

    @Test
    @DisplayName("测试白名单拦截：未在 allowed-extensions 中触发 STORAGE_FILE_NOT_ALLOWED")
    void testAllowedExtensionsWhitelist() {
        StorageGuardProperties props = new StorageGuardProperties();
        props.setEnabled(true);
        props.setAllowedExtensions(Set.of("png", "jpg", "jpeg"));
        StorageGuard guard = new StorageGuard(props);

        // 允许的扩展名
        StorageGuard.GuardResult result = guard.inspect("avatar.png", new ByteArrayInputStream(PNG_HEADER), null);
        assertThat(result.getDetectedType()).isEqualTo(StorageFileType.PNG);

        // 未在白名单中
        assertThatThrownBy(() -> guard.inspect("document.pdf", new ByteArrayInputStream(PDF_HEADER), null))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> {
                    StorageException se = (StorageException) e;
                    assertThat(se.getResultCode()).isEqualTo(StorageResultCode.STORAGE_FILE_NOT_ALLOWED);
                });
    }

    @Test
    @DisplayName("测试伪装攻击拦截：可执行文件伪装为普通图片触发 STORAGE_FILE_TYPE_MISMATCH")
    void testCamouflageExecutableInterception() {
        StorageGuardProperties props = new StorageGuardProperties();
        props.setEnabled(true);
        // strictMagicCheck 即使为 false，依然必须强力拦截伪装的高危可执行内容！
        props.setStrictMagicCheck(false);
        StorageGuard guard = new StorageGuard(props);

        // 1. Windows PE .exe 伪装成 .png
        assertThatThrownBy(() -> guard.inspect("normal_pic.png", new ByteArrayInputStream(EXE_PE_HEADER), "image/png"))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> {
                    StorageException se = (StorageException) e;
                    assertThat(se.getResultCode()).isEqualTo(StorageResultCode.STORAGE_FILE_TYPE_MISMATCH);
                });

        // 2. Linux ELF 伪装成 .pdf
        assertThatThrownBy(() -> guard.inspect("report.pdf", new ByteArrayInputStream(ELF_HEADER), "application/pdf"))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> {
                    StorageException se = (StorageException) e;
                    assertThat(se.getResultCode()).isEqualTo(StorageResultCode.STORAGE_FILE_TYPE_MISMATCH);
                });

        // 3. Shell 脚本伪装成 .jpg
        assertThatThrownBy(() -> guard.inspect("scenery.jpg", new ByteArrayInputStream(SHELL_HEADER), "image/jpeg"))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> {
                    StorageException se = (StorageException) e;
                    assertThat(se.getResultCode()).isEqualTo(StorageResultCode.STORAGE_FILE_TYPE_MISMATCH);
                });

        // 4. Java Class 字节码伪装成 .png
        assertThatThrownBy(() -> guard.inspect("icon.png", new ByteArrayInputStream(CLASS_HEADER), "image/png"))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> {
                    StorageException se = (StorageException) e;
                    assertThat(se.getResultCode()).isEqualTo(StorageResultCode.STORAGE_FILE_TYPE_MISMATCH);
                });
    }

    @Test
    @DisplayName("测试严格魔数强校验：strictMagicCheck 开启时内容特征不匹配触发 STORAGE_FILE_TYPE_MISMATCH")
    void testStrictMagicCheckMismatch() {
        StorageGuardProperties props = new StorageGuardProperties();
        props.setEnabled(true);
        props.setStrictMagicCheck(true);
        StorageGuard guard = new StorageGuard(props);

        // 1. 普通文本内容伪装为 png
        byte[] textBytes = "hello world this is plain text".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> guard.inspect("fake_image.png", new ByteArrayInputStream(textBytes), "image/png"))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> {
                    StorageException se = (StorageException) e;
                    assertThat(se.getResultCode()).isEqualTo(StorageResultCode.STORAGE_FILE_TYPE_MISMATCH);
                });

        // 2. MP4 视频伪装成 png
        assertThatThrownBy(() -> guard.inspect("fake_video_as_pic.png", new ByteArrayInputStream(MP4_HEADER), "image/png"))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> {
                    StorageException se = (StorageException) e;
                    assertThat(se.getResultCode()).isEqualTo(StorageResultCode.STORAGE_FILE_TYPE_MISMATCH);
                });

        // 3. 真实 PNG 在严格模式下正常放行
        StorageGuard.GuardResult okResult = guard.inspect("real_image.png", new ByteArrayInputStream(PNG_HEADER), "image/png");
        assertThat(okResult.getDetectedType()).isEqualTo(StorageFileType.PNG);
        assertThat(okResult.getDetectedMimeType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("测试零损耗流包装与完整性 MD5 比对")
    void testInspectZeroLossDataIntegrity() throws Exception {
        StorageGuardProperties props = new StorageGuardProperties();
        props.setEnabled(true);
        props.setStrictMagicCheck(true);
        StorageGuard guard = new StorageGuard(props);

        // 构造一个 16KB 的大图片测试桩流
        byte[] original = new byte[16384];
        System.arraycopy(PNG_HEADER, 0, original, 0, PNG_HEADER.length);
        for (int i = PNG_HEADER.length; i < original.length; i++) {
            original[i] = (byte) (i & 0xFF);
        }

        String originalMd5 = md5Hex(original);

        StorageGuard.GuardResult result = guard.inspect("test.png", new ByteArrayInputStream(original), "image/png");
        InputStream guardedStream = result.getGuardedStream();
        assertThat(guardedStream).isNotNull();

        // 读取包装后的输入流并计算 MD5
        byte[] readBack = guardedStream.readAllBytes();
        String readBackMd5 = md5Hex(readBack);

        assertThat(readBackMd5).isEqualTo(originalMd5);
        assertThat(readBack.length).isEqualTo(original.length);
    }

    private String md5Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        return HexFormat.of().formatHex(md.digest(data));
    }
}

package io.github.atengk.boot.document.word;

import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.exception.WordRenderException;
import io.github.atengk.boot.document.word.model.StitchOptions;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WordTemplate 多文档拼装追加与只读锁定保护单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class WordTemplateStitchAndProtectTest {

    private final WordTemplate wordTemplate = new WordTemplate();

    @Test
    @DisplayName("测试两份独立 Word 文档物理无缝拼装：段落与内容连续性")
    void testStitchTwoDocuments(@TempDir Path tempDir) throws Exception {
        byte[] docBytesA = createSimpleDoc("第一章：项目总则", "本项目旨在建设高可用服务底座。");
        byte[] docBytesB = createSimpleDoc("第二章：商务条款", "付款方式为按期阶段验收结算。");

        Path targetPath = tempDir.resolve("stitched-contract.docx");
        wordTemplate.stitch(targetPath, List.of(docBytesA, docBytesB));

        assertThat(Files.exists(targetPath)).isTrue();
        assertThat(Files.size(targetPath)).isGreaterThan(0);

        try (XWPFDocument verified = new XWPFDocument(Files.newInputStream(targetPath))) {
            List<XWPFParagraph> paragraphs = verified.getParagraphs();
            String fullText = extractText(verified);

            assertThat(fullText).contains("第一章：项目总则");
            assertThat(fullText).contains("本项目旨在建设高可用服务底座。");
            assertThat(fullText).contains("第二章：商务条款");
            assertThat(fullText).contains("付款方式为按期阶段验收结算。");
            assertThat(paragraphs.size()).isGreaterThanOrEqualTo(4);
        }
    }

    @Test
    @DisplayName("测试多份文档（含表格与自定义样式）按序无缝拼装并导出为字节数组")
    void testStitchMultipleDocumentsWithTable() throws Exception {
        byte[] docA = createSimpleDoc("主合同正文", "请遵守以下采购清单及签署事项：");
        byte[] docB = createDocWithTable();
        byte[] docC = createSimpleDoc("签署附件", "双方代表签字盖章后即时生效。");

        byte[] mergedBytes = wordTemplate.stitchToBytes(docA, docB, docC);
        assertThat(mergedBytes).isNotEmpty();

        try (XWPFDocument verified = new XWPFDocument(new ByteArrayInputStream(mergedBytes))) {
            String fullText = extractText(verified);
            assertThat(fullText).contains("主合同正文");
            assertThat(fullText).contains("采购清单");
            assertThat(fullText).contains("签署附件");

            // 验证表格完整保留
            List<XWPFTable> tables = verified.getTables();
            assertThat(tables).hasSize(1);
            assertThat(tables.get(0).getRows()).hasSize(3);
            assertThat(tables.get(0).getRow(1).getCell(0).getText()).isEqualTo("高性能服务器");
        }
    }

    @Test
    @DisplayName("测试单份文档拼装退化为原样输出")
    void testStitchSingleDocument(@TempDir Path tempDir) throws Exception {
        byte[] docA = createSimpleDoc("独立单据", "只有一份文档需要流转。");
        File targetFile = tempDir.resolve("single.docx").toFile();

        wordTemplate.stitch(targetFile, Collections.singletonList(docA));
        assertThat(targetFile).exists();

        try (XWPFDocument verified = new XWPFDocument(Files.newInputStream(targetFile.toPath()))) {
            String fullText = extractText(verified);
            assertThat(fullText).contains("独立单据");
            assertThat(fullText).contains("只有一份文档需要流转。");
        }
    }

    @Test
    @DisplayName("测试 Word 文档密码锁定保护与密码校验")
    void testProtectWithPasswordAndValidation(@TempDir Path tempDir) throws Exception {
        byte[] original = createSimpleDoc("商业机密合同", "禁止任何未经授权的人员进行篡改。");
        String secretPass = "AtengSafe#2026";

        File protectedFile = tempDir.resolve("protected.docx").toFile();
        wordTemplate.protect(original, protectedFile, secretPass);

        assertThat(protectedFile).exists();

        // 验证保护状态
        assertThat(wordTemplate.isEnforcedReadonlyProtection(protectedFile)).isTrue();
        // 验证密码校验
        assertThat(wordTemplate.validateProtectionPassword(protectedFile, secretPass)).isTrue();
        assertThat(wordTemplate.validateProtectionPassword(protectedFile, "WrongPassword")).isFalse();
    }

    @Test
    @DisplayName("测试 Word 文档无密码只读锁定与解除保护")
    void testProtectWithoutPasswordAndUnprotect(@TempDir Path tempDir) throws Exception {
        byte[] original = createSimpleDoc("公开规范", "建议以只读模式查阅。");

        // 1. 无密码保护
        byte[] protectedBytes = wordTemplate.protectToBytes(original);
        assertThat(wordTemplate.isEnforcedReadonlyProtection(protectedBytes)).isTrue();

        // 2. 解除保护
        Path unprotectPath = tempDir.resolve("unprotected.docx");
        wordTemplate.unprotect(protectedBytes, unprotectPath);

        assertThat(Files.exists(unprotectPath)).isTrue();
        assertThat(wordTemplate.isEnforcedReadonlyProtection(unprotectPath)).isFalse();
    }

    @Test
    @DisplayName("对抗性防卫性测试：空入参、非法输入与边界快速失败")
    void testAdversarialEdgeCases() {
        // 1. stitch 入参校验
        assertThatThrownBy(() -> wordTemplate.stitch(null, List.of(new byte[0])))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("目标输出 target 不能为空");

        assertThatThrownBy(() -> wordTemplate.stitch(new ByteArrayOutputStream(), (List<?>) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("待拼装的 Word 文档输入列表 docxSources 不能为空");

        assertThatThrownBy(() -> wordTemplate.stitch(new ByteArrayOutputStream(), Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("待拼装的 Word 文档输入列表 docxSources 不能为空");

        List<Object> listWithNull = Collections.singletonList(null);
        assertThatThrownBy(() -> wordTemplate.stitch(new ByteArrayOutputStream(), listWithNull))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("待拼装的 Word 文档项不能为空");

        // 2. protect 入参校验
        assertThatThrownBy(() -> wordTemplate.protect(null, new ByteArrayOutputStream(), "123"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("模板输入源 source 不能为空");

        assertThatThrownBy(() -> wordTemplate.protect(new byte[10], null, "123"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("目标输出 target 不能为空");

        // 3. 校验不存在的文件路径
        assertThatThrownBy(() -> wordTemplate.protect("not/exist/file.docx", new ByteArrayOutputStream(), "123"))
                .isInstanceOf(WordRenderException.class);
    }

    @Test
    @DisplayName("双轨安全：Agile AES-256 文件级加密、解密与 isEncrypted 状态精准判定")
    void testAgileAes256EncryptDecryptAndIsEncrypted(@TempDir Path tempDir) throws Exception {
        byte[] plainBytes = createSimpleDoc("绝密战略协议", "本协议文本受 Agile AES-256 加密信封保护。");
        String password = "OpenSecret#2026";

        assertThat(wordTemplate.isEncrypted(plainBytes)).isFalse();

        // 1. AES-256 加密落盘与二进制校验
        Path encryptedPath = tempDir.resolve("encrypted.docx");
        wordTemplate.encrypt(plainBytes, encryptedPath, password);
        byte[] encryptedBytes = Files.readAllBytes(encryptedPath);

        assertThat(FileMagic.valueOf(encryptedBytes)).isEqualTo(FileMagic.OLE2);
        assertThat(wordTemplate.isEncrypted(encryptedPath)).isTrue();
        assertThat(wordTemplate.isEncrypted(encryptedBytes)).isTrue();

        // 无密码直接按明文 XWPFDocument 打开必须失败
        assertThatThrownBy(() -> new XWPFDocument(new ByteArrayInputStream(encryptedBytes)))
                .isInstanceOf(Exception.class);

        // 2. 错误密码或非加密流快速失败
        assertThatThrownBy(() -> wordTemplate.decryptToBytes(encryptedBytes, "Wrong#Pass"))
                .isInstanceOf(WordRenderException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.PASSWORD_ERROR);

        assertThatThrownBy(() -> wordTemplate.decryptToBytes(plainBytes, password))
                .isInstanceOf(WordRenderException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.ENCRYPTION_ERROR);

        assertThatThrownBy(() -> wordTemplate.encryptToBytes(plainBytes, "   "))
                .isInstanceOf(WordRenderException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.ENCRYPTION_ERROR);

        assertThatThrownBy(() -> wordTemplate.decryptToBytes(encryptedBytes, ""))
                .isInstanceOf(WordRenderException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.ENCRYPTION_ERROR);

        // 3. 正确密码解密还原并可继续文本提取
        byte[] decryptedBytes = wordTemplate.decryptToBytes(encryptedBytes, password);
        assertThat(FileMagic.valueOf(decryptedBytes)).isEqualTo(FileMagic.OOXML);
        assertThat(wordTemplate.isEncrypted(decryptedBytes)).isFalse();
        assertThat(wordTemplate.extractText(decryptedBytes)).contains("绝密战略协议").contains("Agile AES-256");

        Path decryptedPath = tempDir.resolve("decrypted.docx");
        wordTemplate.decrypt(encryptedPath, decryptedPath, password);
        assertThat(Files.exists(decryptedPath)).isTrue();
        assertThat(wordTemplate.isEncrypted(decryptedPath)).isFalse();

        // 4. 损坏二进制探测快速失败（不吞咽异常）
        assertThatThrownBy(() -> wordTemplate.isEncrypted(new byte[]{0x01, 0x02, 0x03, 0x04}))
                .isInstanceOf(WordRenderException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.ENCRYPTION_ERROR);
    }

    @Test
    @DisplayName("分页拼装：StitchOptions(pageBreakBetween=true) 在合并文档之间注入显式分页符")
    void testStitchWithPageBreakBetween() throws Exception {
        byte[] part1 = createSimpleDoc("第一部分", "第一页正文内容。");
        byte[] part2 = createSimpleDoc("第二部分", "第二页正文内容。");
        byte[] part3 = createSimpleDoc("第三部分", "第三页正文内容。");

        byte[] stitched = wordTemplate.stitchToBytes(
                List.of(part1, part2, part3),
                StitchOptions.builder().pageBreakBetween(true).build()
        );

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(stitched))) {
            String fullText = extractText(doc);
            assertThat(fullText).contains("第一部分", "第二部分", "第三部分");

            long pageBreakCount = doc.getParagraphs().stream()
                    .flatMap(p -> p.getRuns().stream())
                    .mapToLong(r -> r.getCTR().getBrList().stream()
                            .filter(br -> org.openxmlformats.schemas.wordprocessingml.x2006.main.STBrType.PAGE.equals(br.getType()))
                            .count())
                    .sum();
            assertThat(pageBreakCount).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("分页与页眉页脚拼装控制：StitchOptions 前置重载与 keepHeaderFooter=false 剥离页眉页脚")
    void testStitchOptionsPrecedingOverloadsAndKeepHeaderFooter(@TempDir Path tempDir) throws Exception {
        byte[] mainWithHeader = wordTemplate.addWatermarkToBytes(
                createSimpleDoc("主文档含页眉水印", "主体内容段落"), "CONFIDENTIAL-HDR");
        byte[] annexDoc = createSimpleDoc("附件二", "附加补充条款");
        assertThat(wordTemplate.inspectMetadata(mainWithHeader).isHasHeadersOrFooters()).isTrue();

        // 1. 默认 keepHeaderFooter=true 保留主文档页眉页脚（选项前置 List 重载）
        Path keptPath = tempDir.resolve("kept-hdr.docx");
        wordTemplate.stitch(keptPath, StitchOptions.builder().pageBreakBetween(true).build(), List.of(mainWithHeader, annexDoc));
        assertThat(wordTemplate.inspectMetadata(keptPath).isHasHeadersOrFooters()).isTrue();

        // 2. keepHeaderFooter=false 剥离页眉页脚（选项前置可变参数与字节数组重载）
        StitchOptions stripOpts = StitchOptions.builder()
                .pageBreakBetween(true)
                .keepHeaderFooter(false)
                .build();
        byte[] strippedBytes = wordTemplate.stitchToBytes(stripOpts, mainWithHeader, annexDoc);
        assertThat(wordTemplate.inspectMetadata(strippedBytes).isHasHeadersOrFooters()).isFalse();
        assertThat(wordTemplate.extractText(strippedBytes)).contains("主文档含页眉水印", "附件二");

        Path strippedPath = tempDir.resolve("stripped-hdr.docx");
        wordTemplate.stitch(strippedPath, stripOpts, mainWithHeader, annexDoc);
        assertThat(wordTemplate.inspectMetadata(strippedPath).isHasHeadersOrFooters()).isFalse();

        byte[] strippedListBytes = wordTemplate.stitchToBytes(stripOpts, List.of(mainWithHeader, annexDoc));
        assertThat(wordTemplate.inspectMetadata(strippedListBytes).isHasHeadersOrFooters()).isFalse();
    }

    private static byte[] createSimpleDoc(String title, String content) throws IOException {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            XWPFParagraph titlePara = doc.createParagraph();
            titlePara.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setText(title);
            titleRun.setBold(true);
            titleRun.setFontSize(16);

            XWPFParagraph bodyPara = doc.createParagraph();
            bodyPara.createRun().setText(content);

            doc.write(baos);
            return baos.toByteArray();
        }
    }

    private static byte[] createDocWithTable() throws IOException {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText("采购清单：");

            XWPFTable table = doc.createTable(3, 2);
            table.getRow(0).getCell(0).setText("设备名称");
            table.getRow(0).getCell(1).setText("数量");
            table.getRow(1).getCell(0).setText("高性能服务器");
            table.getRow(1).getCell(1).setText("10台");
            table.getRow(2).getCell(0).setText("三层核心交换机");
            table.getRow(2).getCell(1).setText("2台");

            doc.write(baos);
            return baos.toByteArray();
        }
    }

    private static String extractText(XWPFDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph p : doc.getParagraphs()) {
            sb.append(p.getText()).append("\n");
        }
        return sb.toString();
    }
}

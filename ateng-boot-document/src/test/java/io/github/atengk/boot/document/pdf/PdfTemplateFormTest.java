package io.github.atengk.boot.document.pdf;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.exception.PdfOperationException;
import io.github.atengk.boot.document.pdf.model.FormOptions;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PdfTemplate 交互式表单（AcroForm）填充与矢量扁平化防篡改单测用例
 *
 * @author Ateng
 * @since 2026-10-23
 */
class PdfTemplateFormTest {

    private final PdfTemplate pdfTemplate = new PdfTemplate();

    @AfterEach
    void tearDown() {
        UserContextHolder.set(null);
        MDC.clear();
    }

    @Test
    @DisplayName("测试表单填充与默认矢量扁平化：验证控件销毁与文本静态固化")
    void testFillFormWithFlattenDefault(@TempDir Path tempDir) throws Exception {
        byte[] templatePdf = createSampleFormPdf();

        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("username", "Alex Mercer");
        formData.put("role", "Security Architect");
        formData.put("agreeTerms", true);

        Path targetPdfPath = tempDir.resolve("form_flattened.pdf");
        pdfTemplate.fillForm(templatePdf, targetPdfPath, formData);

        assertThat(Files.exists(targetPdfPath)).isTrue();
        assertThat(Files.size(targetPdfPath)).isGreaterThan(0);

        // 验证文本已被固化到文档内容流中
        String extracted = pdfTemplate.extractText(targetPdfPath);
        assertThat(extracted).contains("Alex Mercer");
        assertThat(extracted).contains("Security Architect");

        // 验证不可逆扁平化断言：交互式表单结构已不复存在
        try (PDDocument doc = Loader.loadPDF(targetPdfPath.toFile())) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            boolean isFormDestroyed = (acroForm == null || acroForm.getFields().isEmpty());
            assertThat(isFormDestroyed)
                    .as("默认 flatten = true 时表单控件必须已被完全销毁或清空")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("测试保留交互式表单填报（flatten = false）：验证字段值写入与可复读")
    void testFillFormInteractiveNotFlatten(@TempDir Path tempDir) throws Exception {
        byte[] templatePdf = createSampleFormPdf();

        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("username", "John Doe");
        formData.put("role", "Cloud Engineer");
        formData.put("agreeTerms", true);

        FormOptions options = FormOptions.builder()
                .flatten(false)
                .build();

        Path targetPdfPath = tempDir.resolve("form_interactive.pdf");
        pdfTemplate.fillForm(templatePdf, targetPdfPath, formData, options);

        assertThat(Files.exists(targetPdfPath)).isTrue();

        // 验证表单仍保留交互性且字段已填入预期数据
        try (PDDocument doc = Loader.loadPDF(targetPdfPath.toFile())) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            assertThat(acroForm).isNotNull();

            PDField userField = acroForm.getField("username");
            assertThat(userField).isNotNull();
            assertThat(userField.getValueAsString()).isEqualTo("John Doe");

            PDField roleField = acroForm.getField("role");
            assertThat(roleField).isNotNull();
            assertThat(roleField.getValueAsString()).isEqualTo("Cloud Engineer");

            PDField agreeField = acroForm.getField("agreeTerms");
            assertThat(agreeField).isInstanceOf(PDCheckBox.class);
            assertThat(((PDCheckBox) agreeField).isChecked()).isTrue();
        }

        // 验证 getFormFields 契约自省能力
        Map<String, String> fields = pdfTemplate.getFormFields(targetPdfPath);
        assertThat(fields).containsEntry("username", "John Doe");
        assertThat(fields).containsEntry("role", "Cloud Engineer");
    }

    @Test
    @DisplayName("测试全局只读表单（readOnly = true）：验证全部控件锁定不可编辑")
    void testFillFormWithReadOnlyGlobal(@TempDir Path tempDir) throws Exception {
        byte[] templatePdf = createSampleFormPdf();

        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("username", "Alice");
        formData.put("role", "Tester");
        formData.put("agreeTerms", false);

        FormOptions options = FormOptions.readOnlyInteractive();

        Path targetPdfPath = tempDir.resolve("form_readonly.pdf");
        pdfTemplate.fillForm(templatePdf, targetPdfPath, formData, options);

        try (PDDocument doc = Loader.loadPDF(targetPdfPath.toFile())) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            assertThat(acroForm).isNotNull();
            for (PDField field : acroForm.getFields()) {
                assertThat(field.isReadOnly())
                        .as("字段 %s 必须为只读状态", field.getFullyQualifiedName())
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("测试细粒度只读字段（readOnlyFields）：验证局部锁定与其余字段可编辑")
    void testFillFormWithReadOnlyFieldsFineGrained(@TempDir Path tempDir) throws Exception {
        byte[] templatePdf = createSampleFormPdf();

        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("username", "Bob");
        formData.put("role", "Developer");
        formData.put("agreeTerms", true);

        FormOptions options = FormOptions.builder()
                .flatten(false)
                .readOnlyFields(Set.of("username"))
                .build();

        Path targetPdfPath = tempDir.resolve("form_fine_grained.pdf");
        pdfTemplate.fillForm(templatePdf, targetPdfPath, formData, options);

        try (PDDocument doc = Loader.loadPDF(targetPdfPath.toFile())) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            assertThat(acroForm).isNotNull();

            PDField userField = acroForm.getField("username");
            assertThat(userField.isReadOnly()).isTrue();

            PDField roleField = acroForm.getField("role");
            assertThat(roleField.isReadOnly()).isFalse();

            PDField agreeField = acroForm.getField("agreeTerms");
            assertThat(agreeField.isReadOnly()).isFalse();
        }
    }

    @Test
    @DisplayName("测试中文表单填充与弹性字体适配：验证中文顺利渲染不乱码不抛错")
    void testFillFormWithChineseCharacters(@TempDir Path tempDir) throws Exception {
        byte[] templatePdf = createSampleFormPdf();

        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("username", "张三丰");
        formData.put("role", "武当掌门兼架构师");
        formData.put("agreeTerms", true);

        Path targetPdfPath = tempDir.resolve("form_chinese.pdf");
        pdfTemplate.fillForm(templatePdf, targetPdfPath, formData);

        assertThat(Files.exists(targetPdfPath)).isTrue();
        String extracted = pdfTemplate.extractText(targetPdfPath);
        assertThat(extracted).contains("张三丰");
    }

    @Test
    @DisplayName("测试内存字节数组流式导出 fillFormToBytes 重载")
    void testFillFormToBytes() throws Exception {
        byte[] templatePdf = createSampleFormPdf();

        Map<String, Object> formData = Map.of("username", "David", "role", "DevOps", "agreeTerms", false);
        byte[] resultBytes = pdfTemplate.fillFormToBytes(templatePdf, formData);

        assertThat(resultBytes).isNotEmpty();
        assertThat(resultBytes.length).isGreaterThan(0);
    }

    @Test
    @DisplayName("测试防御性失败：当 PDF 不含 AcroForm 时抛出明确的 FORM_ERROR 业务异常")
    void testFillFormWhenNoAcroForm(@TempDir Path tempDir) throws Exception {
        // 创建一个无 AcroForm 的普通单页空白 PDF
        byte[] plainPdf;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            plainPdf = baos.toByteArray();
        }

        Map<String, Object> formData = Map.of("username", "test");
        Path targetPath = tempDir.resolve("plain_out.pdf");

        assertThatThrownBy(() -> pdfTemplate.fillForm(plainPdf, targetPath, formData))
                .isInstanceOf(PdfOperationException.class)
                .satisfies(e -> {
                    PdfOperationException poe = (PdfOperationException) e;
                    assertThat(poe.getResultCode()).isEqualTo(DocumentResultCode.FORM_ERROR);
                    assertThat(poe.getMessage()).contains("不包含 AcroForm");
                });
    }

    @Test
    @DisplayName("测试容错机制：表单数据包含不存在的字段时应安全忽略而不抛异常")
    void testFillFormNonExistentFieldIgnored(@TempDir Path tempDir) throws Exception {
        byte[] templatePdf = createSampleFormPdf();

        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("nonExistentKey1", "RandomValue");
        formData.put("username", "ValidUser");
        formData.put("nonExistentKey2", 12345);

        Path targetPdfPath = tempDir.resolve("form_ignored.pdf");
        pdfTemplate.fillForm(templatePdf, targetPdfPath, formData);

        assertThat(Files.exists(targetPdfPath)).isTrue();
        String extracted = pdfTemplate.extractText(targetPdfPath);
        assertThat(extracted).contains("ValidUser");
    }

    @Test
    @DisplayName("测试 hasAcroForm 与 getFormFields 契约探测能力")
    void testHasAcroFormAndGetFormFields() throws Exception {
        byte[] templatePdf = createSampleFormPdf();

        assertThat(pdfTemplate.hasAcroForm(templatePdf)).isTrue();
        Map<String, String> fields = pdfTemplate.getFormFields(templatePdf);
        assertThat(fields).containsKeys("username", "role", "agreeTerms");

        // 空白普通 PDF 探测断言
        byte[] plainPdf;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            plainPdf = baos.toByteArray();
        }

        assertThat(pdfTemplate.hasAcroForm(plainPdf)).isFalse();
        assertThat(pdfTemplate.getFormFields(plainPdf)).isEmpty();
    }

    /**
     * 编程式动态构造包含交互式表单（AcroForm）的离线自洽测试 PDF 模板
     */
    private static byte[] createSampleFormPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);

            PDResources resources = new PDResources();
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            COSName fontName = resources.add(font);
            acroForm.setDefaultResources(resources);
            acroForm.setDefaultAppearance("/" + fontName.getName() + " 12 Tf 0 g");

            // 1. 文本输入框 username
            PDTextField textField = new PDTextField(acroForm);
            textField.setPartialName("username");
            textField.setDefaultAppearance("/" + fontName.getName() + " 12 Tf 0 g");
            acroForm.getFields().add(textField);

            PDAnnotationWidget widget = textField.getWidgets().get(0);
            widget.setRectangle(new PDRectangle(50, 700, 200, 25));
            widget.setPage(page);
            page.getAnnotations().add(widget);

            // 2. 文本输入框 role
            PDTextField roleField = new PDTextField(acroForm);
            roleField.setPartialName("role");
            roleField.setDefaultAppearance("/" + fontName.getName() + " 12 Tf 0 g");
            acroForm.getFields().add(roleField);

            PDAnnotationWidget roleWidget = roleField.getWidgets().get(0);
            roleWidget.setRectangle(new PDRectangle(50, 650, 200, 25));
            roleWidget.setPage(page);
            page.getAnnotations().add(roleWidget);

            // 3. 复选框 agreeTerms
            PDCheckBox checkBox = new PDCheckBox(acroForm);
            checkBox.setPartialName("agreeTerms");
            acroForm.getFields().add(checkBox);

            PDAnnotationWidget cbWidget = checkBox.getWidgets().get(0);
            cbWidget.setRectangle(new PDRectangle(50, 600, 20, 20));
            cbWidget.setPage(page);
            page.getAnnotations().add(cbWidget);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }
}

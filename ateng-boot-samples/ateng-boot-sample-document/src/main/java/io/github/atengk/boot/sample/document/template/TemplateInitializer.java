package io.github.atengk.boot.sample.document.template;

import jakarta.annotation.PostConstruct;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 模板资产自动化生成与自洽保障组件
 * <p>
 * 负责在测试与本地启动时，自适应检查并生成合法的 Word 与 PDF 模板，
 * 确保样本工程 100% 离线自洽，零外部二进制文件强依赖。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Component
public class TemplateInitializer {

    private static final Logger log = LoggerFactory.getLogger(TemplateInitializer.class);

    private static final String TARGET_TEMPLATES_DIR = "src/main/resources/templates";

    @PostConstruct
    public void init() {
        ensureTemplatesExist();
    }

    /**
     * 确保所有场景模板物理文件已就绪
     */
    public static synchronized void ensureTemplatesExist() {
        // 若类路径中已存在全部模板资源，无需在文件系统中重复写入，保障环境宿主中立与打包运行弹性
        if (TemplateInitializer.class.getResource("/templates/contract-template.docx") != null
                && TemplateInitializer.class.getResource("/templates/attachment-clause.docx") != null
                && TemplateInitializer.class.getResource("/templates/sample-statement.pdf") != null
                && TemplateInitializer.class.getResource("/templates/supplier-form.pdf") != null) {
            return;
        }

        ensureDirectoryAndFiles(new File(TARGET_TEMPLATES_DIR));
        File targetClassesDir = new File("target/classes/templates");
        if (targetClassesDir.getParentFile() != null && targetClassesDir.getParentFile().exists()) {
            ensureDirectoryAndFiles(targetClassesDir);
        }
    }

    private static void ensureDirectoryAndFiles(File dir) {
        if (!dir.exists()) {
            dir.mkdirs();
        }

        File contractFile = new File(dir, "contract-template.docx");
        if (!contractFile.exists() || contractFile.length() == 0) {
            generateContractTemplate(contractFile);
        }

        File attachmentFile = new File(dir, "attachment-clause.docx");
        if (!attachmentFile.exists() || attachmentFile.length() == 0) {
            generateAttachmentClause(attachmentFile);
        }

        File statementFile = new File(dir, "sample-statement.pdf");
        if (!statementFile.exists() || statementFile.length() == 0) {
            generateSampleStatementPdf(statementFile);
        }

        File supplierFile = new File(dir, "supplier-form.pdf");
        if (!supplierFile.exists() || supplierFile.length() == 0) {
            generateSupplierFormPdf(supplierFile);
        }
    }

    private static void generateContractTemplate(File file) {
        try (XWPFDocument doc = new XWPFDocument();
             FileOutputStream fos = new FileOutputStream(file)) {

            XWPFParagraph titlePara = doc.createParagraph();
            titlePara.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setText("企业全日制劳动聘用合同书");
            titleRun.setBold(true);
            titleRun.setFontSize(18);

            addPara(doc, "甲方（用人单位）：阿腾科技数字创新（集团）有限公司");
            addPara(doc, "乙方（劳动者）：{{employeeName}}    身份证号码：{{idCard}}");
            addPara(doc, "任职部门：{{department}}    工作岗位：{{postName}}");
            addPara(doc, "月度综合薪资：{{salary}} 元/月    签署生效日期：{{signDate}}");
            addPara(doc, "主要负责与交付项目经历清单：");

            // 动态表格循环行：首列 {{items}}[projectName]，后续列 [role]、[duration]
            XWPFTable table = doc.createTable(2, 3);
            XWPFTableRow headerRow = table.getRow(0);
            headerRow.getCell(0).setText("项目名称");
            headerRow.getCell(1).setText("承担角色");
            headerRow.getCell(2).setText("周期历时");

            XWPFTableRow templateRow = table.getRow(1);
            templateRow.getCell(0).setText("{{items}}[projectName]");
            templateRow.getCell(1).setText("[role]");
            templateRow.getCell(2).setText("[duration]");

            XWPFParagraph sealPara = doc.createParagraph();
            sealPara.setAlignment(ParagraphAlignment.RIGHT);
            XWPFRun sealRun = sealPara.createRun();
            sealRun.setText("用人单位签章：{{seal}}");

            doc.write(fos);
            log.info("【TemplateInitializer】成功自洽生成劳动合同模板: {}", file.getAbsolutePath());
        } catch (IOException e) {
            log.error("生成 contract-template.docx 失败", e);
        }
    }

    private static void generateAttachmentClause(File file) {
        try (XWPFDocument doc = new XWPFDocument();
             FileOutputStream fos = new FileOutputStream(file)) {

            XWPFParagraph titlePara = doc.createParagraph();
            titlePara.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setText("附件一：商业秘密保护与竞业限制协议");
            titleRun.setBold(true);
            titleRun.setFontSize(16);

            addPara(doc, "第一条 乙方在职期间及离职后两年内，对甲方的技术成果、商业秘密负有严格保密义务。");
            addPara(doc, "第二条 未经甲方书面同意，乙方不得在任何存在竞争关系的企业兼职或提供咨询顾问服务。");
            addPara(doc, "第三条 双方签字盖章后本附件与主合同具备同等法定法律约束力。");

            doc.write(fos);
            log.info("【TemplateInitializer】成功自洽生成附件协议模板: {}", file.getAbsolutePath());
        } catch (IOException e) {
            log.error("生成 attachment-clause.docx 失败", e);
        }
    }

    private static void generateSampleStatementPdf(File file) {
        try (PDDocument doc = new PDDocument();
             FileOutputStream fos = new FileOutputStream(file)) {

            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            // Page 1
            PDPage page1 = new PDPage(PDRectangle.A4);
            doc.addPage(page1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                cs.beginText();
                cs.setFont(font, 18);
                cs.newLineAtOffset(50, 750);
                cs.showText("FINANCIAL RECONCILIATION STATEMENT - 2026 Q3");
                cs.setFont(font, 12);
                cs.newLineAtOffset(0, -40);
                cs.showText("Account Name: Ateng Cloud Technology Co., Ltd.");
                cs.newLineAtOffset(0, -25);
                cs.showText("Cycle: 2026-07-01 to 2026-09-30");
                cs.newLineAtOffset(0, -25);
                cs.showText("Total Settlement Amount: USD 1,850,200.00");
                cs.endText();
            }

            // Page 2
            PDPage page2 = new PDPage(PDRectangle.A4);
            doc.addPage(page2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                cs.beginText();
                cs.setFont(font, 14);
                cs.newLineAtOffset(50, 750);
                cs.showText("DETAILED TRANSACTIONS BREAKDOWN (PAGE 2)");
                cs.setFont(font, 11);
                cs.newLineAtOffset(0, -30);
                cs.showText("Txn #9801: Amount USD 12,000.00 - Settled");
                cs.newLineAtOffset(0, -20);
                cs.showText("Txn #9802: Amount USD 45,500.00 - Settled");
                cs.newLineAtOffset(0, -20);
                cs.showText("Txn #9803: Amount USD 88,200.00 - Settled");
                cs.endText();
            }

            doc.save(fos);
            log.info("【TemplateInitializer】成功自洽生成财务对账单模板: {}", file.getAbsolutePath());
        } catch (IOException e) {
            log.error("生成 sample-statement.pdf 失败", e);
        }
    }

    private static void generateSupplierFormPdf(File file) {
        try (PDDocument doc = new PDDocument();
             FileOutputStream fos = new FileOutputStream(file)) {

            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);

            PDResources resources = new PDResources();
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            COSName fontName = resources.add(font);
            acroForm.setDefaultResources(resources);
            acroForm.setDefaultAppearance("/" + fontName.getName() + " 12 Tf 0 g");

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 16);
                cs.newLineAtOffset(50, 760);
                cs.showText("SUPPLIER ADMISSION REGISTRATION FORM");
                cs.setFont(font, 11);
                cs.newLineAtOffset(0, -45);
                cs.showText("Supplier Name:");
                cs.newLineAtOffset(0, -50);
                cs.showText("Contact Phone:");
                cs.newLineAtOffset(0, -50);
                cs.showText("Qualification Passed (Check to confirm):");
                cs.endText();
            }

            PDTextField nameField = new PDTextField(acroForm);
            nameField.setPartialName("supplierName");
            nameField.setDefaultAppearance("/" + fontName.getName() + " 12 Tf 0 g");
            acroForm.getFields().add(nameField);

            PDAnnotationWidget nameWidget = nameField.getWidgets().get(0);
            nameWidget.setRectangle(new PDRectangle(160, 700, 250, 25));
            nameWidget.setPage(page);
            page.getAnnotations().add(nameWidget);

            PDTextField phoneField = new PDTextField(acroForm);
            phoneField.setPartialName("contactPhone");
            phoneField.setDefaultAppearance("/" + fontName.getName() + " 12 Tf 0 g");
            acroForm.getFields().add(phoneField);

            PDAnnotationWidget phoneWidget = phoneField.getWidgets().get(0);
            phoneWidget.setRectangle(new PDRectangle(160, 650, 250, 25));
            phoneWidget.setPage(page);
            page.getAnnotations().add(phoneWidget);

            PDCheckBox checkBox = new PDCheckBox(acroForm);
            checkBox.setPartialName("qualificationPassed");
            acroForm.getFields().add(checkBox);

            PDAnnotationWidget cbWidget = checkBox.getWidgets().get(0);
            cbWidget.setRectangle(new PDRectangle(320, 600, 20, 20));
            cbWidget.setPage(page);
            page.getAnnotations().add(cbWidget);

            doc.save(fos);
            log.info("【TemplateInitializer】成功自洽生成供应商 AcroForm 模板: {}", file.getAbsolutePath());
        } catch (IOException e) {
            log.error("生成 supplier-form.pdf 失败", e);
        }
    }

    private static void addPara(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontSize(12);
    }
}

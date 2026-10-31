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

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

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
     * 智能探测并选取支持完整中文字符集的印章字体
     *
     * @param style 字体样式（如 Font.BOLD）
     * @param size  字体大小
     * @return 最优可用字体
     */
    public static Font findSealFont(int style, int size) {
        String[] preferredFonts = {
                "SimSun", "STSong", "Songti SC", "Microsoft YaHei", "PingFang SC",
                "Noto Serif CJK SC", "Noto Sans CJK SC", "WenQuanYi Zen Hei", "WenQuanYi Micro Hei",
                Font.SERIF, Font.SANS_SERIF, Font.DIALOG
        };
        String sampleText = "阿腾科技数字创新（集团）有限公司合同专用章";
        for (String fontName : preferredFonts) {
            Font f = new Font(fontName, style, size);
            if (f.canDisplayUpTo(sampleText) == -1) {
                return f;
            }
        }
        return new Font(Font.SERIF, style, size);
    }

    /**
     * 动态生成 300 DPI 高保真矢量质感透明企业电子印章（含标准五角星、圆弧企业全称与底部合同专用章字样）
     *
     * @return 印章 PNG 二进制字节数据
     * @throws IOException 图像编码异常
     */
    public static byte[] generateSealImageBytes() throws IOException {
        int width = 400;
        int height = 400;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();

        try {
            // 启用高质量抗锯齿渲染
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

            Color sealRed = new Color(218, 37, 29); // 经典鲜红印章色
            g2d.setColor(sealRed);

            int cx = width / 2;
            int cy = height / 2;

            // 1. 外圈圆环（经典红边，粗细 8px）
            int radius = 180;
            g2d.setStroke(new BasicStroke(8.0f));
            g2d.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);

            // 2. 中心五角星（正五角星，中心略微上偏以留出下方落款空间）
            int starCenterY = cy - 8;
            double outerR = 48.0;
            double innerR = outerR * Math.sin(Math.toRadians(18)) / Math.cos(Math.toRadians(36));
            Path2D starPath = new Path2D.Double();
            for (int i = 0; i < 5; i++) {
                double outerAngle = Math.toRadians(-90 + i * 72);
                double innerAngle = Math.toRadians(-90 + 36 + i * 72);
                double ox = cx + outerR * Math.cos(outerAngle);
                double oy = starCenterY + outerR * Math.sin(outerAngle);
                double ix = cx + innerR * Math.cos(innerAngle);
                double iy = starCenterY + innerR * Math.sin(innerAngle);
                if (i == 0) {
                    starPath.moveTo(ox, oy);
                } else {
                    starPath.lineTo(ox, oy);
                }
                starPath.lineTo(ix, iy);
            }
            starPath.closePath();
            g2d.fill(starPath);

            // 3. 上方圆弧企业名称："阿腾科技数字创新（集团）有限公司"
            String topText = "阿腾科技数字创新（集团）有限公司";
            Font topFont = findSealFont(Font.BOLD, 26);
            g2d.setFont(topFont);
            FontMetrics topFm = g2d.getFontMetrics(topFont);

            double arcRadius = 140.0;
            double totalSweepAngle = Math.toRadians(210.0); // 覆盖 210 度圆弧
            double startAngle = Math.toRadians(270.0) - totalSweepAngle / 2.0;
            double angleStep = totalSweepAngle / (topText.length() - 1);

            for (int i = 0; i < topText.length(); i++) {
                char ch = topText.charAt(i);
                double angle = startAngle + i * angleStep;
                double charX = cx + arcRadius * Math.cos(angle);
                double charY = cy + arcRadius * Math.sin(angle);

                AffineTransform old = g2d.getTransform();
                g2d.translate(charX, charY);
                // 旋转角度使文字底座始终朝向圆心
                g2d.rotate(angle + Math.PI / 2.0);
                int charWidth = topFm.charWidth(ch);
                g2d.drawString(String.valueOf(ch), -charWidth / 2.0f, topFm.getAscent() / 3.0f);
                g2d.setTransform(old);
            }

            // 4. 下方横向文字："合同专用章"
            String bottomText = "合同专用章";
            Font bottomFont = findSealFont(Font.BOLD, 28);
            g2d.setFont(bottomFont);
            FontMetrics bottomFm = g2d.getFontMetrics(bottomFont);

            int letterSpacing = 8;
            int totalBottomWidth = 0;
            for (char ch : bottomText.toCharArray()) {
                totalBottomWidth += bottomFm.charWidth(ch);
            }
            totalBottomWidth += (bottomText.length() - 1) * letterSpacing;

            float curX = cx - totalBottomWidth / 2.0f;
            int bottomY = cy + 90;
            for (char ch : bottomText.toCharArray()) {
                g2d.drawString(String.valueOf(ch), curX, bottomY);
                curX += bottomFm.charWidth(ch) + letterSpacing;
            }
        } finally {
            g2d.dispose();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        return baos.toByteArray();
    }

    /**
     * 确保高保真印章资产存在于构建输出目录中（仅补全缺失的 target 构建产物，严禁污染源码目录）
     */
    public static void ensureSealAssetsExist() {
        try {
            File currentDir = new File(System.getProperty("user.dir"));
            List<File> targets = new ArrayList<>();
            // 仅对构建输出目录进行自洽补全
            targets.add(new File(currentDir, "ateng-boot-samples/ateng-boot-sample-document/target/classes/images/company-seal.png"));
            targets.add(new File(currentDir, "ateng-boot-document/target/test-classes/images/seal.png"));
            targets.add(new File(currentDir, "target/classes/images/company-seal.png"));
            targets.add(new File(currentDir, "../../ateng-boot-document/target/test-classes/images/seal.png"));

            byte[] sealBytes = null;
            for (File target : targets) {
                if (target.getParentFile() != null && target.getParentFile().exists() && (!target.exists() || target.length() == 0)) {
                    if (sealBytes == null) {
                        sealBytes = generateSealImageBytes();
                    }
                    Files.write(target.toPath(), sealBytes);
                    log.info("【TemplateInitializer】成功按需生成高保真印章资产至构建目录: {}", target.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            log.error("生成高保真印章资产失败", e);
        }
    }

    /**
     * 确保所有场景模板物理文件已就绪
     */
    public static synchronized void ensureTemplatesExist() {
        ensureSealAssetsExist();

        File targetTemplatesDir = new File(TARGET_TEMPLATES_DIR);
        if (targetTemplatesDir.exists()) {
            ensureDirectoryAndFiles(targetTemplatesDir);
        }
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

            // 双方确认生效条款（增加合理垂直留白）
            XWPFParagraph signDescPara = doc.createParagraph();
            signDescPara.setSpacingBefore(300);
            XWPFRun descRun = signDescPara.createRun();
            descRun.setText("双方确认：本合同一式两份，双方各执一份，自签署盖章之日起正式生效。");
            descRun.setFontSize(12);

            // 签约落款区域（右对齐，带法定代表人签名横线与印章占位）
            XWPFParagraph sealPara = doc.createParagraph();
            sealPara.setSpacingBefore(500);
            sealPara.setSpacingAfter(200);
            sealPara.setAlignment(ParagraphAlignment.RIGHT);
            XWPFRun prefixRun = sealPara.createRun();
            prefixRun.setText("用人单位（盖章）：");
            prefixRun.setFontSize(12);
            XWPFRun sealRun = sealPara.createRun();
            sealRun.setText("{{seal}}");
            XWPFRun signRun = sealPara.createRun();
            signRun.setText("    法定代表人（签字）：__________");
            signRun.setFontSize(12);

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

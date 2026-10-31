package io.github.atengk.boot.sample.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.sample.document.model.ContractRenderRequest;
import io.github.atengk.boot.sample.document.model.ProjectExperience;
import io.github.atengk.boot.sample.document.model.SupplierFormRequest;
import io.github.atengk.boot.sample.document.service.ContractService;
import io.github.atengk.boot.sample.document.service.FinanceStatementService;
import io.github.atengk.boot.sample.document.service.SupplierFormService;
import io.github.atengk.boot.sample.document.template.TemplateInitializer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 样例工程 9 大企业场景端到端真实落盘、SHA-256 验真与控制台可视化点检测试
 * <p>
 * 将 9 大真实业务场景输出物确定性持久化落盘至工程 {@code target/test-output/} 目录，
 * 并自动生成 {@code audit-manifest.json} 与 ASCII 可视化控制台表格，便于开发者及人工审核员一键点击本地文件直达查看。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootTest
class DocumentAuditExportTest {

    private static final Logger log = LoggerFactory.getLogger(DocumentAuditExportTest.class);
    private static final Path OUTPUT_DIR = Paths.get("target", "test-output");
    private static final String STATEMENT_PASS = "MyStrongPassword#2026";
    private static final String STATEMENT_OWNER_PASS = "AdminOwnerPass#2026";

    @Autowired
    private ContractService contractService;

    @Autowired
    private FinanceStatementService financeStatementService;

    @Autowired
    private SupplierFormService supplierFormService;

    @BeforeAll
    static void setup() throws IOException {
        TemplateInitializer.ensureTemplatesExist();
        Files.createDirectories(OUTPUT_DIR);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("执行 9 大企业级场景完整流水线落盘，生成 audit-manifest.json 与点检直达报告")
    void testExportAllNineScenariosAndGenerateManifest() throws Exception {
        List<AuditFileEntry> auditEntries = new ArrayList<>();

        // =========================================================================
        // 场景 1: Word 劳动合同模板渲染 (含变量插值、多行循环表格与浮动公章)
        // =========================================================================
        ContractRenderRequest fullContractReq = ContractRenderRequest.builder()
                .employeeName("李云龙")
                .idCard("110101198501011234")
                .department("独立团特勤保障部")
                .postName("核心防务架构师")
                .salary("48,000")
                .signDate("2026-10-31")
                .items(List.of(
                        new ProjectExperience("阵地通信专网高可用重构", "项目总指挥", "12 个月"),
                        new ProjectExperience("分布式防勒索物理隔离方案", "核心技术负责人", "6 个月")
                ))
                .build();
        byte[] contractRenderedBytes = contractService.renderMainContract(fullContractReq);
        auditEntries.add(saveAndAudit(
                "contract-rendered.docx",
                "Word 劳动合同模板渲染 (变量插值+多行表格+浮动公章)",
                "DOCX",
                contractRenderedBytes,
                1
        ));

        // =========================================================================
        // 场景 2: Word 主合同无缝物理拼装保密协议附件
        // =========================================================================
        byte[] contractMergedBytes = contractService.generateContractDocx(fullContractReq);
        auditEntries.add(saveAndAudit(
                "contract-merged.docx",
                "Word 主合同与商业保密协议附件物理无缝拼装",
                "DOCX",
                contractMergedBytes,
                2
        ));

        // =========================================================================
        // 场景 3: Word 动态表格空数据降级 (空列表优雅折叠无残留语法)
        // =========================================================================
        ContractRenderRequest emptyTableReq = ContractRenderRequest.builder()
                .employeeName("赵刚")
                .idCard("110101198602022345")
                .department("政务纪检监察室")
                .postName("高级政务监督专员")
                .salary("36,000")
                .signDate("2026-10-31")
                .items(Collections.emptyList())
                .build();
        byte[] contractEmptyTableBytes = contractService.renderMainContract(emptyTableReq);
        auditEntries.add(saveAndAudit(
                "contract-empty-table.docx",
                "Word 动态表格空数据安全折叠 (空列表优雅降级)",
                "DOCX",
                contractEmptyTableBytes,
                1
        ));

        // =========================================================================
        // 场景 4: PDF 财务对账单平铺暗水印与跳过封面页码重算
        // =========================================================================
        byte[] statementWatermarkedBytes = financeStatementService.generateWatermarkedStatement(
                "周审计", "FIN-AUD-007", "13800138000"
        );
        int statementPages;
        try (PDDocument doc = Loader.loadPDF(statementWatermarkedBytes)) {
            statementPages = doc.getNumberOfPages();
        }
        auditEntries.add(saveAndAudit(
                "statement-watermarked.pdf",
                "PDF 财务对账单 -30°操作人宏变量暗水印与跳过封面页码",
                "PDF",
                statementWatermarkedBytes,
                statementPages
        ));

        // =========================================================================
        // 场景 5: PDF 256 位 AES 权限锁加密受控归档
        // =========================================================================
        byte[] statementEncryptedBytes = financeStatementService.protectStatement(
                statementWatermarkedBytes, STATEMENT_PASS, STATEMENT_OWNER_PASS
        );
        auditEntries.add(saveAndAudit(
                "statement-encrypted.pdf",
                "PDF 256 位 AES 权限控制加密锁 (打开密码: " + STATEMENT_PASS + ")",
                "PDF",
                statementEncryptedBytes,
                statementPages
        ));

        // =========================================================================
        // 场景 6: PDF 多页对账单跨页骑缝章垂直切片与贴合压印
        // =========================================================================
        byte[] statementCrossPageSealBytes = financeStatementService.stampCrossPageSeal(
                statementWatermarkedBytes, "classpath:images/company-seal.png"
        );
        auditEntries.add(saveAndAudit(
                "statement-cross-page-seal.pdf",
                "PDF 多页对账单右侧边缘跨页骑缝章等分垂直切片压印",
                "PDF",
                statementCrossPageSealBytes,
                statementPages
        ));

        // =========================================================================
        // 场景 7: PDF 供应商资质准入 AcroForm 预填报 (保留交互复选框与文本域)
        // =========================================================================
        SupplierFormRequest supplierInteractiveReq = SupplierFormRequest.builder()
                .supplierName("未来智能网络科技(北京)有限公司")
                .contactPhone("010-88886666")
                .qualificationPassed(true)
                .build();
        byte[] supplierInteractiveBytes = supplierFormService.fillSupplierForm(supplierInteractiveReq, false);
        auditEntries.add(saveAndAudit(
                "supplier-form-interactive.pdf",
                "PDF 供应商资质准入 AcroForm 填报 (保留交互式控件)",
                "PDF",
                supplierInteractiveBytes,
                1
        ));

        // =========================================================================
        // 场景 8: PDF 供应商资质准入 AcroForm 矢量不可逆扁平化固化防篡改
        // =========================================================================
        SupplierFormRequest supplierFlattenedReq = SupplierFormRequest.builder()
                .supplierName("腾讯云计算(深圳)有限责任公司")
                .contactPhone("0755-88889999")
                .qualificationPassed(true)
                .build();
        byte[] supplierFlattenedBytes = supplierFormService.fillSupplierForm(supplierFlattenedReq, true);
        auditEntries.add(saveAndAudit(
                "supplier-form-flattened.pdf",
                "PDF 供应商资质准入 AcroForm 矢量扁平化固化 (防篡改)",
                "PDF",
                supplierFlattenedBytes,
                1
        ));

        // =========================================================================
        // 场景 9: PDF 原生页面 150 DPI 高清光栅化 PNG 缩略图/预览图
        // =========================================================================
        byte[] statementPreviewBytes = financeStatementService.renderStatementPageToPng(
                statementWatermarkedBytes, 0, 150.0f
        );
        auditEntries.add(saveAndAudit(
                "statement-page-preview.png",
                "PDFBox 原生 150 DPI 高清页面光栅化预览图 (首页缩略图)",
                "PNG",
                statementPreviewBytes,
                1
        ));

        // =========================================================================
        // 生成 audit-manifest.json 结构化元数据清单
        // =========================================================================
        Path manifestPath = OUTPUT_DIR.resolve("audit-manifest.json");
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        Map<String, Object> manifestRoot = new LinkedHashMap<>();
        manifestRoot.put("module", "ateng-boot-document");
        manifestRoot.put("sample", "ateng-boot-sample-document");
        manifestRoot.put("totalScenarios", auditEntries.size());
        manifestRoot.put("generatedAt", java.time.OffsetDateTime.now().toString());
        manifestRoot.put("outputDirectory", OUTPUT_DIR.toAbsolutePath().toString().replace('\\', '/'));
        manifestRoot.put("artifacts", auditEntries);

        mapper.writeValue(manifestPath.toFile(), manifestRoot);
        assertThat(manifestPath.toFile()).exists().isNotEmpty();

        // =========================================================================
        // 打印格式化控制台 ASCII 点检表与直达点击链接
        // =========================================================================
        printAsciiAuditReport(auditEntries, manifestPath);

        // =========================================================================
        // 强断言业务合法性与关键约束
        // =========================================================================
        assertThat(auditEntries).hasSize(9);

        // 验证 DOCX 内容
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(contractRenderedBytes))) {
            assertThat(doc.getParagraphs().stream().anyMatch(p -> p.getText().contains("李云龙"))).isTrue();
        }
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(contractEmptyTableBytes))) {
            // 空表格中不应残留 {{items}} 占位符
            assertThat(doc.getParagraphs().stream().noneMatch(p -> p.getText().contains("{{items}}"))).isTrue();
        }

        // 验证加密 PDF
        try (PDDocument doc = Loader.loadPDF(statementEncryptedBytes, STATEMENT_PASS)) {
            assertThat(doc.isEncrypted()).isTrue();
        }

        // 验证交互式与扁平化 AcroForm
        try (PDDocument doc = Loader.loadPDF(supplierInteractiveBytes)) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            assertThat(form).isNotNull();
            assertThat(form.getFields()).isNotEmpty();
        }
        try (PDDocument doc = Loader.loadPDF(supplierFlattenedBytes)) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            boolean isFlattened = (form == null || form.getFields().isEmpty());
            assertThat(isFlattened).isTrue();
        }

        // 验证 PNG 预览图
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(statementPreviewBytes));
        assertThat(img).isNotNull();
        assertThat(img.getWidth()).isGreaterThan(500);
        assertThat(img.getHeight()).isGreaterThan(500);
    }

    private AuditFileEntry saveAndAudit(String fileName, String scenarioName, String format, byte[] content, int pageCount) throws IOException {
        Path targetPath = OUTPUT_DIR.resolve(fileName);
        Files.write(targetPath, content);

        String sha256 = calculateSha256(content);
        long size = content.length;
        String absoluteUrl = "file:///" + targetPath.toAbsolutePath().toString().replace('\\', '/');

        return new AuditFileEntry(fileName, scenarioName, format, size, pageCount, sha256, absoluteUrl);
    }

    private static String calculateSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private void printAsciiAuditReport(List<AuditFileEntry> entries, Path manifestPath) {
        String manifestUrl = "file:///" + manifestPath.toAbsolutePath().toString().replace('\\', '/');
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("========================================================================================================================\n");
        sb.append("【ateng-boot-document 生产就绪场景落盘与可视化点检报告 (Visual Audit Manifest)】\n");
        sb.append("输出目录: ").append(OUTPUT_DIR.toAbsolutePath().toString().replace('\\', '/')).append("\n");
        sb.append("元数据清单: ").append(manifestUrl).append("\n");
        sb.append("========================================================================================================================\n");
        sb.append(String.format("| %-2s | %-28s | %-32s | %-8s | %-4s | %-16s | %s\n",
                "序号", "文件名", "场景说明", "大小", "页数", "SHA-256(前16位)", "直达文件链接 (file://)"));
        sb.append("|----|------------------------------|----------------------------------|----------|------|------------------|-----------------------------------------------------\n");

        int index = 1;
        for (AuditFileEntry entry : entries) {
            String shortSha = entry.sha256().length() >= 16 ? entry.sha256().substring(0, 16) : entry.sha256();
            String sizeStr = formatFileSize(entry.fileSizeBytes());
            sb.append(String.format("| %-2d | %-28s | %-32s | %-8s | %-4d | %-16s | %s\n",
                    index++,
                    entry.fileName(),
                    entry.scenarioName(),
                    sizeStr,
                    entry.pageCount(),
                    shortSha,
                    entry.fileUrl()));
        }
        sb.append("========================================================================================================================\n");
        sb.append("提示：以上文件均已成功落盘至项目 target 目录，支持在 IDEA/VS Code 终端或文档中 Ctrl+单击 (或直接在系统文件管理器中打开) 进行全真排版与视觉效果审核。\n");
        sb.append("========================================================================================================================\n");

        log.info("\n{}", sb);
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        }
    }

    public record AuditFileEntry(
            String fileName,
            String scenarioName,
            String format,
            long fileSizeBytes,
            int pageCount,
            String sha256,
            String fileUrl
    ) {
    }
}

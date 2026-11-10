package io.github.atengk.boot.sample.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.document.pdf.util.PdfWebUtil;
import io.github.atengk.boot.document.word.WordTemplate;
import io.github.atengk.boot.document.word.util.WordWebUtil;
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
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ateng-boot-sample-document 端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@SpringBootTest
@AutoConfigureMockMvc
class DocumentSampleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WordTemplate wordTemplate;

    @Autowired
    private ContractService contractService;

    @Autowired
    private FinanceStatementService financeStatementService;

    @Autowired
    private SupplierFormService supplierFormService;

    @BeforeAll
    static void setup() {
        TemplateInitializer.ensureTemplatesExist();
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("服务层直测：验证保留交互式表单填报（flatten = false）与勾选状态")
    void testSupplierFormServiceInteractive() throws Exception {
        SupplierFormRequest request = SupplierFormRequest.builder()
                .supplierName("华为数字能源")
                .contactPhone("0755-66668888")
                .qualificationPassed(true)
                .build();

        byte[] interactivePdf = supplierFormService.fillSupplierForm(request, false);
        assertThat(interactivePdf).isNotEmpty();

        try (PDDocument doc = Loader.loadPDF(interactivePdf)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            assertThat(acroForm).isNotNull();
            assertThat(acroForm.getField("supplierName").getValueAsString()).isEqualTo("华为数字能源");
            assertThat(acroForm.getField("contactPhone").getValueAsString()).isEqualTo("0755-66668888");
            assertThat(((PDCheckBox) acroForm.getField("qualificationPassed")).isChecked()).isTrue();
        }
    }

    @Test
    @DisplayName("端到端测试 5：Word 表格跨列水平合并与单元格处理")
    void testContractTableCellMerge() {
        ContractRenderRequest req = ContractRenderRequest.builder()
                .employeeName("张三")
                .idCard("110101199001011234")
                .department("研发部")
                .postName("资深架构师")
                .salary("50,000")
                .signDate("2026-10-23")
                .items(List.of(new ProjectExperience("核心网重构", "Tech Lead", "12 个月")))
                .build();
        byte[] docx = contractService.generateContractDocx(req);
        byte[] mergedDocx = contractService.mergeContractTableCells(docx);
        assertThat(mergedDocx).isNotEmpty();
    }

    @Test
    @DisplayName("端到端测试 6：PDF 物理编排（合并、切分、旋转）")
    void testPdfOrchestrationMergeSplitRotate() throws Exception {
        byte[] pdf1 = financeStatementService.generateWatermarkedStatement("张三", "FIN-001", "13800000000");
        byte[] pdf2 = financeStatementService.generateWatermarkedStatement("李四", "FIN-002", "13900000000");

        // 1. 合并
        byte[] mergedPdf = financeStatementService.mergeStatements(List.of(pdf1, pdf2));
        assertThat(mergedPdf).isNotEmpty();
        try (PDDocument doc = Loader.loadPDF(mergedPdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(2);
        }

        // 2. 切分
        List<byte[]> splits = financeStatementService.splitStatement(mergedPdf, 1);
        assertThat(splits).hasSizeGreaterThanOrEqualTo(2);

        // 3. 旋转（将第 0 页顺时针旋转 90 度）
        byte[] rotatedPdf = financeStatementService.rotateStatement(pdf1, 90, 0);
        try (PDDocument doc = Loader.loadPDF(rotatedPdf)) {
            assertThat(doc.getPage(0).getRotation()).isEqualTo(90);
        }
    }

    // =========================================================================
    // Word 领域控制器 WordContractController (/sample/document/word/*) 端到端全真回归
    // =========================================================================

    @Test
    @DisplayName("Word 领域回归 1：主合同与附件拼装下载 GET /sample/document/word/contract/download 验证 RFC 6266 与格式")
    void testSampleWordContractDownloadEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/document/word/contract/download")
                        .param("employeeName", "楚云飞"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();
        assertThat(mvcResult.getResponse().getContentType()).contains(WordWebUtil.DOCX_CONTENT_TYPE);
        String disposition = mvcResult.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).isNotNull();
        assertThat(disposition).startsWith("attachment");
        assertThat(disposition).contains("filename*=");
        assertThat(disposition).contains(".docx");

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(contentBytes))) {
            String fullText = doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertThat(fullText).contains("楚云飞");
            assertThat(fullText).contains("商业秘密保护与竞业限制协议");
        }
    }

    @Test
    @DisplayName("Word 领域回归 2：动态表格空数据安全折叠 GET /sample/document/word/contract/empty-table/download 验证零残留")
    void testSampleWordContractEmptyTableDownloadEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/document/word/contract/empty-table/download")
                        .param("employeeName", "赵刚"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(contentBytes))) {
            String fullText = doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertThat(fullText).contains("赵刚");
        }
    }

    @Test
    @DisplayName("Word 领域回归 3：结构化 DTO 渲染 POST /sample/document/word/contract/render 验证 JSR-303 与格式流")
    void testSampleWordContractRenderPostEndpoint() throws Exception {
        ContractRenderRequest request = ContractRenderRequest.builder()
                .employeeName("魏大勇")
                .idCard("110101198808081234")
                .department("特勤突击大队")
                .postName("特勤前线专家")
                .salary("52,000")
                .signDate("2026-11-10")
                .items(List.of(
                        new ProjectExperience("核心前线防务", "前沿突击组长", "6 个月")
                ))
                .build();

        MvcResult mvcResult = mockMvc.perform(post("/sample/document/word/contract/render")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(contentBytes))) {
            String fullText = doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertThat(fullText).contains("魏大勇");
            assertThat(fullText).contains("特勤突击大队");
        }
    }

    @Test
    @DisplayName("Word 领域回归 4：模板 AST 变量反查 GET /sample/document/word/contract/variables 验证 Result<List<String>>")
    void testSampleWordContractVariablesEndpoint() throws Exception {
        mockMvc.perform(get("/sample/document/word/contract/variables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", Matchers.hasItem("employeeName")))
                .andExpect(jsonPath("$.data", Matchers.hasItem("salary")))
                .andExpect(jsonPath("$.data", Matchers.hasItem("seal")));
    }

    @Test
    @DisplayName("Word 领域回归 5：正文纯文本抽取 GET /sample/document/word/contract/extract-text 验证 Result<String>")
    void testSampleWordContractExtractTextEndpoint() throws Exception {
        mockMvc.perform(get("/sample/document/word/contract/extract-text")
                        .param("employeeName", "李云龙"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", Matchers.containsString("李云龙")))
                .andExpect(jsonPath("$.data", Matchers.containsString("企业全日制劳动聘用合同书")));
    }

    @Test
    @DisplayName("Word 领域回归 6：只读密码保护下载 GET /sample/document/word/contract/protect/download 验证密码锁生效")
    void testSampleWordContractProtectDownloadEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/document/word/contract/protect/download")
                        .param("employeeName", "李云龙")
                        .param("password", "SecretPass#2026"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();
        // 验证只读锁定已生效
        assertThat(wordTemplate.isEnforcedReadonlyProtection(contentBytes)).isTrue();
        // 验证口令匹配
        assertThat(wordTemplate.validateProtectionPassword(contentBytes, "SecretPass#2026")).isTrue();
        assertThat(wordTemplate.validateProtectionPassword(contentBytes, "WrongPass")).isFalse();
    }

    @Test
    @DisplayName("Word 领域回归 7：只读密码校验 GET /sample/document/word/contract/protect/verify 验证 Result<Boolean>")
    void testSampleWordContractProtectVerifyEndpoint() throws Exception {
        // 验证密码正确场景
        mockMvc.perform(get("/sample/document/word/contract/protect/verify")
                        .param("password", "MyPassword#2026")
                        .param("verifyPassword", "MyPassword#2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 验证密码错误场景
        mockMvc.perform(get("/sample/document/word/contract/protect/verify")
                        .param("password", "MyPassword#2026")
                        .param("verifyPassword", "BadPassword"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(false));
    }

    // =========================================================================
    // PDF 水印印章控制器 PdfWatermarkSealController (/sample/document/pdf/stamp/*) 端到端全真回归
    // =========================================================================

    @Test
    @DisplayName("PDF 水印回归 1：暗水印在线预览 GET /sample/document/pdf/stamp/watermark/preview 验证 inline 标头与暗水印")
    void testSamplePdfWatermarkPreviewEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/document/pdf/stamp/watermark/preview")
                        .param("name", "周审计")
                        .param("code", "AUD-888")
                        .param("phone", "13800138000"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();
        assertThat(mvcResult.getResponse().getContentType()).contains(PdfWebUtil.PDF_CONTENT_TYPE);
        String disposition = mvcResult.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).isNotNull();
        assertThat(disposition).startsWith("inline");
        assertThat(disposition).contains("filename*=");
    }

    @Test
    @DisplayName("PDF 水印回归 2：暗水印附件下载 GET /sample/document/pdf/stamp/watermark/download 验证 attachment 标头")
    void testSamplePdfWatermarkDownloadEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/document/pdf/stamp/watermark/download")
                        .param("name", "李小四")
                        .param("code", "FIN-001")
                        .param("phone", "13912345678"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();
        String disposition = mvcResult.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).isNotNull();
        assertThat(disposition).startsWith("attachment");
        assertThat(disposition).contains("filename*=");
    }

    @Test
    @DisplayName("PDF 印章回归 3：跨页骑缝章下载 GET /sample/document/pdf/stamp/cross-page-seal/download 验证切片压印")
    void testSamplePdfCrossPageSealDownloadEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/document/pdf/stamp/cross-page-seal/download"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();
        try (PDDocument doc = Loader.loadPDF(contentBytes)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    @DisplayName("PDF 印章回归 4：尾页绝对坐标盖章 GET /sample/document/pdf/stamp/absolute-seal/download 验证坐标压印")
    void testSamplePdfAbsoluteSealDownloadEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/document/pdf/stamp/absolute-seal/download")
                        .param("pageIndex", "-1"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();
        try (PDDocument doc = Loader.loadPDF(contentBytes)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("PDF 页码回归 5：重算页码下载 GET /sample/document/pdf/stamp/page-number/download 验证跳过封面页码")
    void testSamplePdfPageNumberDownloadEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/document/pdf/stamp/page-number/download")
                        .param("skipPages", "1"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();
        try (PDDocument doc = Loader.loadPDF(contentBytes)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(2);
        }
    }

    // =========================================================================
    // PDF 表单安全控制器 PdfFormSecurityController (/sample/document/pdf/*) 端到端全真回归
    // =========================================================================

    @Test
    @DisplayName("PDF 表单回归 1：表单域元数据自省 GET /sample/document/pdf/form/fields 验证 Result<List<PdfFormFieldDescriptor>>")
    void testSamplePdfFormFieldsEndpoint() throws Exception {
        mockMvc.perform(get("/sample/document/pdf/form/fields"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[*].name", Matchers.hasItem("supplierName")))
                .andExpect(jsonPath("$.data[*].name", Matchers.hasItem("contactPhone")))
                .andExpect(jsonPath("$.data[*].name", Matchers.hasItem("qualificationPassed")));
    }

    @Test
    @DisplayName("PDF 表单回归 2：交互式表单下载 GET /sample/document/pdf/form/interactive/download 验证交互控件保留")
    void testSamplePdfFormInteractiveDownloadEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/document/pdf/form/interactive/download")
                        .param("supplierName", "未来智能网络科技(深圳)有限公司")
                        .param("contactPhone", "0755-88889999"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();
        try (PDDocument doc = Loader.loadPDF(contentBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            assertThat(acroForm).isNotNull();
            assertThat(acroForm.getField("supplierName").getValueAsString()).isEqualTo("未来智能网络科技(深圳)有限公司");
            assertThat(acroForm.getField("contactPhone").getValueAsString()).isEqualTo("0755-88889999");
            assertThat(((PDCheckBox) acroForm.getField("qualificationPassed")).isChecked()).isTrue();
        }
    }

    @Test
    @DisplayName("PDF 表单回归 3：矢量扁平化固化下载 GET /sample/document/pdf/form/flattened/download 验证控件销毁")
    void testSamplePdfFormFlattenedDownloadEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/document/pdf/form/flattened/download")
                        .param("supplierName", "腾讯云计算(深圳)有限责任公司")
                        .param("contactPhone", "0755-88889999"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();
        try (PDDocument doc = Loader.loadPDF(contentBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            boolean isFieldsEmptyOrNull = (acroForm == null || acroForm.getFields().isEmpty());
            assertThat(isFieldsEmptyOrNull).isTrue();
        }
    }

    @Test
    @DisplayName("PDF 表单回归 4：结构化 DTO 扁平化 POST /sample/document/pdf/form/fill 验证 JSR-303 与格式流")
    void testSamplePdfFormFillPostEndpoint() throws Exception {
        SupplierFormRequest request = SupplierFormRequest.builder()
                .supplierName("腾讯云计算(北京)有限责任公司")
                .contactPhone("010-88886666")
                .qualificationPassed(true)
                .build();

        MvcResult mvcResult = mockMvc.perform(post("/sample/document/pdf/form/fill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();
        String disposition = mvcResult.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).isNotNull();
        assertThat(disposition).startsWith("attachment");
        assertThat(java.net.URLDecoder.decode(disposition, java.nio.charset.StandardCharsets.UTF_8)).contains("腾讯云计算");

        try (PDDocument doc = Loader.loadPDF(contentBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            boolean isFieldsEmptyOrNull = (acroForm == null || acroForm.getFields().isEmpty());
            assertThat(isFieldsEmptyOrNull).isTrue();
        }
    }

    @Test
    @DisplayName("PDF 安全回归 5：256 位 AES 权限锁加密下载 GET /sample/document/pdf/security/protect/download 验证权限锁")
    void testSamplePdfSecurityProtectDownloadEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/document/pdf/security/protect/download")
                        .param("userPassword", "TestPass#123")
                        .param("ownerPassword", "OwnerPass#888"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();

        // 验证无口令无法直接打开
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> {
            try (PDDocument doc = Loader.loadPDF(contentBytes)) {
                doc.getNumberOfPages();
            }
        });

        // 验证使用正确用户密码可打开
        try (PDDocument doc = Loader.loadPDF(contentBytes, "TestPass#123")) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(2);
            assertThat(doc.getCurrentAccessPermission().canExtractContent()).isFalse();
        }
    }

    @Test
    @DisplayName("PDF 安全回归 6：权限锁密码校验 GET /sample/document/pdf/security/verify 验证 Result<Boolean>")
    void testSamplePdfSecurityVerifyEndpoint() throws Exception {
        // 验证密码正确场景
        mockMvc.perform(get("/sample/document/pdf/security/verify")
                        .param("password", "Pass123456")
                        .param("verifyPassword", "Pass123456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 验证密码错误场景
        mockMvc.perform(get("/sample/document/pdf/security/verify")
                        .param("password", "Pass123456")
                        .param("verifyPassword", "WrongPass"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(false));
    }

    // =========================================================================
    // PDF 页面编排、渲染与探针控制器 PdfPageOpsController 端到端全真回归
    // =========================================================================

    @Test
    @DisplayName("PDF 页面回归 1：150 DPI 高清单页光栅化预览 GET /sample/document/pdf/page/render/preview 验证 image/png 与 inline")
    void testSamplePdfPageRenderPreviewEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/document/pdf/page/render/preview")
                        .param("pageIndex", "0")
                        .param("dpi", "150.0"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();
        assertThat(mvcResult.getResponse().getContentType()).contains("image/png");
        String disposition = mvcResult.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).isNotNull();
        assertThat(disposition).startsWith("inline");
        assertThat(disposition).contains(".png");

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(contentBytes));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isGreaterThan(0);
        assertThat(image.getHeight()).isGreaterThan(0);
    }

    @Test
    @DisplayName("PDF 页面回归 2：72 DPI 缩略图下载 GET /sample/document/pdf/page/render/download 验证 image/png 与 attachment")
    void testSamplePdfPageRenderDownloadEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/document/pdf/page/render/download")
                        .param("pageIndex", "0")
                        .param("dpi", "72.0"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();
        assertThat(mvcResult.getResponse().getContentType()).contains("image/png");
        String disposition = mvcResult.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).isNotNull();
        assertThat(disposition).startsWith("attachment");
        assertThat(disposition).contains(".png");
    }

    @Test
    @DisplayName("PDF 页面回归 3：多文档物理合并下载 GET /sample/document/pdf/page/merge/download 验证页数翻倍")
    void testSamplePdfPageMergeDownloadEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/document/pdf/page/merge/download"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();
        assertThat(mvcResult.getResponse().getContentType()).contains(PdfWebUtil.PDF_CONTENT_TYPE);
        String disposition = mvcResult.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).isNotNull();
        assertThat(disposition).startsWith("attachment");

        try (PDDocument doc = Loader.loadPDF(contentBytes)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(4);
        }
    }

    @Test
    @DisplayName("PDF 页面回归 4：单页物理拆分下载 GET /sample/document/pdf/page/split/download 验证单页结构")
    void testSamplePdfPageSplitDownloadEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/document/pdf/page/split/download")
                        .param("pageIndex", "0"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();
        assertThat(mvcResult.getResponse().getContentType()).contains(PdfWebUtil.PDF_CONTENT_TYPE);
        String disposition = mvcResult.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).isNotNull();
        assertThat(disposition).startsWith("attachment");

        try (PDDocument doc = Loader.loadPDF(contentBytes)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("PDF 页面回归 5：页面顺时针旋转校准 GET /sample/document/pdf/page/rotate/download 验证角度变化")
    void testSamplePdfPageRotateDownloadEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/document/pdf/page/rotate/download")
                        .param("angle", "90")
                        .param("pageIndex", "0"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();
        assertThat(mvcResult.getResponse().getContentType()).contains(PdfWebUtil.PDF_CONTENT_TYPE);
        String disposition = mvcResult.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).isNotNull();
        assertThat(disposition).startsWith("attachment");

        try (PDDocument doc = Loader.loadPDF(contentBytes)) {
            assertThat(doc.getPage(0).getRotation()).isEqualTo(90);
        }
    }

    @Test
    @DisplayName("PDF 探针回归 6：11 维元数据结构化自省 GET /sample/document/inspect/metadata 验证 Result<PdfMetadata>")
    void testSamplePdfInspectMetadataEndpoint() throws Exception {
        mockMvc.perform(get("/sample/document/inspect/metadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.pageCount").value(2))
                .andExpect(jsonPath("$.data.version").isNumber());
    }

    @Test
    @DisplayName("PDF 探针回归 7：起止范围纯文本抽取 GET /sample/document/inspect/text 验证 Result<String>")
    void testSamplePdfInspectTextEndpoint() throws Exception {
        mockMvc.perform(get("/sample/document/inspect/text")
                        .param("startPage", "1")
                        .param("endPage", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isString())
                .andExpect(jsonPath("$.data").value(Matchers.containsString("FINANCIAL RECONCILIATION STATEMENT")));
    }

    @Test
    @DisplayName("PDF 探针回归 8：CJK 中文字体云原生探测自省 GET /sample/document/inspect/font 验证 Result<ResolvedFont>")
    void testSamplePdfInspectFontEndpoint() throws Exception {
        mockMvc.perform(get("/sample/document/inspect/font"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.fontName").isNotEmpty())
                .andExpect(jsonPath("$.data.tier").isNotEmpty())
                .andExpect(jsonPath("$.data.location").isNotEmpty());
    }
}

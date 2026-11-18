package io.github.atengk.boot.sample.document.service;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.document.enums.KeywordStampOccurrence;
import io.github.atengk.boot.document.enums.PageNumberPosition;
import io.github.atengk.boot.document.enums.WatermarkLayer;
import io.github.atengk.boot.document.font.ResolvedFont;
import io.github.atengk.boot.document.pdf.PdfTemplate;
import io.github.atengk.boot.document.pdf.model.CrossPageSealOptions;
import io.github.atengk.boot.document.pdf.model.ImageStampOptions;
import io.github.atengk.boot.document.pdf.model.ImageToPdfOptions;
import io.github.atengk.boot.document.pdf.model.KeywordStampOptions;
import io.github.atengk.boot.document.pdf.model.PageNumberOptions;
import io.github.atengk.boot.document.pdf.model.PdfMetadata;
import io.github.atengk.boot.document.pdf.model.PdfMetadataUpdate;
import io.github.atengk.boot.document.pdf.model.RedactOptions;
import io.github.atengk.boot.document.pdf.model.SecurityOptions;
import io.github.atengk.boot.document.pdf.model.TextPositionMatch;
import io.github.atengk.boot.document.pdf.model.WatermarkOptions;
import io.github.atengk.boot.document.pdf.util.PdfWebUtil;
import io.github.atengk.boot.sample.document.model.PdfMetadataUpdateRequest;
import io.github.atengk.boot.sample.document.model.PdfRedactRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 场景二：财务对账单平铺防泄密暗水印与安全权限治理服务
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Service
public class FinanceStatementService {

    private static final String STATEMENT_TEMPLATE_PATH = "classpath:templates/sample-statement.pdf";
    private static final String SUPPLIER_FORM_TEMPLATE_PATH = "classpath:templates/supplier-form.pdf";
    private static final String SEAL_IMAGE_PATH = "classpath:images/company-seal.png";

    private final PdfTemplate pdfTemplate;

    public FinanceStatementService(PdfTemplate pdfTemplate) {
        this.pdfTemplate = pdfTemplate;
    }

    /**
     * 为财务对账单叠加动态操作人防泄密平铺暗水印与跳过封面页码
     *
     * @param operatorName  操作人姓名
     * @param operatorCode  操作人工号
     * @param operatorPhone 操作人手机号
     * @return 施加水印与页码后的 PDF 二进制数据
     */
    public byte[] generateWatermarkedStatement(String operatorName, String operatorCode, String operatorPhone) {
        String effectiveName = operatorName != null ? operatorName : "财务专员";
        String effectiveCode = operatorCode != null ? operatorCode : "FIN-001";
        String effectivePhone = operatorPhone != null ? operatorPhone : "13800138000";

        UserContextHolder.set(effectiveName);

        try {
            Map<String, Object> userMap = new HashMap<>();
            userMap.put("name", effectiveName);
            userMap.put("code", effectiveCode);
            userMap.put("phone", effectivePhone);

            Map<String, Object> vars = new HashMap<>();
            vars.put("user", userMap);

            // 1. 添加动态操作人宏变量平铺暗水印
            WatermarkOptions watermarkOptions = WatermarkOptions.builder()
                    .text("#{user.name} (#{user.code}) - #{user.phone} #{now('yyyy-MM-dd')}")
                    .opacity(0.18f)
                    .rotation(-30.0f)
                    .fontSize(16.0f)
                    .color("#990000")
                    .layer(WatermarkLayer.OVERLAY)
                    .variables(vars)
                    .build();

            byte[] watermarkedPdf = pdfTemplate.addWatermarkToBytes(STATEMENT_TEMPLATE_PATH, watermarkOptions);

            // 2. 压印页码（跳过第 1 页封面，重算有效总页数）
            PageNumberOptions pageOptions = PageNumberOptions.builder()
                    .format("第 {page} 页 / 共 {total} 页")
                    .skipPages(1)
                    .recalculateTotal(true)
                    .position(PageNumberPosition.BOTTOM_CENTER)
                    .fontSize(10.0f)
                    .color("#666666")
                    .build();

            return pdfTemplate.addPageNumbersToBytes(watermarkedPdf, pageOptions);
        } finally {
            UserContextHolder.clear();
        }
    }

    /**
     * 为对账单施加 256 位 AES 权限控制密码锁
     *
     * @param sourcePdf     源 PDF 二进制
     * @param userPassword  打开密码
     * @param ownerPassword 管理密码
     * @return 加密后的 PDF 二进制数据
     */
    public byte[] protectStatement(byte[] sourcePdf, String userPassword, String ownerPassword) {
        SecurityOptions options = SecurityOptions.builder()
                .userPassword(userPassword)
                .ownerPassword(ownerPassword)
                .canPrint(true)
                .canCopy(false)
                .canModify(false)
                .canAnnotate(false)
                .build();
        return pdfTemplate.protectToBytes(sourcePdf, options);
    }

    /**
     * 浏览器内联在线预览（inline）对账单
     *
     * @param response      HTTP 响应
     * @param operatorName  操作人姓名
     * @param operatorCode  工号
     * @param operatorPhone 手机号
     */
    public void previewStatementWeb(HttpServletResponse response, String operatorName, String operatorCode, String operatorPhone) {
        byte[] pdfBytes = generateWatermarkedStatement(operatorName, operatorCode, operatorPhone);
        PdfWebUtil.preview(response, "财务对账单_安全预览", pdfBytes);
    }

    /**
     * 附件下载受保护且施加权限锁的对账单
     *
     * @param response      HTTP 响应
     * @param operatorName  操作人
     * @param userPassword  打开密码
     * @param ownerPassword 管理密码
     */
    public void downloadProtectedStatementWeb(HttpServletResponse response, String operatorName,
                                              String userPassword, String ownerPassword) {
        byte[] watermarkedPdf = generateWatermarkedStatement(operatorName, "FIN-009", "13912345678");
        byte[] protectedPdf = protectStatement(watermarkedPdf, userPassword, ownerPassword);
        PdfWebUtil.download(response, "财务对账单_受控归档", protectedPdf);
    }

    /**
     * Web 环境下附件下载带操作人防泄密暗水印的对账单
     *
     * @param response      HTTP 响应
     * @param operatorName  操作人姓名
     * @param operatorCode  工号
     * @param operatorPhone 手机号
     */
    public void downloadStatementWeb(HttpServletResponse response, String operatorName, String operatorCode, String operatorPhone) {
        byte[] pdfBytes = generateWatermarkedStatement(operatorName, operatorCode, operatorPhone);
        PdfWebUtil.download(response, "财务对账单_水印附件", pdfBytes);
    }

    /**
     * Web 环境下附件下载施加跨页骑缝章的对账单
     *
     * @param response HTTP 响应
     */
    public void downloadCrossPageSealStatementWeb(HttpServletResponse response) {
        byte[] watermarkedPdf = generateWatermarkedStatement("审计组长", "AUD-001", "13800138000");
        byte[] sealedPdf = stampCrossPageSeal(watermarkedPdf, "classpath:images/company-seal.png");
        PdfWebUtil.download(response, "财务对账单_跨页骑缝章", sealedPdf);
    }

    /**
     * Web 环境下附件下载在指定页面绝对物理坐标加盖电子公章的对账单
     *
     * @param response  HTTP 响应
     * @param pageIndex 目标页码索引（-1 为倒数第一页即尾页）
     */
    public void downloadAbsoluteSealStatementWeb(HttpServletResponse response, int pageIndex) {
        byte[] watermarkedPdf = generateWatermarkedStatement("财务总监", "FIN-000", "13800000000");
        ImageStampOptions stampOptions = ImageStampOptions.of(
                "classpath:images/company-seal.png",
                pageIndex,
                420.0f,
                120.0f,
                100.0f,
                100.0f
        );
        byte[] stampedPdf = pdfTemplate.addImageStampToBytes(watermarkedPdf, stampOptions);
        PdfWebUtil.download(response, "财务对账单_尾页盖章", stampedPdf);
    }

    /**
     * Web 环境下附件下载施加重算页码（跳过封面）的对账单
     *
     * @param response  HTTP 响应
     * @param skipPages 跳过的封面页数
     */
    public void downloadPageNumberStatementWeb(HttpServletResponse response, int skipPages) {
        PageNumberOptions pageOptions = PageNumberOptions.builder()
                .format("第 {page} 页 / 共 {total} 页")
                .skipPages(Math.max(0, skipPages))
                .recalculateTotal(true)
                .position(PageNumberPosition.BOTTOM_CENTER)
                .fontSize(10.0f)
                .color("#666666")
                .build();
        byte[] pagedPdf = pdfTemplate.addPageNumbersToBytes(STATEMENT_TEMPLATE_PATH, pageOptions);
        PdfWebUtil.download(response, "财务对账单_重算页码", pagedPdf);
    }

    /**
     * 校验财务对账单 256 位 AES 权限锁口令与解密有效性
     *
     * @param password       设置的密码
     * @param verifyPassword 待验证的密码
     * @return 密码匹配解密成功返回 true，否则返回 false
     */
    public boolean verifyStatementPassword(String password, String verifyPassword) {
        try {
            byte[] watermarkedPdf = generateWatermarkedStatement("安全专员", "SEC-001", "13800138000");
            byte[] protectedPdf = protectStatement(watermarkedPdf, password, "AdminOwnerPass#2026");
            byte[] unprotected = pdfTemplate.unprotectToBytes(protectedPdf, verifyPassword);
            return !pdfTemplate.isEncrypted(unprotected);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 场景编排：为多页对账单施加跨页骑缝章
     *
     * @param sourcePdf  对账单 PDF 源
     * @param sealSource 印章图片输入源
     * @return 施加骑缝章后的 PDF 二进制数据
     */
    public byte[] stampCrossPageSeal(Object sourcePdf, Object sealSource) {
        return pdfTemplate.stampCrossPageSealToBytes(sourcePdf, sealSource);
    }

    /**
     * 场景编排：将对账单指定页面渲染为高清 PNG 预览图
     *
     * @param sourcePdf 对账单 PDF 源
     * @param pageIndex 页面索引（从 0 开始）
     * @param dpi       渲染分辨率
     * @return PNG 图片二进制字节数组
     */
    public byte[] renderStatementPageToPng(Object sourcePdf, int pageIndex, float dpi) {
        return pdfTemplate.renderPageToPng(sourcePdf, pageIndex, dpi);
    }

    /**
     * 场景编排：多对账单物理合并（Merge）
     *
     * @param statements 多份对账单输入源
     * @return 合并后的 PDF 二进制数据
     */
    public byte[] mergeStatements(java.util.List<?> statements) {
        return pdfTemplate.mergeToBytes(statements);
    }

    /**
     * 场景编排：对账单物理分页切分（Split）
     *
     * @param statementPdf 对账单 PDF 源
     * @param pagesPerFile 每个文件的页数
     * @return 切分后的单文件切片列表
     */
    public java.util.List<byte[]> splitStatement(Object statementPdf, int pagesPerFile) {
        return pdfTemplate.splitToBytes(statementPdf, pagesPerFile);
    }

    /**
     * 场景编排：对账单页面物理旋转（Rotate）
     *
     * @param statementPdf 对账单 PDF 源
     * @param angle        旋转角度（90, 180, 270）
     * @param pages        目标页码（1-based）
     * @return 旋转后的 PDF 二进制数据
     */
    public byte[] rotateStatement(Object statementPdf, int angle, int... pages) {
        return pdfTemplate.rotatePagesToBytes(statementPdf, angle, pages);
    }

    /**
     * Web 环境下流式输出对账单指定页面的 PNG 图像（支持 inline 预览或 attachment 下载）
     *
     * @param response  HTTP 响应对象
     * @param pageIndex 页面索引（从 0 开始）
     * @param dpi       图像分辨率 DPI
     * @param inline    是否内联预览模式
     */
    public void renderPageWeb(HttpServletResponse response, int pageIndex, float dpi, boolean inline) {
        byte[] pngBytes = renderStatementPageToPng(STATEMENT_TEMPLATE_PATH, pageIndex, dpi);
        String filename = String.format("财务对账单_第%d页_%s.png", pageIndex + 1, inline ? "高清预览" : "缩略图");
        PdfWebUtil.writeImageToResponse(response, filename, pngBytes, inline);
    }

    /**
     * Web 环境下多文档物理合并下载
     *
     * @param response HTTP 响应对象
     */
    public void downloadMergedStatementsWeb(HttpServletResponse response) {
        byte[] pdf1 = generateWatermarkedStatement("张三", "FIN-001", "13800000000");
        byte[] pdf2 = generateWatermarkedStatement("李四", "FIN-002", "13900000000");
        byte[] mergedPdf = mergeStatements(java.util.List.of(pdf1, pdf2));
        PdfWebUtil.download(response, "财务对账单_多文档合并", mergedPdf);
    }

    /**
     * Web 环境下对账单物理切分单页下载
     *
     * @param response  HTTP 响应对象
     * @param pageIndex 目标切片页面索引（从 0 开始）
     */
    public void downloadSplitStatementWeb(HttpServletResponse response, int pageIndex) {
        java.util.List<byte[]> splits = splitStatement(STATEMENT_TEMPLATE_PATH, 1);
        int actualIndex = (pageIndex >= 0 && pageIndex < splits.size()) ? pageIndex : 0;
        byte[] splitPdf = splits.get(actualIndex);
        PdfWebUtil.download(response, String.format("财务对账单_拆分页_%d", actualIndex + 1), splitPdf);
    }

    /**
     * Web 环境下对账单物理旋转下载
     *
     * @param response  HTTP 响应对象
     * @param angle     顺时针旋转角度（必须为 90 的倍数）
     * @param pageIndex 目标旋转页面索引（从 0 开始）
     */
    public void downloadRotatedStatementWeb(HttpServletResponse response, int angle, int pageIndex) {
        byte[] rotatedPdf = rotateStatement(STATEMENT_TEMPLATE_PATH, angle, pageIndex);
        PdfWebUtil.download(response, String.format("财务对账单_第%d页旋转%d度", pageIndex + 1, angle), rotatedPdf);
    }

    /**
     * 巡检对账单 11 维核心元数据
     *
     * @return PDF 元数据对象
     */
    public PdfMetadata inspectStatementMetadata() {
        return pdfTemplate.inspectMetadata(STATEMENT_TEMPLATE_PATH);
    }

    /**
     * 抽取对账单指定页码范围纯文本
     *
     * @param startPage 起始页码（1-based）
     * @param endPage   结束页码（1-based）
     * @return 范围内的纯文本
     */
    public String extractStatementText(int startPage, int endPage) {
        return pdfTemplate.extractText(STATEMENT_TEMPLATE_PATH, startPage, endPage);
    }

    /**
     * 探测解析 CJK 中文字体状态
     *
     * @return 已解析的中文字体持有对象
     */
    public ResolvedFont resolveFont() {
        return pdfTemplate.getFontResolver().resolveFont();
    }

    /**
     * Web 环境下导出施加公司印章 Logo 矩阵平铺暗水印的对账单（支持跳过封面页）
     *
     * @param response  HTTP 响应对象
     * @param skipPages 跳过前 N 页不施加水印
     */
    public void downloadImageWatermarkedStatementWeb(HttpServletResponse response, int skipPages) {
        WatermarkOptions options = WatermarkOptions.builder()
                .image(SEAL_IMAGE_PATH)
                .imageWidth(76.0f)
                .imageHeight(76.0f)
                .opacity(0.18f)
                .rotation(-25.0f)
                .rowSpacing(160.0f)
                .colSpacing(190.0f)
                .skipPages(Math.max(0, skipPages))
                .layer(WatermarkLayer.OVERLAY)
                .build();
        byte[] pdfBytes = pdfTemplate.addWatermarkToBytes(STATEMENT_TEMPLATE_PATH, options);
        PdfWebUtil.download(response, "财务对账单_Logo图片暗水印", pdfBytes);
    }

    /**
     * 在对账单中检索指定关键字的物理左下角坐标位置列表
     *
     * @param keyword 待定位关键字文本
     * @return 命中位置列表
     */
    public List<TextPositionMatch> findStatementKeywordPositions(String keyword) {
        return pdfTemplate.findTextPositions(STATEMENT_TEMPLATE_PATH, keyword);
    }

    /**
     * 基于关键字自动定位在对账单上压印电子公章
     *
     * @param keyword    待定位锚点关键字
     * @param occurrence 多处命中时的盖章策略
     * @return 盖章后的 PDF 二进制数据
     */
    public byte[] stampKeywordOnStatement(String keyword, KeywordStampOccurrence occurrence) {
        KeywordStampOptions options = KeywordStampOptions.builder()
                .keyword(keyword)
                .stampImage(SEAL_IMAGE_PATH)
                .width(96.0f)
                .height(96.0f)
                .offsetX(16.0f)
                .offsetY(-30.0f)
                .occurrence(occurrence != null ? occurrence : KeywordStampOccurrence.LAST)
                .failIfNotFound(true)
                .build();
        return pdfTemplate.stampAtKeywordToBytes(STATEMENT_TEMPLATE_PATH, options);
    }

    /**
     * Web 环境下导出按关键字自动定位盖章的对账单
     *
     * @param response   HTTP 响应对象
     * @param keyword    待定位锚点关键字
     * @param occurrence 命中策略（FIRST / LAST / ALL）
     */
    public void downloadKeywordStampedStatementWeb(HttpServletResponse response, String keyword, KeywordStampOccurrence occurrence) {
        byte[] stampedPdf = stampKeywordOnStatement(keyword, occurrence);
        PdfWebUtil.download(response, "财务对账单_关键字自动盖章", stampedPdf);
    }

    /**
     * 对对账单执行敏感关键字物理不可逆光栅涂黑脱敏
     *
     * @param request 物理涂黑脱敏请求 DTO
     * @return 脱敏重封后的 PDF 二进制数据
     */
    public byte[] redactStatement(PdfRedactRequest request) {
        RedactOptions options = RedactOptions.builder()
                .keywords(request.getKeywords())
                .maskColor(request.getMaskColor() != null && !request.getMaskColor().isBlank() ? request.getMaskColor() : "#000000")
                .dpi(request.getDpi())
                .password(request.getPassword())
                .build();
        return pdfTemplate.redactToBytes(STATEMENT_TEMPLATE_PATH, options);
    }

    /**
     * Web 环境下导出按 DTO 参数物理涂黑脱敏后的对账单
     *
     * @param response HTTP 响应对象
     * @param request  物理涂黑脱敏请求 DTO
     */
    public void downloadRedactedStatementWeb(HttpServletResponse response, PdfRedactRequest request) {
        byte[] redactedPdf = redactStatement(request);
        PdfWebUtil.download(response, "财务对账单_物理涂黑脱敏", redactedPdf);
    }

    /**
     * Web 环境下通过 GET 快捷体验关键字物理不可逆涂黑脱敏
     *
     * @param response HTTP 响应对象
     * @param keyword  待抹除敏感关键字
     */
    public void downloadRedactedKeywordsStatementWeb(HttpServletResponse response, String keyword) {
        byte[] redactedPdf = pdfTemplate.redactKeywordsToBytes(STATEMENT_TEMPLATE_PATH, List.of(keyword));
        PdfWebUtil.download(response, "财务对账单_关键字物理涂黑", redactedPdf);
    }

    /**
     * Web 环境下增量更新对账单文档元数据并下载
     *
     * @param response HTTP 响应对象
     * @param request  元数据更新请求 DTO
     */
    public void updateStatementMetadataWeb(HttpServletResponse response, PdfMetadataUpdateRequest request) {
        PdfMetadataUpdate update = PdfMetadataUpdate.builder()
                .title(request.getTitle())
                .author(request.getAuthor())
                .subject(request.getSubject())
                .keywords(request.getKeywords())
                .creator(request.getCreator())
                .password(request.getPassword())
                .build();
        byte[] updatedPdf = pdfTemplate.updateMetadataToBytes(STATEMENT_TEMPLATE_PATH, update);
        PdfWebUtil.download(response, "财务对账单_元数据更新", updatedPdf);
    }

    /**
     * Web 环境下先注入示例元数据再执行彻底脱敏剥离并下载
     *
     * @param response HTTP 响应对象
     */
    public void stripStatementMetadataWeb(HttpServletResponse response) {
        PdfMetadataUpdate seed = PdfMetadataUpdate.builder()
                .title("内部机密对账单")
                .author("财务内控部")
                .subject("待剥离主题")
                .keywords("机密,对账")
                .creator("Internal ERP")
                .build();
        byte[] seeded = pdfTemplate.updateMetadataToBytes(STATEMENT_TEMPLATE_PATH, seed);
        byte[] stripped = pdfTemplate.stripMetadataToBytes(seeded);
        PdfWebUtil.download(response, "财务对账单_元数据剥离", stripped);
    }

    /**
     * 演示加密 PDF 文档口令穿透自省元数据
     *
     * @param password 打开密码
     * @return 解密后探测得到的 PDF 元数据
     */
    public PdfMetadata inspectProtectedStatementMetadata(String password) {
        byte[] watermarked = generateWatermarkedStatement("安全专员", "AUD-007", "13800138000");
        byte[] protectedPdf = protectStatement(watermarked, password, "AdminOwnerPass#2026");
        return pdfTemplate.inspectMetadata(protectedPdf, password);
    }

    /**
     * 场景编排：将企业印章/凭证图像资产编排合成为标准或原图尺寸 PDF
     *
     * @param fitToImage 是否按原图尺寸精确封装成页
     * @return 合成的 PDF 二进制数据
     */
    public byte[] generateImagesToPdf(boolean fitToImage) {
        ImageToPdfOptions options = fitToImage ? ImageToPdfOptions.fitToImage() : ImageToPdfOptions.defaults();
        return pdfTemplate.imagesToPdfToBytes(List.of(SEAL_IMAGE_PATH), options);
    }

    /**
     * Web 环境下将图像资产合成为 PDF 并流式下载
     *
     * @param response   HTTP 响应对象
     * @param fitToImage 是否按原图尺寸封装
     */
    public void downloadImagesToPdfWeb(HttpServletResponse response, boolean fitToImage) {
        byte[] pdfBytes = generateImagesToPdf(fitToImage);
        PdfWebUtil.download(response, "凭证图像合成归档", pdfBytes);
    }

    /**
     * Web 环境下物理删除对账单指定页面索引并下载
     *
     * @param response  HTTP 响应对象
     * @param pageIndex 待删除页面索引（0-based）
     */
    public void downloadDeletedPagesStatementWeb(HttpServletResponse response, int pageIndex) {
        byte[] pdfBytes = pdfTemplate.deletePagesToBytes(STATEMENT_TEMPLATE_PATH, pageIndex);
        PdfWebUtil.download(response, "财务对账单_删除指定页", pdfBytes);
    }

    /**
     * Web 环境下按倒序重排对账单页面结构并下载
     *
     * @param response HTTP 响应对象
     */
    public void downloadReorderedPagesStatementWeb(HttpServletResponse response) {
        byte[] pdfBytes = pdfTemplate.reorderPagesToBytes(STATEMENT_TEMPLATE_PATH, 1, 0);
        PdfWebUtil.download(response, "财务对账单_页面倒序重排", pdfBytes);
    }

    /**
     * Web 环境下向对账单指定位置插入供应商表单附页并下载
     *
     * @param response          HTTP 响应对象
     * @param insertAtPageIndex 插入位置索引（0-based）
     */
    public void downloadInsertedPagesStatementWeb(HttpServletResponse response, int insertAtPageIndex) {
        byte[] pdfBytes = pdfTemplate.insertPagesToBytes(STATEMENT_TEMPLATE_PATH, SUPPLIER_FORM_TEMPLATE_PATH, insertAtPageIndex);
        PdfWebUtil.download(response, "财务对账单_插入附件页", pdfBytes);
    }
}

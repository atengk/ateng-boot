package io.github.atengk.boot.sample.document.service;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.document.enums.PageNumberPosition;
import io.github.atengk.boot.document.enums.WatermarkLayer;
import io.github.atengk.boot.document.font.ResolvedFont;
import io.github.atengk.boot.document.pdf.PdfTemplate;
import io.github.atengk.boot.document.pdf.model.CrossPageSealOptions;
import io.github.atengk.boot.document.pdf.model.ImageStampOptions;
import io.github.atengk.boot.document.pdf.model.PageNumberOptions;
import io.github.atengk.boot.document.pdf.model.PdfMetadata;
import io.github.atengk.boot.document.pdf.model.SecurityOptions;
import io.github.atengk.boot.document.pdf.model.WatermarkOptions;
import io.github.atengk.boot.document.pdf.util.PdfWebUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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

        response.setContentType("image/png");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setDateHeader(HttpHeaders.EXPIRES, 0);

        ContentDisposition.Builder builder = inline ? ContentDisposition.inline() : ContentDisposition.attachment();
        ContentDisposition disposition = builder
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());

        try {
            OutputStream os = response.getOutputStream();
            os.write(pngBytes);
            os.flush();
        } catch (Exception e) {
            throw new RuntimeException("流式写入 PNG 响应失败: " + e.getMessage(), e);
        }
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
}

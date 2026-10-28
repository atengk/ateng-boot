package io.github.atengk.boot.sample.document.service;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.document.enums.PageNumberPosition;
import io.github.atengk.boot.document.enums.WatermarkLayer;
import io.github.atengk.boot.document.pdf.PdfTemplate;
import io.github.atengk.boot.document.pdf.model.PageNumberOptions;
import io.github.atengk.boot.document.pdf.model.SecurityOptions;
import io.github.atengk.boot.document.pdf.model.WatermarkOptions;
import io.github.atengk.boot.document.pdf.util.PdfWebUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;

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
}

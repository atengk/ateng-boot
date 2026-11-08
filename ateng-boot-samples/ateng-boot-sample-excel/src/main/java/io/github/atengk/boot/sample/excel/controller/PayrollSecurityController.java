package io.github.atengk.boot.sample.excel.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.excel.model.ExcelReadResult;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.excel.util.ExcelWatermarkUtil;
import io.github.atengk.boot.excel.util.ExcelWebUtil;
import io.github.atengk.boot.sample.excel.vo.PayrollSecurityVO;
import io.github.atengk.boot.web.annotation.WebLog;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

/**
 * 薪酬安全与防护控制器
 * <p>
 * 提供敏感数据动态脱敏与公式注入防护对照导出、
 * -30° 防伪全屏暗水印与工作表只读保护密码导出、
 * POI 原生 Agile AES-256 整工作簿强加密导出、
 * 以及带密码透明解密解析上传闭环。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-08
 */
@RestController
@RequestMapping("/sample/excel/security")
public class PayrollSecurityController {

    public static final String DEFAULT_ENCRYPT_PASSWORD = "AgilePass@2026";
    public static final String DEFAULT_WATERMARK_PASSWORD = "Payroll@2026";
    public static final String DEFAULT_WATERMARK_TEXT = "绝密 · 财务薪酬部 · 2026-11-08";

    /**
     * 端点 1：@ExcelSensitive 动态脱敏与 @ExcelFormulaAllowed 公式注入防护对照演示导出
     *
     * @param response HTTP Servlet 响应对象
     * @param fileName 导出文件名
     * @throws IOException IO 异常
     */
    @GetMapping("/export-contrast")
    @WebLog(value = "薪酬安全防护对照导出", printParams = false, printResult = false, printCostTime = true)
    public void exportContrast(
            HttpServletResponse response,
            @RequestParam(value = "fileName", defaultValue = "薪酬安全防护对照表.xlsx") String fileName) throws IOException {
        List<PayrollSecurityVO> data = createSampleSecurityList();
        ExcelWebUtil.export(response, fileName, data, PayrollSecurityVO.class);
    }

    /**
     * 端点 2：-30° 全屏平铺背景暗水印与工作表只读保护密码导出
     *
     * @param response           HTTP Servlet 响应对象
     * @param watermarkText      水印文字
     * @param protectionPassword 工作表保护密码
     * @param fileName           导出文件名
     * @throws IOException IO 异常
     */
    @GetMapping("/export-watermark")
    @WebLog(value = "防伪暗水印与只读保护导出", printParams = true, printResult = false, printCostTime = true)
    public void exportWatermark(
            HttpServletResponse response,
            @RequestParam(value = "watermarkText", defaultValue = DEFAULT_WATERMARK_TEXT) String watermarkText,
            @RequestParam(value = "protectionPassword", defaultValue = DEFAULT_WATERMARK_PASSWORD) String protectionPassword,
            @RequestParam(value = "fileName", defaultValue = "薪酬机密_防伪暗水印.xlsx") String fileName) throws IOException {
        List<PayrollSecurityVO> data = createSampleSecurityList();
        ExcelWebUtil.exportWithWatermark(response, fileName, data, PayrollSecurityVO.class, watermarkText, protectionPassword);
    }

    /**
     * 端点 3：基于 POI 原生 Agile Encryption (AES-256 + SHA-512) 整工作簿强加密导出
     *
     * @param response HTTP Servlet 响应对象
     * @param password 加密密码
     * @param fileName 导出文件名
     * @throws IOException IO 异常
     */
    @GetMapping("/export-encrypt")
    @WebLog(value = "整工作簿 AES-256 强加密导出", printParams = true, printResult = false, printCostTime = true)
    public void exportEncrypt(
            HttpServletResponse response,
            @RequestParam(value = "password", defaultValue = DEFAULT_ENCRYPT_PASSWORD) String password,
            @RequestParam(value = "fileName", defaultValue = "薪酬机密_AES256加密.xlsx") String fileName) throws IOException {
        List<PayrollSecurityVO> data = createSampleSecurityList();
        ExcelWebUtil.prepareWebResponse(response, fileName);
        ExcelUtil.export(response.getOutputStream(), "薪酬机密", data, PayrollSecurityVO.class, null, null, password);
    }

    /**
     * 端点 4：下载预置加密的测试样本工作簿（密码: AgilePass@2026），供体验一键上传解密
     *
     * @param response HTTP Servlet 响应对象
     * @param fileName 导出文件名
     * @throws IOException IO 异常
     */
    @GetMapping("/sample-encrypted")
    @WebLog(value = "下载加密测试样本工作簿", printParams = false, printResult = false, printCostTime = true)
    public void downloadEncryptedSample(
            HttpServletResponse response,
            @RequestParam(value = "fileName", defaultValue = "薪酬机密_加密测试样本.xlsx") String fileName) throws IOException {
        List<PayrollSecurityVO> data = createSampleSecurityList();
        ExcelWebUtil.prepareWebResponse(response, fileName);
        ExcelUtil.export(response.getOutputStream(), "薪酬机密", data, PayrollSecurityVO.class, null, null, DEFAULT_ENCRYPT_PASSWORD);
    }

    /**
     * 端点 5：带密码透明解密解析上传工作簿接口（正确密码成功解密，缺失/错误密码拦截阻断）
     *
     * @param file     上传的加密 Excel 文件
     * @param password 访问密码（可选）
     * @return 强类型解析与校验结果
     * @throws IOException IO 异常
     */
    @PostMapping(value = "/import-decrypt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @WebLog(value = "加密工作簿解密解析上传", printParams = true, printResult = true, printCostTime = true)
    public Result<ExcelReadResult<PayrollSecurityVO>> importDecrypt(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "password", required = false) String password) throws IOException {
        try (InputStream in = file.getInputStream()) {
            ExcelReadResult<PayrollSecurityVO> result = ExcelUtil.readWithResult(in, PayrollSecurityVO.class, password);
            return Result.success(result);
        }
    }

    // ==================== 业务模拟数据生成 ====================

    private List<PayrollSecurityVO> createSampleSecurityList() {
        return List.of(
                PayrollSecurityVO.builder()
                        .id(1001L)
                        .name("张三")
                        .phone("13800138000")
                        .idCard("110101199003072345")
                        .baseSalary(new BigDecimal("28000.00"))
                        .performanceFormula("=SUM(E2*1.2)")
                        .externalRemark("=cmd|'/C calc'!A0")
                        .lockedGrade("P8 专家")
                        .build(),
                PayrollSecurityVO.builder()
                        .id(1002L)
                        .name("李四")
                        .phone("13900139000")
                        .idCard("310101199205126789")
                        .baseSalary(new BigDecimal("22000.00"))
                        .performanceFormula("=AVERAGE(E2:E3)")
                        .externalRemark("+12345678")
                        .lockedGrade("P7 资深")
                        .build(),
                PayrollSecurityVO.builder()
                        .id(1003L)
                        .name("王五")
                        .phone("13700137000")
                        .idCard("440101199511203456")
                        .baseSalary(new BigDecimal("16000.00"))
                        .performanceFormula("=SUM(E4*1.1)")
                        .externalRemark("@SUM(1+1)")
                        .lockedGrade("P6 高级")
                        .build()
        );
    }
}

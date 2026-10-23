package io.github.atengk.boot.sample.document.service;

import io.github.atengk.boot.document.pdf.PdfTemplate;
import io.github.atengk.boot.document.pdf.model.FormOptions;
import io.github.atengk.boot.document.pdf.util.PdfWebUtil;
import io.github.atengk.boot.sample.document.model.SupplierFormRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 场景三：供应商资质准入 AcroForm 表单填报与矢量扁平化服务
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Service
public class SupplierFormService {

    private static final String SUPPLIER_FORM_TEMPLATE_PATH = "classpath:templates/supplier-form.pdf";

    private final PdfTemplate pdfTemplate;

    public SupplierFormService(PdfTemplate pdfTemplate) {
        this.pdfTemplate = pdfTemplate;
    }

    /**
     * 填充供应商准入表单并执行不可逆矢量扁平化
     *
     * @param request 供应商填报数据
     * @param flatten 是否执行矢量扁平化固化数据
     * @return 填报后的 PDF 二进制数据
     */
    public byte[] fillSupplierForm(SupplierFormRequest request, boolean flatten) {
        Map<String, Object> formData = new HashMap<>();
        formData.put("supplierName", request.getSupplierName());
        formData.put("contactPhone", request.getContactPhone());
        formData.put("qualificationPassed", Boolean.TRUE.equals(request.getQualificationPassed()));

        FormOptions options = FormOptions.builder()
                .flatten(flatten)
                .build();

        return pdfTemplate.fillFormToBytes(SUPPLIER_FORM_TEMPLATE_PATH, formData, options);
    }

    /**
     * Web 环境下流式导出已扁平化防篡改的供应商准入表
     *
     * @param response HTTP 响应对象
     * @param request  供应商填报数据
     */
    public void downloadSupplierFormWeb(HttpServletResponse response, SupplierFormRequest request) {
        byte[] pdfBytes = fillSupplierForm(request, true);
        String fileName = String.format("供应商准入登记表_%s", request.getSupplierName());
        PdfWebUtil.download(response, fileName, pdfBytes);
    }
}

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
     * 巡检供应商准入表单所有预定义交互式字段元数据
     *
     * @return 表单字段描述符列表
     */
    public java.util.List<io.github.atengk.boot.document.pdf.model.PdfFormFieldDescriptor> listFormFields() {
        return pdfTemplate.listFormFields(SUPPLIER_FORM_TEMPLATE_PATH);
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

    /**
     * Web 环境下流式导出保留交互式控件的供应商准入表
     *
     * @param response      HTTP 响应
     * @param supplierName  供应商名称
     * @param contactPhone 联系电话
     */
    public void downloadInteractiveSupplierFormWeb(HttpServletResponse response, String supplierName, String contactPhone) {
        SupplierFormRequest req = buildDefaultSupplierFormRequest(supplierName, contactPhone);
        byte[] pdfBytes = fillSupplierForm(req, false);
        String fileName = String.format("供应商准入登记表_交互式_%s", req.getSupplierName());
        PdfWebUtil.download(response, fileName, pdfBytes);
    }

    /**
     * Web 环境下流式导出已矢量扁平化固化防篡改的供应商准入表
     *
     * @param response      HTTP 响应
     * @param supplierName  供应商名称
     * @param contactPhone 联系电话
     */
    public void downloadFlattenedSupplierFormWeb(HttpServletResponse response, String supplierName, String contactPhone) {
        SupplierFormRequest req = buildDefaultSupplierFormRequest(supplierName, contactPhone);
        downloadSupplierFormWeb(response, req);
    }

    /**
     * 构建具备默认备选值的供应商准入表单请求对象
     *
     * @param supplierName  供应商名称（空值兜底）
     * @param contactPhone 联系电话（空值兜底）
     * @return 供应商填报请求传输对象
     */
    private SupplierFormRequest buildDefaultSupplierFormRequest(String supplierName, String contactPhone) {
        String name = (supplierName != null && !supplierName.trim().isEmpty()) ? supplierName.trim() : "未来智能网络科技(深圳)有限公司";
        String phone = (contactPhone != null && !contactPhone.trim().isEmpty()) ? contactPhone.trim() : "0755-88889999";
        return SupplierFormRequest.builder()
                .supplierName(name)
                .contactPhone(phone)
                .qualificationPassed(true)
                .build();
    }
}

package io.github.atengk.boot.sample.document.service;

import io.github.atengk.boot.document.word.WordTemplate;
import io.github.atengk.boot.document.word.model.FloatingPicture;
import io.github.atengk.boot.document.word.util.WordWebUtil;
import io.github.atengk.boot.sample.document.model.ContractRenderRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 场景一：电子劳动合同自动化生成与防篡改服务
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Service
public class ContractService {

    private static final String CONTRACT_TEMPLATE_PATH = "classpath:templates/contract-template.docx";
    private static final String ATTACHMENT_TEMPLATE_PATH = "classpath:templates/attachment-clause.docx";
    private static final String SEAL_IMAGE_PATH = "images/company-seal.png";

    private static final Logger log = LoggerFactory.getLogger(ContractService.class);

    private final WordTemplate wordTemplate;

    public ContractService(WordTemplate wordTemplate) {
        this.wordTemplate = wordTemplate;
    }

    /**
     * 完整生成电子劳动合同与附件拼装后的 DOCX 二进制数据
     *
     * @param request 劳动合同填报参数
     * @return 最终合成的 Word 二进制数据
     */
    public byte[] generateContractDocx(ContractRenderRequest request) {
        // 1. 组装数据模型与浮动公章
        Map<String, Object> dataModel = buildDataModel(request);

        // 2. 编译渲染主合同
        byte[] mainContract = wordTemplate.renderToBytes(CONTRACT_TEMPLATE_PATH, dataModel);

        // 3. 物理无缝拼装附件保密协议
        return wordTemplate.stitchToBytes(mainContract, ATTACHMENT_TEMPLATE_PATH);
    }

    /**
     * 生成劳动合同二进制文档（别名方法）
     */
    public byte[] generateContractDocument(ContractRenderRequest request) {
        return generateContractDocx(request);
    }

    /**
     * Web 环境下流式导出电子劳动合同至客户端浏览器
     *
     * @param response HTTP 响应对象
     * @param request  劳动合同填报参数
     */
    public void exportContractToWeb(HttpServletResponse response, ContractRenderRequest request) {
        byte[] finalDocx = generateContractDocx(request);
        String fileName = String.format("劳动雇佣合同_%s", request.getEmployeeName());
        WordWebUtil.writeToResponse(response, fileName, finalDocx);
    }

    private Map<String, Object> buildDataModel(ContractRenderRequest req) {
        Map<String, Object> map = new HashMap<>();
        map.put("employeeName", req.getEmployeeName());
        map.put("idCard", req.getIdCard());
        map.put("department", req.getDepartment());
        map.put("postName", req.getPostName());
        map.put("salary", req.getSalary());
        map.put("signDate", req.getSignDate());
        map.put("items", req.getItems());

        // 注入浮动公章（使用 FloatingPicture 设定尺寸与居右浮动偏移）
        try {
            ClassPathResource sealRes = new ClassPathResource(SEAL_IMAGE_PATH);
            if (sealRes.exists()) {
                try (InputStream is = sealRes.getInputStream()) {
                    byte[] sealBytes = is.readAllBytes();
                    FloatingPicture seal = FloatingPicture.ofBytes(sealBytes, 120, 120, 20, -30);
                    map.put("seal", seal);
                }
            } else {
                log.warn("未定位到指定印章图片资产: {}", SEAL_IMAGE_PATH);
            }
        } catch (Exception e) {
            log.warn("加载印章图片资产失败: {}", e.getMessage(), e);
        }

        return map;
    }

    /**
     * 对现有的 DOCX 表格执行单元格合并操作（支持水平跨列合并与垂直跨行合并）
     *
     * @param docxBytes 源 Word 二进制数据
     * @return 合并处理后的 Word 二进制数据
     */
    public byte[] mergeContractTableCells(byte[] docxBytes) {
        try (java.io.InputStream is = new java.io.ByteArrayInputStream(docxBytes);
             org.apache.poi.xwpf.usermodel.XWPFDocument doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(is);
             java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            if (!doc.getTables().isEmpty()) {
                org.apache.poi.xwpf.usermodel.XWPFTable table = doc.getTables().get(0);
                if (table.getNumberOfRows() > 1 && table.getRow(0).getTableCells().size() > 1) {
                    // 水平合并第一行第 0 列至第 1 列
                    WordTemplate.mergeCellsHorizontal(table, 0, 0, 1);
                }
            }
            doc.write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new io.github.atengk.boot.document.exception.WordRenderException(
                    io.github.atengk.boot.document.exception.DocumentResultCode.DOCUMENT_OPERATION_FAILED,
                    "表格单元格合并处理失败: " + e.getMessage(), e);
        }
    }
}

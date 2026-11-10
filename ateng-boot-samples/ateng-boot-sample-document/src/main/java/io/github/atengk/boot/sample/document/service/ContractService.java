package io.github.atengk.boot.sample.document.service;

import io.github.atengk.boot.document.word.WordTemplate;
import io.github.atengk.boot.document.word.model.FloatingPicture;
import io.github.atengk.boot.document.word.util.WordWebUtil;
import io.github.atengk.boot.sample.document.model.ContractRenderRequest;
import io.github.atengk.boot.sample.document.model.ProjectExperience;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
     * 仅渲染主合同（不拼装附件），用于单文档模板审核与验真
     *
     * @param request 劳动合同填报参数
     * @return 渲染后的 Word 二进制数据
     */
    public byte[] renderMainContract(ContractRenderRequest request) {
        Map<String, Object> dataModel = buildDataModel(request);
        return wordTemplate.renderToBytes(CONTRACT_TEMPLATE_PATH, dataModel);
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

        // 注入浮动公章（基于字符锚定，实现标准骑字盖章压印效果）
        try {
            ClassPathResource sealRes = new ClassPathResource(SEAL_IMAGE_PATH);
            if (sealRes.exists()) {
                try (InputStream is = sealRes.getInputStream()) {
                    byte[] sealBytes = is.readAllBytes();
                    FloatingPicture seal = FloatingPicture.ofBytes(sealBytes, 120, 120, -80, -48);
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
     * 构建具备完整真实业务语义的默认劳动合同请求 DTO
     *
     * @param employeeName 员工姓名（为空时保底为“李云龙”）
     * @return 预填报请求对象
     */
    public ContractRenderRequest createDefaultRequest(String employeeName) {
        String name = (employeeName != null && !employeeName.trim().isEmpty()) ? employeeName.trim() : "李云龙";
        return ContractRenderRequest.builder()
                .employeeName(name)
                .idCard("110101198501011234")
                .department("基础架构研发中心")
                .postName("云原生架构专家")
                .salary("48,000")
                .signDate("2026-11-10")
                .items(List.of(
                        ProjectExperience.builder().projectName("分布式多租户 Starter 套件").role("技术负责人").duration("8 个月").build(),
                        ProjectExperience.builder().projectName("企业级 Office/PDF 治理底座").role("核心架构师").duration("3 个月").build()
                ))
                .build();
    }

    /**
     * Web 环境下流式导出动态表格空数据安全折叠的主合同
     *
     * @param response     HTTP 响应对象
     * @param employeeName 员工姓名
     */
    public void exportEmptyTableContractToWeb(HttpServletResponse response, String employeeName) {
        String name = (employeeName != null && !employeeName.trim().isEmpty()) ? employeeName.trim() : "赵刚";
        ContractRenderRequest req = ContractRenderRequest.builder()
                .employeeName(name)
                .idCard("110101198602022345")
                .department("政务纪检监察室")
                .postName("高级政务监督专员")
                .salary("36,000")
                .signDate("2026-11-10")
                .items(Collections.emptyList())
                .build();
        byte[] docx = renderMainContract(req);
        String fileName = String.format("劳动合同_空表折叠_%s", req.getEmployeeName());
        WordWebUtil.writeToResponse(response, fileName, docx);
    }

    /**
     * 逆向解析合同模板声明的所有占位符变量清单（去重且保序）
     *
     * @return 模板占位符变量名列表
     */
    public List<String> listContractVariables() {
        return wordTemplate.listVariableNames(CONTRACT_TEMPLATE_PATH);
    }

    /**
     * 从生成的劳动合同 Word 文档中抽取段落与表格纯文本
     *
     * @param employeeName 员工姓名
     * @return 提取的正文纯文本
     */
    public String extractContractText(String employeeName) {
        ContractRenderRequest request = createDefaultRequest(employeeName);
        byte[] docx = generateContractDocx(request);
        return wordTemplate.extractText(docx);
    }

    /**
     * Web 环境下流式导出受只读密码锁保护的劳动合同 DOCX
     *
     * @param response     HTTP 响应对象
     * @param employeeName 员工姓名
     * @param password     保护密码
     */
    public void exportProtectedContractToWeb(HttpServletResponse response, String employeeName, String password) {
        ContractRenderRequest request = createDefaultRequest(employeeName);
        byte[] docx = generateContractDocx(request);
        byte[] protectedDocx = wordTemplate.protectToBytes(docx, password);
        String fileName = String.format("劳动合同_只读保护_%s", request.getEmployeeName());
        WordWebUtil.writeToResponse(response, fileName, protectedDocx);
    }

    /**
     * 校验 Word 文档只读密码锁定状态与口令正确性
     *
     * @param password       设置的只读密码
     * @param verifyPassword 待验证的密码
     * @return 密码匹配返回 true，否则返回 false
     */
    public boolean verifyContractProtectionPassword(String password, String verifyPassword) {
        ContractRenderRequest request = createDefaultRequest("密码验证员");
        byte[] docx = generateContractDocx(request);
        byte[] protectedDocx = wordTemplate.protectToBytes(docx, password);
        return wordTemplate.validateProtectionPassword(protectedDocx, verifyPassword);
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

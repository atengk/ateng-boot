package io.github.atengk.boot.sample.excel.service;

import io.github.atengk.boot.excel.enums.HeaderValidationStrategy;
import io.github.atengk.boot.excel.listener.BatchValidationListener;
import io.github.atengk.boot.excel.model.ExcelErrorRow;
import io.github.atengk.boot.excel.model.ExcelReadResult;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.sample.excel.vo.UserExcelVO;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 业务场景 3：数据校验与错题集反馈场景服务
 * <p>
 * 演示 JSR-303 行级校验、表头契约静态校验、超阈值紧急熔断，
 * 以及将错误单元格标红并插入悬浮批注回写生成专属错题集反馈文件。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Service
public class ValidationAndFeedbackScenarioService {

    private final Validator validator;

    public ValidationAndFeedbackScenarioService(Validator validator) {
        this.validator = validator;
    }

    /**
     * 校验上传文件的表头结构契约
     *
     * @param in       Excel 输入流
     * @param strategy 校验策略（严格/忽略顺序/宽松）
     */
    public void validateHeader(InputStream in, HeaderValidationStrategy strategy) {
        ExcelUtil.validateHeader(in, UserExcelVO.class, strategy);
    }

    /**
     * 读取并执行 JSR-303 行级数据校验，聚合所有校验错误行
     *
     * @param in Excel 输入流
     * @return 包含合法数据与错误行明细的读取结果
     */
    public ExcelReadResult<UserExcelVO> readAndValidate(InputStream in) {
        BatchValidationListener<UserExcelVO> listener = new BatchValidationListener<>(UserExcelVO.class, validator, false);
        ExcelUtil.read(in, UserExcelVO.class, listener);
        return listener.toResult();
    }

    /**
     * 带有错误熔断阈值的导入校验（防御超大畸形文件 DOS 攻击）
     *
     * @param in           Excel 输入流
     * @param maxErrorRows 允许的最大错误行数上限，达到时熔断阻断后续解析
     * @return 读取与熔断结果
     */
    public ExcelReadResult<UserExcelVO> readWithCircuitBreaker(InputStream in, int maxErrorRows) {
        BatchValidationListener<UserExcelVO> listener = new BatchValidationListener<>(UserExcelVO.class, validator, false, maxErrorRows);
        ExcelUtil.read(in, UserExcelVO.class, listener);
        return listener.toResult();
    }

    /**
     * 基于原始上传的 Excel 生成带错误标红与悬浮批注的纠错反馈文件
     *
     * @param originalExcelStream 原始上传的 Excel 输入流
     * @param feedbackOut         反馈文件的输出流
     * @param errorRows           校验失败的错误行信息
     */
    public void generateErrorFeedback(InputStream originalExcelStream, OutputStream feedbackOut, List<ExcelErrorRow> errorRows) {
        ExcelUtil.exportErrorFeedback(originalExcelStream, feedbackOut, errorRows);
    }

    /**
     * 差错数据隔离提取导出：仅将校验失败的数据生成专属纠错 Excel 文件
     *
     * @param out        目标输出流
     * @param failedList 失败行实体列表
     * @param errorRows  错误明细列表
     */
    public void exportFailedRows(OutputStream out, List<UserExcelVO> failedList, List<ExcelErrorRow> errorRows) {
        ExcelUtil.exportFailedRows(out, UserExcelVO.class, failedList, errorRows);
    }
}

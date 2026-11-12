package io.github.atengk.boot.sample.smoke.controller;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.excel.util.ExcelWebUtil;
import io.github.atengk.boot.sample.smoke.model.SmokeRecordCreateRequest;
import io.github.atengk.boot.sample.smoke.model.SmokeRecordExportRow;
import io.github.atengk.boot.sample.smoke.model.SmokeRecordPageRequest;
import io.github.atengk.boot.sample.smoke.model.SmokeRecordVO;
import io.github.atengk.boot.sample.smoke.service.SmokeRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

/**
 * 持久层审计与表格导出领域控制器
 * 验证 MyBatis-Plus 审计自动填充、Zero-Entity-Leakage VO 分页转换与 Apache Fesod 脱敏流式下载
 *
 * @author Ateng
 * @since 2026-11-12
 */
@Tag(name = "2. 持久层审计与表格导出")
@RestController
@RequestMapping("/sample/smoke/record")
@RequiredArgsConstructor
public class SmokePersistenceExcelController {

    private final SmokeRecordService smokeRecordService;

    /**
     * 新增冒烟记录（触发审计自动填充）
     *
     * @param request 新增记录请求 DTO
     * @return 新增后的记录视图 VO
     */
    @Operation(summary = "新增冒烟记录")
    @PostMapping("/create")
    public Result<SmokeRecordVO> create(@RequestBody @Valid SmokeRecordCreateRequest request) {
        return Result.success(smokeRecordService.createRecord(request));
    }

    /**
     * 分页查询冒烟记录
     *
     * @param request 分页查询请求 DTO
     * @return 标准化分页结果
     */
    @Operation(summary = "分页查询冒烟记录")
    @PostMapping("/page")
    public Result<PageResult<SmokeRecordVO>> page(@RequestBody @Valid SmokeRecordPageRequest request) {
        return Result.success(smokeRecordService.pageRecords(request));
    }

    /**
     * 流式导出脱敏 Excel 表格
     *
     * @param status 可选状态过滤
     * @return 标准 RFC 5987 Excel 下载流响应
     */
    @Operation(summary = "流式导出脱敏 Excel 表格")
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(@RequestParam(required = false) String status) {
        List<SmokeRecordExportRow> rows = smokeRecordService.listExportRows(status);
        return ExcelWebUtil.toResponseEntity("smoke-records", rows, SmokeRecordExportRow.class);
    }
}

package io.github.atengk.boot.sample.smoke.service;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.service.AtengBaseService;
import io.github.atengk.boot.sample.smoke.entity.SmokeRecordEntity;
import io.github.atengk.boot.sample.smoke.model.SmokeRecordCreateRequest;
import io.github.atengk.boot.sample.smoke.model.SmokeRecordExportRow;
import io.github.atengk.boot.sample.smoke.model.SmokeRecordPageRequest;
import io.github.atengk.boot.sample.smoke.model.SmokeRecordVO;

import java.util.List;

/**
 * 冒烟记录业务服务接口
 *
 * @author Ateng
 * @since 2026-11-12
 */
public interface SmokeRecordService extends AtengBaseService<SmokeRecordEntity> {

    /**
     * 新增冒烟记录并返回视图对象
     *
     * @param request 新增请求 DTO
     * @return 新增后的记录 VO
     */
    SmokeRecordVO createRecord(SmokeRecordCreateRequest request);

    /**
     * 分页查询冒烟记录并投影为视图对象
     *
     * @param request 分页查询请求 DTO
     * @return 标准 5 要素分页结果
     */
    PageResult<SmokeRecordVO> pageRecords(SmokeRecordPageRequest request);

    /**
     * 查询导出行列表（保底空集合，严禁返回 null）
     *
     * @param status 可选状态过滤
     * @return 导出行集合
     */
    List<SmokeRecordExportRow> listExportRows(String status);
}

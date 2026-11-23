package io.github.atengk.boot.sample.smoke.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.mybatisplus.util.PageUtil;
import io.github.atengk.boot.sample.smoke.entity.SmokeRecordEntity;
import io.github.atengk.boot.sample.smoke.mapper.SmokeRecordMapper;
import io.github.atengk.boot.sample.smoke.model.SmokeRecordCreateRequest;
import io.github.atengk.boot.sample.smoke.model.SmokeRecordExportRow;
import io.github.atengk.boot.sample.smoke.model.SmokeRecordPageRequest;
import io.github.atengk.boot.sample.smoke.model.SmokeRecordVO;
import io.github.atengk.boot.sample.smoke.service.SmokeRecordService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 冒烟记录业务服务实现
 *
 * @author Ateng
 * @since 2026-11-12
 */
@Service
public class SmokeRecordServiceImpl extends AtengServiceImpl<SmokeRecordMapper, SmokeRecordEntity> implements SmokeRecordService {

    @Override
    public SmokeRecordVO createRecord(SmokeRecordCreateRequest request) {
        Objects.requireNonNull(request, "新增请求参数不能为空");
        SmokeRecordEntity entity = new SmokeRecordEntity(
                request.getTitle(),
                request.getStatus(),
                request.getAmount(),
                request.getMobile()
        );
        save(entity);
        return SmokeRecordVO.fromEntity(entity);
    }

    @Override
    public PageResult<SmokeRecordVO> pageRecords(SmokeRecordPageRequest request) {
        SmokeRecordPageRequest safeQuery = request != null ? request : new SmokeRecordPageRequest();
        LambdaQueryWrapper<SmokeRecordEntity> wrapper = new LambdaQueryWrapper<SmokeRecordEntity>()
                .like(StringUtils.hasText(safeQuery.getTitle()), SmokeRecordEntity::getTitle, safeQuery.getTitle())
                .eq(StringUtils.hasText(safeQuery.getStatus()), SmokeRecordEntity::getStatus, safeQuery.getStatus())
                .orderByDesc(SmokeRecordEntity::getCreateTime);
        Page<SmokeRecordEntity> mpPage = page(PageUtil.toPage(safeQuery, SmokeRecordEntity.class), wrapper);
        return PageUtil.toPageResult(mpPage, SmokeRecordVO::fromEntity);
    }

    @Override
    public List<SmokeRecordExportRow> listExportRows(String status) {
        LambdaQueryWrapper<SmokeRecordEntity> wrapper = new LambdaQueryWrapper<SmokeRecordEntity>()
                .eq(StringUtils.hasText(status), SmokeRecordEntity::getStatus, status)
                .orderByDesc(SmokeRecordEntity::getCreateTime);
        List<SmokeRecordEntity> entities = list(wrapper);
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }
        return entities.stream()
                .map(SmokeRecordExportRow::fromEntity)
                .toList();
    }
}

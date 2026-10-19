package io.github.atengk.boot.sample.service.impl;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryRequest;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.sample.entity.SysDocumentEntity;
import io.github.atengk.boot.sample.mapper.SysDocumentMapper;
import io.github.atengk.boot.sample.service.SysDocumentService;
import io.github.atengk.boot.sample.vo.SysDocumentVO;
import org.springframework.stereotype.Service;

/**
 * 业务文档业务实现
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Service
public class SysDocumentServiceImpl extends AtengServiceImpl<SysDocumentMapper, SysDocumentEntity> implements SysDocumentService {

    @Override
    public PageResult<SysDocumentVO> pageDocumentsWithScope(DynamicQueryRequest request) {
        return pageDynamic("selectPageWithScope", request, SysDocumentVO.class);
    }
}

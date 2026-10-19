package io.github.atengk.boot.sample.service;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryRequest;
import io.github.atengk.boot.mybatisplus.service.AtengBaseService;
import io.github.atengk.boot.sample.entity.SysDocumentEntity;
import io.github.atengk.boot.sample.vo.SysDocumentVO;

/**
 * 业务文档业务接口
 * 继承 AtengBaseService 获得动态查询条件无感直出能力，为控制层提供业务边界防腐
 *
 * @author Ateng
 * @since 2026-10-19
 */
public interface SysDocumentService extends AtengBaseService<SysDocumentEntity> {

    /**
     * 结合 @DataScope 数据权限动态分页查询文档列表并直出 VO
     *
     * @param request 动态查询请求契约
     * @return 文档分页视图结果
     */
    PageResult<SysDocumentVO> pageDocumentsWithScope(DynamicQueryRequest request);
}

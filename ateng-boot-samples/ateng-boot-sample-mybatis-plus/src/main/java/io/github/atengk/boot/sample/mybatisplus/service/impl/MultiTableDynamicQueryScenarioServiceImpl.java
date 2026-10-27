package io.github.atengk.boot.sample.mybatisplus.service.impl;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryRequest;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.sample.mybatisplus.entity.UserEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.UserMapper;
import io.github.atengk.boot.sample.mybatisplus.model.UserDeptDetailVO;
import io.github.atengk.boot.sample.mybatisplus.service.MultiTableDynamicQueryScenarioService;
import org.springframework.stereotype.Service;

/**
 * 多表联查动态 AST 条件推导与别名自愈业务场景服务实现类
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Service
public class MultiTableDynamicQueryScenarioServiceImpl extends AtengServiceImpl<UserMapper, UserEntity>
        implements MultiTableDynamicQueryScenarioService {

    @Override
    public PageResult<UserDeptDetailVO> pageUserDeptByMethodRef(DynamicQueryRequest request) {
        return pageDynamic(baseMapper::selectUserDeptPage, request, UserDeptDetailVO.class);
    }

    @Override
    public PageResult<UserDeptDetailVO> pageUserDeptByTemplate(DynamicQueryRequest request) {
        return getDynamicQueryTemplate().selectPage("selectUserDeptPage", request, UserDeptDetailVO.class);
    }

    @Override
    public boolean existsUserDeptByMethodRef(DynamicQueryRequest request) {
        return existsDynamic(baseMapper::selectUserDeptPage, request);
    }

    @Override
    public boolean existsUserDeptByTemplate(DynamicQueryRequest request) {
        return getDynamicQueryTemplate().exists("selectUserDeptPage", request);
    }
}

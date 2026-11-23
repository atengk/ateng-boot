package io.github.atengk.boot.sample.mybatisplus.service.impl;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryParser;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryRequest;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.sample.mybatisplus.entity.DeptEntity;
import io.github.atengk.boot.sample.mybatisplus.entity.UserEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.DeptMapper;
import io.github.atengk.boot.sample.mybatisplus.mapper.UserMapper;
import io.github.atengk.boot.sample.mybatisplus.model.UserDeptDetailVO;
import io.github.atengk.boot.sample.mybatisplus.service.MultiTableDynamicQueryScenarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 多表联查动态 AST 条件推导与别名自愈业务场景服务实现类
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Service
@RequiredArgsConstructor
public class MultiTableDynamicQueryScenarioServiceImpl extends AtengServiceImpl<UserMapper, UserEntity>
        implements MultiTableDynamicQueryScenarioService {

    private final DeptMapper deptMapper;

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

    @Override
    public List<UserDeptDetailVO> listUserDeptByMethodRef(DynamicQueryRequest request) {
        return listDynamic(baseMapper::selectUserDeptList, request, UserDeptDetailVO.class);
    }

    @Override
    public PageResult<UserDeptDetailVO> pageUserDeptByUrl(String queryString) {
        ensureSeedUserAndDeptIfEmpty();
        DynamicQueryRequest request = DynamicQueryParser.parse(queryString);
        return pageUserDeptByMethodRef(request);
    }

    @Override
    public List<UserDeptDetailVO> listUserDeptByUrl(String queryString) {
        ensureSeedUserAndDeptIfEmpty();
        DynamicQueryRequest request = DynamicQueryParser.parse(queryString);
        return listUserDeptByMethodRef(request);
    }

    @Override
    public boolean existsUserDeptByUrl(String queryString) {
        ensureSeedUserAndDeptIfEmpty();
        DynamicQueryRequest request = DynamicQueryParser.parse(queryString);
        return existsUserDeptByMethodRef(request);
    }

    private void ensureSeedUserAndDeptIfEmpty() {
        Long deptCount = deptMapper.selectCount(null);
        if (deptCount == null || deptCount == 0L) {
            DeptEntity d1 = new DeptEntity("研发一部", "ACTIVE");
            d1.setId(101L);
            deptMapper.insert(d1);

            DeptEntity d2 = new DeptEntity("研发二部", "ACTIVE");
            d2.setId(102L);
            deptMapper.insert(d2);

            DeptEntity d3 = new DeptEntity("运营中心", "INACTIVE");
            d3.setId(103L);
            deptMapper.insert(d3);
        }

        if (count() == 0L) {
            UserEntity u1 = new UserEntity("张三", 25, "zhangsan@example.com", "13800000001", 101L);
            u1.setId(1001L);
            save(u1);

            UserEntity u2 = new UserEntity("李四", 28, "lisi@example.com", "13800000002", 101L);
            u2.setId(1002L);
            save(u2);

            UserEntity u3 = new UserEntity("王五", 32, "wangwu@example.com", "13800000003", 102L);
            u3.setId(1003L);
            save(u3);

            UserEntity u4 = new UserEntity("孙七", 40, "sunqi@example.com", "13800000005", 103L);
            u4.setId(1005L);
            save(u4);
        }
    }
}

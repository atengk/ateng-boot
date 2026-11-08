package io.github.atengk.boot.sample.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryParser;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryRequest;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.mybatisplus.util.PageUtil;
import io.github.atengk.boot.sample.mybatisplus.entity.UserEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.UserMapper;
import io.github.atengk.boot.sample.mybatisplus.model.DataPermissionCompareVO;
import io.github.atengk.boot.sample.mybatisplus.model.UserSearchDTO;
import io.github.atengk.boot.sample.mybatisplus.model.UserVO;
import io.github.atengk.boot.sample.mybatisplus.permission.SampleSecurityContext;
import io.github.atengk.boot.sample.mybatisplus.service.UserService;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 用户业务服务实现
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Service
public class UserServiceImpl extends AtengServiceImpl<UserMapper, UserEntity> implements UserService {

    @Override
    public List<UserVO> listAccessibleUsersByDataScope(List<Long> deptIds, String selfOperator) {
        ensureSeedUsersIfEmpty();
        SampleSecurityContext.setAccessibleDeptIds(deptIds);
        SampleSecurityContext.setSelfOperator(selfOperator);
        try {
            List<UserEntity> entities = baseMapper.selectAccessibleUsers(new QueryWrapper<>());
            if (entities == null || entities.isEmpty()) {
                return Collections.emptyList();
            }
            return entities.stream().map(UserVO::fromEntity).toList();
        } finally {
            SampleSecurityContext.clear();
        }
    }

    @Override
    public DataPermissionCompareVO compareDataPermissionAndBypass(List<Long> deptIds, String selfOperator) {
        ensureSeedUsersIfEmpty();
        SampleSecurityContext.setAccessibleDeptIds(deptIds);
        SampleSecurityContext.setSelfOperator(selfOperator);
        try {
            // 1. 常规 @DataScope AST 权限过滤查询
            List<UserEntity> scoped = baseMapper.selectAccessibleUsers(new QueryWrapper<>());

            // 2. 超级管理员身份秒级短路放行（跳过 AST 语法树解析）
            List<UserEntity> superAdmin = DataPermissionContextHolder.runAsSuperAdmin(
                    () -> baseMapper.selectAccessibleUsers(new QueryWrapper<>())
            );

            // 3. 编程式 runWithoutPermission 闭包豁免放行
            List<UserEntity> withoutPermission = DataPermissionContextHolder.runWithoutPermission(
                    () -> baseMapper.selectAccessibleUsers(new QueryWrapper<>())
            );

            DataPermissionCompareVO compareVO = new DataPermissionCompareVO();
            compareVO.setAccessibleDeptIds(deptIds != null ? deptIds : Collections.emptyList());
            compareVO.setSelfOperator(selfOperator);
            compareVO.setScopedCount(scoped != null ? scoped.size() : 0);
            compareVO.setSuperAdminCount(superAdmin != null ? superAdmin.size() : 0);
            compareVO.setWithoutPermissionCount(withoutPermission != null ? withoutPermission.size() : 0);
            compareVO.setScopedUsers(scoped != null
                    ? scoped.stream().map(UserVO::fromEntity).toList()
                    : Collections.emptyList());
            compareVO.setSuperAdminUsers(superAdmin != null
                    ? superAdmin.stream().map(UserVO::fromEntity).toList()
                    : Collections.emptyList());
            return compareVO;
        } finally {
            SampleSecurityContext.clear();
        }
    }

    @Override
    public PageResult<UserVO> pageUsersSafely(PageQuery pageQuery) {
        ensureSeedUsersIfEmpty();
        // 1. PageUtil.toPage 依托 UserEntity 元数据白名单安全转译排序字段，自动拦截 @DynamicQueryIgnore 与 @FieldEncrypt 字段
        Page<UserEntity> page = PageUtil.toPage(pageQuery, UserEntity.class);
        // 2. 执行分页并流式映射为强类型 UserVO
        return PageUtil.toPageResult(page(page), UserVO::fromEntity);
    }

    @Override
    public PageResult<UserVO> pageUsersByDynamicUrl(String queryString) {
        ensureSeedUsersIfEmpty();
        DynamicQueryRequest request = DynamicQueryParser.parse(queryString);
        return pageDynamic(request, UserVO.class);
    }

    @Override
    public PageResult<UserVO> pageUsersBySearchDto(UserSearchDTO searchDTO) {
        ensureSeedUsersIfEmpty();
        return pageDynamic(searchDTO, UserVO.class);
    }

    private void ensureSeedUsersIfEmpty() {
        if (count() == 0L) {
            UserContextHolder.set("creator-alice");
            try {
                UserEntity u1 = new UserEntity("张三", 25, "zhangsan@example.com", "13800000010", 10L);
                u1.setInternalRemark("核心高管账号-机密");
                save(u1);

                UserEntity u2 = new UserEntity("李四", 30, "lisi@example.com", "13800000020", 20L);
                u2.setInternalRemark("研发骨干账号");
                save(u2);
            } finally {
                UserContextHolder.clear();
            }

            UserContextHolder.set("creator-bob");
            try {
                UserEntity u3 = new UserEntity("王五", 35, "wangwu@example.com", "13800000030", 30L);
                u3.setInternalRemark("外部顾问账号");
                save(u3);
            } finally {
                UserContextHolder.clear();
            }
        }
    }
}

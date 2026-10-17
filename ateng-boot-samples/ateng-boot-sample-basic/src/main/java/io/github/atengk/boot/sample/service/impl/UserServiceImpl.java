package io.github.atengk.boot.sample.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.mybatisplus.util.PageUtil;
import io.github.atengk.boot.sample.dto.UserCreateDTO;
import io.github.atengk.boot.sample.entity.UserEntity;
import io.github.atengk.boot.sample.mapper.UserMapper;
import io.github.atengk.boot.sample.service.UserService;
import io.github.atengk.boot.sample.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 示例用户业务实现类
 * 负责用户创建、详情查询、名称更新及分页检索等业务逻辑，实现对持久层的隔离防护
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createUser(UserCreateDTO dto) {
        BusinessAssert.notNull(dto, "创建用户入参不能为空");
        UserEntity entity = new UserEntity();
        entity.setName(dto.getName());
        entity.setAge(dto.getAge());
        entity.setEmail(dto.getEmail());
        entity.setPhone(dto.getPhone());
        entity.setIdCard(dto.getIdCard());
        entity.setAddress(dto.getAddress());
        entity.setCarLicense(dto.getCarLicense());
        entity.setIpv4(dto.getIpv4());
        entity.setSocialCreditCode(dto.getSocialCreditCode());
        entity.setCustomMask(dto.getCustomMask());
        userMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public UserVO getUserById(Long id) {
        UserEntity user = userMapper.selectById(id);
        BusinessAssert.notNull(user, "目标用户不存在");
        return UserVO.from(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateUserName(Long id, String name) {
        UserEntity user = userMapper.selectById(id);
        BusinessAssert.notNull(user, "目标用户不存在");
        user.setName(name);
        int rows = userMapper.updateById(user);
        return rows > 0;
    }

    @Override
    public PageResult<UserVO> pageUsers(PageQuery query) {
        Page<UserEntity> page = PageUtil.toPage(query);
        Page<UserEntity> entityPage = userMapper.selectPage(page, null);
        return PageUtil.toPageResult(entityPage).map(UserVO::from);
    }
}

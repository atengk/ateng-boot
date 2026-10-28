package io.github.atengk.boot.sample.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.mybatisplus.slowsql.SlowSqlEvent;
import io.github.atengk.boot.sample.mybatisplus.entity.UserEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.UserMapper;
import io.github.atengk.boot.sample.mybatisplus.model.UserDynamicVO;
import io.github.atengk.boot.sample.mybatisplus.service.SlowSqlAlertScenarioService;
import io.github.atengk.boot.sample.mybatisplus.slowsql.SampleSlowSqlAlertProvider;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 慢 SQL 性能拦截、敏感脱敏与告警业务场景服务实现类
 * 恪守实体零泄露（Zero-Entity-Leakage）契约，出参 100% 映射转换为强类型 VO
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Service
public class SlowSqlAlertScenarioServiceImpl implements SlowSqlAlertScenarioService {

    private final UserMapper userMapper;
    private final SampleSlowSqlAlertProvider alertProvider;

    public SlowSqlAlertScenarioServiceImpl(UserMapper userMapper, SampleSlowSqlAlertProvider alertProvider) {
        this.userMapper = userMapper;
        this.alertProvider = alertProvider;
    }

    @Override
    public UserDynamicVO createUserWithEncryptedFields(String name, Integer age, String email, String phone, Long deptId) {
        UserEntity user = new UserEntity(name, age, email, phone, deptId);
        userMapper.insert(user);
        return JsonUtil.convertValue(user, UserDynamicVO.class);
    }

    @Override
    public UserDynamicVO getUserById(Long id) {
        if (id == null) {
            return null;
        }
        UserEntity user = userMapper.selectById(id);
        return user != null ? JsonUtil.convertValue(user, UserDynamicVO.class) : null;
    }

    @Override
    public List<UserDynamicVO> listUsersByAge(Integer minAge) {
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();
        if (minAge != null) {
            wrapper.ge(UserEntity::getAge, minAge);
        }
        List<UserEntity> list = userMapper.selectList(wrapper);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return list.stream()
                .map(item -> JsonUtil.convertValue(item, UserDynamicVO.class))
                .toList();
    }

    @Override
    public List<UserDynamicVO> triggerSlowSqlWithPasswordParam(String username, String password) {
        List<UserEntity> list = userMapper.selectByUsernameAndPassword(username, password);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return list.stream()
                .map(item -> JsonUtil.convertValue(item, UserDynamicVO.class))
                .toList();
    }

    @Override
    public List<SlowSqlEvent> getCapturedAlerts() {
        return alertProvider.getEvents();
    }

    @Override
    public void clearAlerts() {
        alertProvider.clear();
    }
}

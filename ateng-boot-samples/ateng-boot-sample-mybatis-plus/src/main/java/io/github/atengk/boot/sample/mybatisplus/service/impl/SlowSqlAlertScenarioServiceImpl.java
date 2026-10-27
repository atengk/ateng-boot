package io.github.atengk.boot.sample.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.mybatisplus.slowsql.SlowSqlEvent;
import io.github.atengk.boot.sample.mybatisplus.entity.UserEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.UserMapper;
import io.github.atengk.boot.sample.mybatisplus.service.SlowSqlAlertScenarioService;
import io.github.atengk.boot.sample.mybatisplus.slowsql.SampleSlowSqlAlertProvider;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 慢 SQL 性能拦截、敏感脱敏与告警业务场景服务实现类
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Service
public class SlowSqlAlertScenarioServiceImpl extends AtengServiceImpl<UserMapper, UserEntity>
        implements SlowSqlAlertScenarioService {

    private final SampleSlowSqlAlertProvider alertProvider;

    public SlowSqlAlertScenarioServiceImpl(SampleSlowSqlAlertProvider alertProvider) {
        this.alertProvider = alertProvider;
    }

    @Override
    public UserEntity createUserWithEncryptedFields(String name, Integer age, String email, String phone, Long deptId) {
        UserEntity user = new UserEntity(name, age, email, phone, deptId);
        save(user);
        return user;
    }

    @Override
    public UserEntity getUserById(Long id) {
        return getById(id);
    }

    @Override
    public List<UserEntity> listUsersByAge(Integer minAge) {
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();
        if (minAge != null) {
            wrapper.ge(UserEntity::getAge, minAge);
        }
        List<UserEntity> list = list(wrapper);
        return (list != null) ? list : Collections.emptyList();
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

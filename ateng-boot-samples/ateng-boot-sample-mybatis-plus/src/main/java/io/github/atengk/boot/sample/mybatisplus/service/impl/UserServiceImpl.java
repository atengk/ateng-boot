package io.github.atengk.boot.sample.mybatisplus.service.impl;

import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.sample.mybatisplus.entity.UserEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.UserMapper;
import io.github.atengk.boot.sample.mybatisplus.service.UserService;
import org.springframework.stereotype.Service;

/**
 * 用户业务服务实现
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Service
public class UserServiceImpl extends AtengServiceImpl<UserMapper, UserEntity> implements UserService {
}

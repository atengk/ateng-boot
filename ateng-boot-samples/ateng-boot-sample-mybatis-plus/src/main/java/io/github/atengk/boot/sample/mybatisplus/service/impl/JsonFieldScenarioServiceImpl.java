package io.github.atengk.boot.sample.mybatisplus.service.impl;

import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.sample.mybatisplus.entity.DemoJsonUserEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.DemoJsonUserMapper;
import io.github.atengk.boot.sample.mybatisplus.service.JsonFieldScenarioService;
import org.springframework.stereotype.Service;

/**
 * JSON 字段业务场景服务实现类
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Service
public class JsonFieldScenarioServiceImpl extends AtengServiceImpl<DemoJsonUserMapper, DemoJsonUserEntity> implements JsonFieldScenarioService {
}

package io.github.atengk.boot.sample.mybatisplus.service.impl;

import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.sample.mybatisplus.entity.SoftDeleteItemEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.SoftDeleteItemMapper;
import io.github.atengk.boot.sample.mybatisplus.service.SoftDeleteUniqueKeyScenarioService;
import org.springframework.stereotype.Service;

/**
 * 联合唯一键软删除业务场景服务实现类
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Service
public class SoftDeleteUniqueKeyScenarioServiceImpl extends AtengServiceImpl<SoftDeleteItemMapper, SoftDeleteItemEntity> implements SoftDeleteUniqueKeyScenarioService {
}

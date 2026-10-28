package io.github.atengk.boot.sample.mybatisplus.service;

import io.github.atengk.boot.mybatisplus.service.AtengBaseService;
import io.github.atengk.boot.sample.mybatisplus.entity.SoftDeleteItemEntity;

/**
 * 联合唯一键软删除与穿透恢复业务场景服务契约
 *
 * @author Ateng
 * @since 2026-10-27
 */
public interface SoftDeleteUniqueKeyScenarioService extends AtengBaseService<SoftDeleteItemEntity> {
}

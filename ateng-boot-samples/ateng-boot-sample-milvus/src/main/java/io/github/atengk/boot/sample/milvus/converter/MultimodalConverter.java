package io.github.atengk.boot.sample.milvus.converter;

import io.github.atengk.boot.milvus.model.MilvusHit;
import io.github.atengk.boot.sample.milvus.dto.MultimodalInsertDto;
import io.github.atengk.boot.sample.milvus.model.SampleMultimodalEntity;
import io.github.atengk.boot.sample.milvus.vo.MultimodalHitVo;
import io.github.atengk.boot.sample.milvus.vo.MultimodalVo;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 样例工程多模态多特征模型与传输对象转换器
 * 负责在 Controller 传输边界与持久层实体之间执行解耦映射 (Zero-Entity-Leakage)
 *
 * @author Ateng
 * @since 2026-11-04
 */
public final class MultimodalConverter {

    private MultimodalConverter() {
    }

    /**
     * 将插入请求 DTO 映射为 Milvus 多模态实体
     *
     * @param dto 插入请求 DTO
     * @return 实体对象
     */
    public static SampleMultimodalEntity toEntity(MultimodalInsertDto dto) {
        if (dto == null) {
            return null;
        }
        return SampleMultimodalEntity.builder()
                .id(dto.getId())
                .title(dto.getTitle())
                .summary(dto.getSummary())
                .denseVector(dto.getDenseVector())
                .sparseVector(dto.getSparseVector())
                .extra(dto.getExtra())
                .build();
    }

    /**
     * 将 Milvus 多模态实体转换为对外暴露的视图对象 (VO)
     *
     * @param entity 实体对象
     * @return 视图对象
     */
    public static MultimodalVo toVo(SampleMultimodalEntity entity) {
        if (entity == null) {
            return null;
        }
        return MultimodalVo.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .summary(entity.getSummary())
                .denseVector(entity.getDenseVector())
                .sparseVector(entity.getSparseVector())
                .extra(entity.getExtra())
                .build();
    }

    /**
     * 将检索命中结果包装为统一的 MultimodalHitVo
     *
     * @param hit 检索命中项
     * @return 命中项视图对象
     */
    public static MultimodalHitVo toHitVo(MilvusHit<SampleMultimodalEntity> hit) {
        if (hit == null) {
            return null;
        }
        return MultimodalHitVo.builder()
                .id(hit.getId())
                .score(hit.getScore())
                .document(toVo(hit.getEntity()))
                .build();
    }

    /**
     * 批量将多模态检索命中结果转换为视图对象列表
     *
     * @param hits 检索命中列表
     * @return 命中项视图列表
     */
    public static List<MultimodalHitVo> toHitVoList(List<MilvusHit<SampleMultimodalEntity>> hits) {
        if (hits == null || hits.isEmpty()) {
            return Collections.emptyList();
        }
        return hits.stream().map(MultimodalConverter::toHitVo).filter(Objects::nonNull).toList();
    }
}

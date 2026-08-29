package com.healix.common.domain;

import com.healix.common.util.SnowflakeId;

import java.time.LocalDateTime;

/**
 * 软删与公共字段填充约定。
 *
 * <p>业务主键 {@code id} 一律使用 {@link SnowflakeId#nextBizId()}（32 位编码）。
 */
public final class EntityMeta {

    /** 未删除时的 gmt_deleted 哨兵值 */
    public static final LocalDateTime NOT_DELETED = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    private EntityMeta() {
    }

    /**
     * 新建行：业务 id = {@link SnowflakeId#nextBizId()}，并填充软删与时间字段。
     * 若调用方已预先 setId，则保留不覆盖。
     */
    public static void onCreate(BaseEntity entity) {
        if (entity.getId() == null || entity.getId().isBlank()) {
            entity.setId(SnowflakeId.nextBizId());
        }
        LocalDateTime now = LocalDateTime.now();
        if (entity.getIsDeleted() == null) {
            entity.setIsDeleted(0);
        }
        if (entity.getGmtCreated() == null) {
            entity.setGmtCreated(now);
        }
        entity.setGmtModified(now);
        if (entity.getGmtDeleted() == null) {
            entity.setGmtDeleted(NOT_DELETED);
        }
    }

    /** 更新行：刷新修改时间 */
    public static void onUpdate(BaseEntity entity) {
        entity.setGmtModified(LocalDateTime.now());
    }

    /** 软删：is_deleted=1，gmt_deleted=当前时间 */
    public static void onSoftDelete(BaseEntity entity) {
        LocalDateTime now = LocalDateTime.now();
        entity.setIsDeleted(1);
        entity.setGmtModified(now);
        entity.setGmtDeleted(now);
    }
}

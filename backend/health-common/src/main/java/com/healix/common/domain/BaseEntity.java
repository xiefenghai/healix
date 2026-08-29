package com.healix.common.domain;

import java.time.LocalDateTime;

/**
 * 表公共字段：pk_id 物理自增；id 业务主键（32 位雪花编码）；软删与审计时间。
 */
public abstract class BaseEntity {

    /** 物理主键（自增，不对外关联） */
    private Long pkId;

    /** 业务主键（SnowflakeId.nextBizId，表间关联用此字段） */
    private String id;

    /** 0 未删除 / 1 已删除 */
    private Integer isDeleted;

    private LocalDateTime gmtCreated;
    private LocalDateTime gmtModified;
    private LocalDateTime gmtDeleted;

    public Long getPkId() {
        return pkId;
    }

    public void setPkId(Long pkId) {
        this.pkId = pkId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Integer getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Integer isDeleted) {
        this.isDeleted = isDeleted;
    }

    public LocalDateTime getGmtCreated() {
        return gmtCreated;
    }

    public void setGmtCreated(LocalDateTime gmtCreated) {
        this.gmtCreated = gmtCreated;
    }

    public LocalDateTime getGmtModified() {
        return gmtModified;
    }

    public void setGmtModified(LocalDateTime gmtModified) {
        this.gmtModified = gmtModified;
    }

    public LocalDateTime getGmtDeleted() {
        return gmtDeleted;
    }

    public void setGmtDeleted(LocalDateTime gmtDeleted) {
        this.gmtDeleted = gmtDeleted;
    }
}

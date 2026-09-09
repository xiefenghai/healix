package com.healix.core.people.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 患者合并的数据迁移语句。
 *
 * <p>SQL 写明表名而不做泛化：每张表的唯一键约束不同，能不能直接改归属要逐表判断，
 * 一个「万能迁移」方法只会把冲突藏起来变成运行时异常。
 */
@Mapper
public interface PatientMergeMapper {

    /** 无 people 维度唯一键的表：直接改归属。 */
    int repointAppendOnly(
            @Param("table") String table,
            @Param("sourcePeopleId") String sourcePeopleId,
            @Param("targetPeopleId") String targetPeopleId,
            @Param("gmtModified") LocalDateTime gmtModified);

    /** 目标已存在的证件类型；源侧同类型证件不迁移。 */
    List<String> listIdentityTypes(@Param("peopleId") String peopleId);

    int moveIdentity(
            @Param("sourcePeopleId") String sourcePeopleId,
            @Param("targetPeopleId") String targetPeopleId,
            @Param("skipTypes") List<String> skipTypes,
            @Param("gmtModified") LocalDateTime gmtModified);

    int softDeleteIdentityRest(
            @Param("sourcePeopleId") String sourcePeopleId, @Param("gmtModified") LocalDateTime gmtModified);

    /** 目标已入组的机构；源侧同机构入组记录不迁移。 */
    List<String> listMembershipOrgIds(@Param("peopleId") String peopleId);

    int moveMembership(
            @Param("sourcePeopleId") String sourcePeopleId,
            @Param("targetPeopleId") String targetPeopleId,
            @Param("skipOrgIds") List<String> skipOrgIds,
            @Param("gmtModified") LocalDateTime gmtModified);

    int softDeleteMembershipRest(
            @Param("sourcePeopleId") String sourcePeopleId, @Param("gmtModified") LocalDateTime gmtModified);

    /** 目标已在的健管组；源侧同组成员记录不迁移。 */
    List<String> listCareTeamIds(@Param("peopleId") String peopleId);

    int moveCareTeamMember(
            @Param("sourcePeopleId") String sourcePeopleId,
            @Param("targetPeopleId") String targetPeopleId,
            @Param("skipTeamIds") List<String> skipTeamIds,
            @Param("gmtModified") LocalDateTime gmtModified);

    int softDeleteCareTeamMemberRest(
            @Param("sourcePeopleId") String sourcePeopleId, @Param("gmtModified") LocalDateTime gmtModified);

    /** 一人一行的表（分派、基础档案、方案头）：目标有则弃源，目标无则迁移。 */
    boolean existsSingleton(@Param("table") String table, @Param("peopleId") String peopleId);

    int moveSingleton(
            @Param("table") String table,
            @Param("sourcePeopleId") String sourcePeopleId,
            @Param("targetPeopleId") String targetPeopleId,
            @Param("gmtModified") LocalDateTime gmtModified);

    int softDeleteSingleton(
            @Param("table") String table,
            @Param("peopleId") String peopleId,
            @Param("gmtModified") LocalDateTime gmtModified);

    /** 按业务码去重的表（疾病档案 disease_code、元数据 metadata_code）。 */
    List<String> listCodes(
            @Param("table") String table,
            @Param("codeColumn") String codeColumn,
            @Param("peopleId") String peopleId);

    int moveByCode(
            @Param("table") String table,
            @Param("codeColumn") String codeColumn,
            @Param("sourcePeopleId") String sourcePeopleId,
            @Param("targetPeopleId") String targetPeopleId,
            @Param("skipCodes") List<String> skipCodes,
            @Param("gmtModified") LocalDateTime gmtModified);

    int softDeleteByCodeRest(
            @Param("table") String table,
            @Param("sourcePeopleId") String sourcePeopleId,
            @Param("gmtModified") LocalDateTime gmtModified);

    /** 源侧未办结的工作台任务先作废，避免 OPEN 唯一键与目标撞车。 */
    int cancelOpenTasks(
            @Param("peopleId") String peopleId, @Param("gmtModified") LocalDateTime gmtModified);

    int markMerged(
            @Param("sourcePeopleId") String sourcePeopleId,
            @Param("targetPeopleId") String targetPeopleId,
            @Param("gmtModified") LocalDateTime gmtModified);
}

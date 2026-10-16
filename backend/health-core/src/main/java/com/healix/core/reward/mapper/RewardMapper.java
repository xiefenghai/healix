package com.healix.core.reward.mapper;

import com.healix.core.reward.domain.RewardBadge;
import com.healix.core.reward.domain.RewardDay;
import com.healix.core.reward.domain.RewardKudos;
import com.healix.core.reward.domain.RewardLedger;
import com.healix.core.reward.domain.RewardSummary;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RewardMapper {

    // ---------------------------------------------------------------- 流水

    int insertLedger(RewardLedger row);

    /** 某日指定类型的净额（每个 bizRef 一行，points=净额，seq=最大序号）。 */
    List<RewardLedger> listLedgerNet(
            @Param("peopleId") String peopleId,
            @Param("bizDate") LocalDate bizDate,
            @Param("types") Collection<String> types);

    List<RewardLedger> listLedger(
            @Param("peopleId") String peopleId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("limit") int limit);

    Integer sumPoints(
            @Param("peopleId") String peopleId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 区间内净额为正的引用个数（如全勤日天数）。 */
    int countPositiveRefs(
            @Param("peopleId") String peopleId,
            @Param("bizType") String bizType,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** 区间内有方案 / 服药 / 测量得豆的日期数。 */
    int countActiveDates(
            @Param("peopleId") String peopleId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 批量：{@code from} 起有方案 / 服药 / 测量得豆的 (peopleId, bizDate)。 */
    List<RewardLedger> listActiveLedgerDates(
            @Param("peopleIds") Collection<String> peopleIds, @Param("from") LocalDate from);

    // ---------------------------------------------------------------- 汇总

    RewardSummary findSummary(@Param("peopleId") String peopleId);

    List<RewardSummary> listSummaries(@Param("peopleIds") Collection<String> peopleIds);

    int insertSummary(RewardSummary row);

    int updateSummary(RewardSummary row);

    int updateDisabled(@Param("peopleId") String peopleId, @Param("disabled") int disabled);

    int updateLastPushDate(@Param("peopleId") String peopleId, @Param("day") LocalDate day);

    int updatePeerOptOut(@Param("peopleId") String peopleId, @Param("optOut") int optOut);

    Integer sumOrgWeekPoints(
            @Param("tenantId") String tenantId,
            @Param("orgId") String orgId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    int countOrgFullDays(
            @Param("tenantId") String tenantId,
            @Param("orgId") String orgId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    int countOrgActiveToday(
            @Param("tenantId") String tenantId, @Param("orgId") String orgId, @Param("day") LocalDate day);

    // ---------------------------------------------------------------- 日结

    int upsertDay(RewardDay row);

    List<RewardDay> listDays(
            @Param("peopleId") String peopleId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    // ---------------------------------------------------------------- 徽章

    /** 已有同编码徽章时不变，返回 0。 */
    int insertBadge(RewardBadge row);

    List<RewardBadge> listBadges(@Param("peopleId") String peopleId);

    // ---------------------------------------------------------------- 点赞

    /** 同员工同患者同日重复点赞只更新鼓励语。 */
    int upsertKudos(RewardKudos row);

    List<RewardKudos> listKudos(@Param("peopleId") String peopleId, @Param("limit") int limit);
}

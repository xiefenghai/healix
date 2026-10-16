package com.healix.core.reward.mapper;

import com.healix.core.reward.dto.RewardActivityRow;
import com.healix.core.reward.dto.RewardSourceEvent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 激励计分只读源：方案打卡、用药、体征、饮食、运动（仅患者本人录入）。 */
@Mapper
public interface RewardSourceMapper {

    List<RewardSourceEvent> listDayEvents(
            @Param("tenantId") String tenantId, @Param("peopleId") String peopleId, @Param("day") LocalDate day);

    /** 当日本人或系统完成（非代录）的方案打卡数，仅统计 {@code before} 前录入的。 */
    int countPlanDoneNotStaff(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("day") LocalDate day,
            @Param("before") LocalDateTime before);

    /** 本人有方案打卡 / 服药 / 测量的日期（初始化火苗用，不套 24h 规则）。 */
    List<LocalDate> listActiveDays(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    int countSelfMeasurements(@Param("tenantId") String tenantId, @Param("peopleId") String peopleId);

    int countPhotoMeals(@Param("tenantId") String tenantId, @Param("peopleId") String peopleId);

    int countSelfMeasurementsBetween(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    int countPhotoMealsBetween(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** 同机构入组日 ±30 天、未关闭激励且未退出百分位的同期患者。 */
    List<String> listPeerPeopleIds(
            @Param("tenantId") String tenantId,
            @Param("orgId") String orgId,
            @Param("joinFrom") LocalDate joinFrom,
            @Param("joinTo") LocalDate joinTo,
            @Param("excludePeopleId") String excludePeopleId);

    /** 批量：{@code from} 起每人每天的打卡动作数（患者列表用，不套 24h 规则）。 */
    List<RewardActivityRow> listRecentActivity(
            @Param("tenantId") String tenantId,
            @Param("peopleIds") Collection<String> peopleIds,
            @Param("from") LocalDate from);

    /** 业务日期 ≥ {@code from} 的本人补录条数（隔天录入；体征晚于测量 12h 以上，按次合并）。 */
    int countBackfills(
            @Param("tenantId") String tenantId, @Param("peopleId") String peopleId, @Param("from") LocalDate from);

    /** 已绑定 C 账号的患者（tenantId, peopleId）。 */
    List<Map<String, String>> listBoundPeople();
}

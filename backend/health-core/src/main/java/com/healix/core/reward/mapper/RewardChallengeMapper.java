package com.healix.core.reward.mapper;

import com.healix.core.reward.domain.RewardChallenge;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RewardChallengeMapper {

    int insert(RewardChallenge row);

    int updateProgress(RewardChallenge row);

    int updateSettled(RewardChallenge row);

    RewardChallenge findActive(
            @Param("peopleId") String peopleId, @Param("periodStart") LocalDate periodStart);

    List<RewardChallenge> listByPeriodStart(@Param("periodStart") LocalDate periodStart);

    RewardChallenge findById(@Param("id") String id);
}

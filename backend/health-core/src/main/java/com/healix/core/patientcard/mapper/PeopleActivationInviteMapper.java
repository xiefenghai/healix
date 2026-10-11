package com.healix.core.patientcard.mapper;

import com.healix.core.patientcard.domain.PeopleActivationInvite;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeopleActivationInviteMapper {

    PeopleActivationInvite findById(@Param("id") String id);

    PeopleActivationInvite findByCode(@Param("code") String code);

    List<PeopleActivationInvite> listByPeople(@Param("peopleId") String peopleId);

    int insert(PeopleActivationInvite invite);

    int markUsed(PeopleActivationInvite invite);

    int disableUnusedByPeople(@Param("peopleId") String peopleId);

    int softDelete(@Param("id") String id, @Param("gmtDeleted") java.time.LocalDateTime gmtDeleted);
}

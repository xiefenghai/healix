package com.healix.core.patientcard.mapper;

import com.healix.core.patientcard.domain.AccountPatient;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AccountPatientMapper {

    AccountPatient findById(@Param("id") String id);

    List<AccountPatient> listByAccount(@Param("accountId") String accountId);

    List<AccountPatient> listByPeople(@Param("peopleId") String peopleId);

    /** 批量：返回有 C 端关联的 peopleId（去重）。 */
    List<String> listLinkedPeopleIds(@Param("peopleIds") List<String> peopleIds);

    int countByAccount(@Param("accountId") String accountId);

    int countByPeople(@Param("peopleId") String peopleId);

    AccountPatient findByAccountAndPeople(
            @Param("accountId") String accountId, @Param("peopleId") String peopleId);

    AccountPatient findByAccountAndIdentityHash(
            @Param("accountId") String accountId,
            @Param("identityType") String identityType,
            @Param("identityValueHash") String identityValueHash);

    int insert(AccountPatient card);

    int softDelete(@Param("id") String id, @Param("gmtDeleted") java.time.LocalDateTime gmtDeleted);
}

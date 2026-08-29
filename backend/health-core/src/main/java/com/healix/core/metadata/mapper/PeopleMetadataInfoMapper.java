package com.healix.core.metadata.mapper;

import com.healix.core.metadata.domain.PeopleMetadataInfo;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeopleMetadataInfoMapper {

    PeopleMetadataInfo findByPeopleAndCode(
            @Param("peopleId") String peopleId, @Param("metadataCode") String metadataCode);

    List<String> listCodesByPrefix(
            @Param("peopleId") String peopleId,
            @Param("tenantId") String tenantId,
            @Param("codePrefix") String codePrefix);

    int insert(PeopleMetadataInfo row);

    int updateValue(PeopleMetadataInfo row);

    int softDeleteById(@Param("id") String id);
}

package com.healix.core.dict.service;

import com.healix.core.dict.domain.SysDict;
import com.healix.core.dict.dto.DictItemDto;
import com.healix.core.dict.enums.DictTypeEnum;
import com.healix.core.dict.mapper.SysDictMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DictService {

    public static final String PLATFORM_TENANT = "0";

    private final SysDictMapper sysDictMapper;

    public List<DictItemDto> listMerged(String tenantId, String dictType, String parentCode) {
        String parent = StringUtils.hasText(parentCode) ? parentCode : "0";
        Map<String, DictItemDto> merged = new LinkedHashMap<>();
        for (SysDict row : sysDictMapper.listByTypeAndParent(PLATFORM_TENANT, dictType, parent)) {
            merged.put(row.getDictCode(), toDto(row));
        }
        if (!PLATFORM_TENANT.equals(tenantId)) {
            for (SysDict row : sysDictMapper.listByTypeAndParent(tenantId, dictType, parent)) {
                merged.put(row.getDictCode(), toDto(row));
            }
        }
        return new ArrayList<>(merged.values());
    }

    public List<DictItemDto> listFieldsForBasicArchive(String tenantId) {
        return listMerged(tenantId, DictTypeEnum.FIELD.name(), "basic_archive");
    }

    public List<DictItemDto> listFieldsForDisease(String tenantId, String diseaseCode) {
        return listMerged(tenantId, DictTypeEnum.DISEASE_FIELD.name(), diseaseCode);
    }


    private static DictItemDto toDto(SysDict row) {
        DictItemDto dto = new DictItemDto();
        dto.setDictCode(row.getDictCode());
        dto.setDictCodeDesc(row.getDictCodeDesc());
        dto.setContent(row.getContent());
        dto.setSortOrder(row.getSortOrder());
        return dto;
    }
}

package com.healix.core.observation.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.healix.core.dict.dto.DictItemDto;
import com.healix.core.dict.enums.DictTypeEnum;
import com.healix.core.dict.service.DictService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LabItemCodeMapperFuzzyMatchTest {

    @Mock
    private DictService dictService;

    private LabItemCodeMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new LabItemCodeMapper(dictService);
        DictItemDto hgb = new DictItemDto();
        hgb.setDictCode("HGB");
        hgb.setDictCodeDesc("血红蛋白");
        DictItemDto hba1c = new DictItemDto();
        hba1c.setDictCode("HBA1C");
        hba1c.setDictCodeDesc("糖化血红蛋白");
        // 故意让较短名称排在前面，验证最长匹配
        when(dictService.listMerged(DictService.PLATFORM_TENANT, DictTypeEnum.OPTION.name(), "labItemCode"))
                .thenReturn(List.of(hgb, hba1c));
    }

    @Test
    void mapsGlycatedHemoglobinAliasToHba1c() {
        assertEquals("HBA1C", mapper.map("糖化血红蛋白").orElseThrow());
    }

    @Test
    void fuzzyPrefersLongerNameOverHemoglobinSubstring() {
        // 不走别名表，验证「…血红蛋白」不会误命中 HGB
        assertEquals("HBA1C", mapper.map("糖化血红蛋白测定").orElseThrow());
    }

    @Test
    void mapsHemoglobinToHgb() {
        assertEquals("HGB", mapper.map("血红蛋白").orElseThrow());
    }
}

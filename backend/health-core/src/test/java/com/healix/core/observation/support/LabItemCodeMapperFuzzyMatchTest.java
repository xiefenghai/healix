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

    @Test
    void mapsCbcCommonNames() {
        stubCbcCatalog();
        assertEquals("WBC", mapper.map("白细胞计数").orElseThrow());
        assertEquals("RBC", mapper.map("红细胞").orElseThrow());
        assertEquals("HGB", mapper.map("血红蛋白(g/L)").orElseThrow());
        assertEquals("PLT", mapper.map("血小板数目").orElseThrow());
        assertEquals("NEUT_PCT", mapper.map("中性粒细胞百分比").orElseThrow());
        assertEquals("NEUT", mapper.map("中性粒细胞计数").orElseThrow());
        assertEquals("LYMPH_PCT", mapper.map("淋巴细胞%").orElseThrow());
        assertEquals("MCV", mapper.map("平均红细胞体积").orElseThrow());
        assertEquals("PCT", mapper.map("血小板压积").orElseThrow());
    }

    private void stubCbcCatalog() {
        when(dictService.listMerged(DictService.PLATFORM_TENANT, DictTypeEnum.OPTION.name(), "labItemCode"))
                .thenReturn(List.of(
                        dict("WBC", "白细胞计数"),
                        dict("RBC", "红细胞计数"),
                        dict("HGB", "血红蛋白"),
                        dict("PLT", "血小板计数"),
                        dict("NEUT", "中性粒细胞计数"),
                        dict("NEUT_PCT", "中性粒细胞百分比"),
                        dict("LYMPH", "淋巴细胞计数"),
                        dict("LYMPH_PCT", "淋巴细胞百分比"),
                        dict("MCV", "平均红细胞体积"),
                        dict("PCT", "血小板压积"),
                        dict("HBA1C", "糖化血红蛋白")));
    }

    private static DictItemDto dict(String code, String desc) {
        DictItemDto d = new DictItemDto();
        d.setDictCode(code);
        d.setDictCodeDesc(desc);
        return d;
    }
}

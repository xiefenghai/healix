package com.healix.core.field;

import com.healix.core.dict.dto.DictItemDto;
import com.healix.core.dict.enums.DictTypeEnum;
import com.healix.core.dict.service.DictService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 字段选项：以字典为准（与档案页同一份），字典没有时用目录兜底。 */
@Service
@RequiredArgsConstructor
public class FieldOptionService {

    private final FieldCatalog catalog;
    private final DictService dictService;

    public List<FieldOption> options(String tenantId, FieldDef def) {
        if (!def.choice()) {
            return List.of();
        }
        List<DictItemDto> rows = dictService.listMerged(tenantId, DictTypeEnum.OPTION.name(), def.options());
        if (!rows.isEmpty()) {
            return rows.stream().map(r -> new FieldOption(r.getDictCode(), r.getDictCodeDesc())).toList();
        }
        return catalog.catalogOptions(def.options());
    }
}

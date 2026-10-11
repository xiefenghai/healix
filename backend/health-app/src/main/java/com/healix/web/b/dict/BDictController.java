package com.healix.web.b.dict;

import com.healix.common.result.ApiResult;
import com.healix.core.dict.dto.DictItemDto;
import com.healix.core.dict.service.DictService;
import com.healix.security.SecurityUtils;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端字典接口。
 * <p>拉取平台/租户合并后的选项与字段定义，驱动档案表单渲染（OPTION / DISEASE / DISEASE_FIELD 等）。
 */
@Validated
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BDictController {

    private final DictService dictService;

    /**
     * 按字典类型查询条目列表。
     *
     * @param dictType   字典类型，如 OPTION、DISEASE、DISEASE_FIELD、FIELD
     * @param parentCode 父编码；如 OPTION 下的 diabetesType、DISEASE 下的 0
     */
    @GetMapping("/dict")
    public ApiResult<List<DictItemDto>> listDict(
            @RequestParam String dictType, @RequestParam(required = false) String parentCode) {
        String tenantId = SecurityUtils.requireTenantId();
        return ApiResult.ok(dictService.listMerged(tenantId, dictType, parentCode));
    }
}

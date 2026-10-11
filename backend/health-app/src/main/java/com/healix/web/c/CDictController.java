package com.healix.web.c;

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

/** C 端只读字典（选项标签回显，与 B 端同源）。 */
@Validated
@RestController
@RequestMapping("/api/c/v1")
@RequiredArgsConstructor
public class CDictController {

    private final DictService dictService;

    @GetMapping("/dict")
    public ApiResult<List<DictItemDto>> listDict(
            @RequestParam String dictType, @RequestParam(required = false) String parentCode) {
        String tenantId = SecurityUtils.requireTenantId();
        return ApiResult.ok(dictService.listMerged(tenantId, dictType, parentCode));
    }
}

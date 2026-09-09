package com.healix.web.b.medication;

import com.healix.common.result.ApiResult;
import com.healix.core.medication.dto.DrugCatalogItemDto;
import com.healix.core.medication.service.DrugCatalogService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** B 端药品库检索（平台小库，供用药录入 autocomplete）。 */
@Validated
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BDrugCatalogController {

    private final DrugCatalogService drugCatalogService;

    @GetMapping("/drug-catalog")
    public ApiResult<List<DrugCatalogItemDto>> search(
            @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(drugCatalogService.search(keyword, limit));
    }
}

package com.healix.web.b.careplan;

import com.healix.common.result.ApiResult;
import com.healix.core.careplan.guideline.GuidelineLibrary;
import com.healix.core.careplan.guideline.GuidelineLibraryView;
import com.healix.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 指南推荐条目库（只读）：依据来源页与方案依据标签。 */
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BGuidelineController {

    private final GuidelineLibrary guidelineLibrary;

    @GetMapping("/guidelines")
    public ApiResult<GuidelineLibraryView> library() {
        SecurityUtils.requireTenantId();
        return ApiResult.ok(GuidelineLibraryView.of(guidelineLibrary));
    }
}

package com.healix.web.c;

import com.healix.core.storage.ImageFileService;
import com.healix.security.SecurityUtils;
import com.healix.web.b.storage.BPatientImageController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/c/v1/me/images")
public class CImageController {

    private final ImageFileService imageFileService;

    public CImageController(ImageFileService imageFileService) {
        this.imageFileService = imageFileService;
    }

    @GetMapping
    public ResponseEntity<byte[]> get(@RequestParam String key) {
        return BPatientImageController.image(
                imageFileService.open(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), key));
    }
}

package com.healix.web.b.storage;

import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.storage.ImageFileService;
import com.healix.core.storage.ImageFileService.StoredFile;
import com.healix.security.SecurityUtils;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 按对象键读取患者图片。键本身不是公开地址。 */
@RestController
@RequestMapping("/api/b/v1/patients/{peopleId}/images")
public class BPatientImageController {

    private final ImageFileService imageFileService;
    private final ArchiveAccessService archiveAccessService;

    public BPatientImageController(ImageFileService imageFileService, ArchiveAccessService archiveAccessService) {
        this.imageFileService = imageFileService;
        this.archiveAccessService = archiveAccessService;
    }

    @GetMapping
    public ResponseEntity<byte[]> get(@PathVariable String peopleId, @RequestParam String key) {
        archiveAccessService.assertStaffCanAccessPeople(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), peopleId);
        StoredFile file = imageFileService.open(SecurityUtils.requireTenantId(), peopleId, key);
        return image(file);
    }

    public static ResponseEntity<byte[]> image(StoredFile file) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(file.body());
    }
}

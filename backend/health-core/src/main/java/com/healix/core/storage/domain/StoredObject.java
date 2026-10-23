package com.healix.core.storage.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 对象键归属。文件本体在对象存储，这里只记谁可以读。 */
@Getter
@Setter
public class StoredObject extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private String objectKey;
    private String contentType;
    private String category;
}

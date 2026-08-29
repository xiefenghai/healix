package com.healix.core.metadata.support;

import com.healix.core.metadata.enums.MetaDataCodeEnum;
import lombok.extern.slf4j.Slf4j;

/**
 * 解析 {@code people_metadata_info.metadata_code}：优先字典 content，否则按命名约定 fallback。
 */
@Slf4j
public final class MetadataCodeResolver {

    private MetadataCodeResolver() {}

    /**
     * @param schemaMetadataCode {@code sys_dict.content.metadataCode}，可空
     * @param codePrefix         如 {@code basic.} 或 {@code disease.diabetes.}
     * @param dictFieldCode      字典 {@code dict_code}
     */
    public static String resolve(String schemaMetadataCode, String codePrefix, String dictFieldCode) {
        if (schemaMetadataCode != null && !schemaMetadataCode.isBlank()) {
            warnIfOverridesPlatformDefault(schemaMetadataCode, codePrefix, dictFieldCode);
            return schemaMetadataCode;
        }
        return codePrefix + dictFieldCode;
    }

    /** COMPOSITE 子字段路径 */
    public static String compositeSub(String parentMetadataCode, String subCode) {
        return parentMetadataCode + "." + subCode;
    }

    private static void warnIfOverridesPlatformDefault(
            String schemaMetadataCode, String codePrefix, String dictFieldCode) {
        String convention = codePrefix + dictFieldCode;
        if (convention.equals(schemaMetadataCode)) {
            return;
        }
        if (MetaDataCodeEnum.findByCode(convention).isPresent()) {
            log.debug(
                    "Dict metadataCode override: field={} platform={} dict={}",
                    dictFieldCode,
                    convention,
                    schemaMetadataCode);
        }
    }
}

package com.healix.core.dict.support;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.metadata.enums.MetaDataCodeEnum;

/** 构造 {@code sys_dict FIELD/DISEASE_FIELD} 的 content JSON（种子与测试用）。 */
public final class DictFieldSchemas {

    private DictFieldSchemas() {}

    public static String textField(MetaDataCodeEnum metadataCode, String label) {
        ObjectNode root = JsonUtils.emptyObject();
        root.put("metadataCode", metadataCode.getCode());
        root.put("label", label);
        root.put("widget", "TEXT");
        return JsonUtils.toJson(root);
    }

    public static String numberField(MetaDataCodeEnum metadataCode, String label) {
        ObjectNode root = JsonUtils.emptyObject();
        root.put("metadataCode", metadataCode.getCode());
        root.put("label", label);
        root.put("widget", "NUMBER");
        return JsonUtils.toJson(root);
    }

    public static String selectField(MetaDataCodeEnum metadataCode, String label, String optionParentCode) {
        ObjectNode root = JsonUtils.emptyObject();
        root.put("metadataCode", metadataCode.getCode());
        root.put("label", label);
        root.put("widget", "SINGLE_SELECT");
        root.set("optionRef", optionRef(optionParentCode));
        return JsonUtils.toJson(root);
    }

    public static String multiSelectField(MetaDataCodeEnum metadataCode, String label, String optionParentCode) {
        ObjectNode root = JsonUtils.emptyObject();
        root.put("metadataCode", metadataCode.getCode());
        root.put("label", label);
        root.put("widget", "MULTI_SELECT");
        root.set("optionRef", optionRef(optionParentCode));
        return JsonUtils.toJson(root);
    }

    public static String compositeField(MetaDataCodeEnum metadataCode, String label, ArrayNode subFields) {
        ObjectNode root = JsonUtils.emptyObject();
        root.put("metadataCode", metadataCode.getCode());
        root.put("label", label);
        root.put("widget", "COMPOSITE");
        root.set("fields", subFields);
        return JsonUtils.toJson(root);
    }

    public static ObjectNode subTextField(String code, String label) {
        ObjectNode sub = JsonUtils.emptyObject();
        sub.put("code", code);
        sub.put("label", label);
        sub.put("widget", "TEXT");
        return sub;
    }

    public static ObjectNode subNumberField(String code, String label) {
        ObjectNode sub = JsonUtils.emptyObject();
        sub.put("code", code);
        sub.put("label", label);
        sub.put("widget", "NUMBER");
        return sub;
    }

    public static ObjectNode subSelectField(String code, String label, String optionParentCode) {
        ObjectNode sub = JsonUtils.emptyObject();
        sub.put("code", code);
        sub.put("label", label);
        sub.put("widget", "SINGLE_SELECT");
        sub.set("optionRef", optionRef(optionParentCode));
        return sub;
    }

    public static ArrayNode fields(ObjectNode... items) {
        ArrayNode arr = JsonUtils.mapper().createArrayNode();
        for (ObjectNode item : items) {
            arr.add(item);
        }
        return arr;
    }

    private static ObjectNode optionRef(String parentCode) {
        ObjectNode ref = JsonUtils.emptyObject();
        ref.put("dictType", "OPTION");
        ref.put("parentCode", parentCode);
        return ref;
    }
}

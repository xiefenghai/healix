package com.healix.core.storage;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.core.storage.domain.StoredObject;
import com.healix.core.storage.mapper.StoredObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 保存图片并按租户、患者校验读取。业务表只保存返回的对象键。 */
@Service
public class ImageFileService {

    private static final int MAX_BYTES = 5 * 1024 * 1024;

    private final ObjectStorage storage;
    private final StoredObjectMapper storedObjectMapper;

    public ImageFileService(ObjectStorage storage, StoredObjectMapper storedObjectMapper) {
        this.storage = storage;
        this.storedObjectMapper = storedObjectMapper;
    }

    public record StoredFile(byte[] body, String contentType) {}

    public String save(String tenantId, String peopleId, String category, byte[] bytes, String ignoredContentType) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(peopleId)) {
            throw new BusinessException("无法保存图片");
        }
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException("请上传图片");
        }
        if (bytes.length > MAX_BYTES) {
            throw new BusinessException("图片大小不能超过 5MB");
        }
        String extension = sniffExtension(bytes);
        String key = ObjectKeys.newKey(category, extension);
        String contentType = contentTypeOf(extension);
        storage.put(key, bytes, contentType);

        StoredObject row = new StoredObject();
        row.setTenantId(tenantId);
        row.setPeopleId(peopleId);
        row.setObjectKey(key);
        row.setContentType(contentType);
        row.setCategory(ObjectKeys.categoryOf(key));
        EntityMeta.onCreate(row);
        storedObjectMapper.insert(row);
        return key;
    }

    public StoredFile open(String tenantId, String peopleId, String key) {
        String safe = requireOwned(tenantId, peopleId, null, key);
        StoredObject row = storedObjectMapper.findByKey(safe);
        return new StoredFile(storage.get(safe), row.getContentType());
    }

    /** 把已保存的键写进业务记录前核对归属和分类。空键表示这次不改图片。 */
    public String requireOwned(String tenantId, String peopleId, String category, String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        String safe = ObjectKeys.require(key.trim());
        if (category != null && !category.equals(ObjectKeys.categoryOf(safe))) {
            throw new BusinessException("图片不存在");
        }
        StoredObject row = storedObjectMapper.findByKey(safe);
        if (row == null || !tenantId.equals(row.getTenantId()) || !peopleId.equals(row.getPeopleId())) {
            throw new BusinessException("图片不存在");
        }
        return safe;
    }

    private static String sniffExtension(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47) {
            return "png";
        }
        if (bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P') {
            return "webp";
        }
        throw new BusinessException("请上传 JPG、PNG 或 WEBP 图片");
    }

    private static String contentTypeOf(String extension) {
        return switch (extension) {
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }
}

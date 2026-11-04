package com.healix.core.storage;

import com.healix.common.exception.BusinessException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 第一版对象存储：写到本机目录。键的规则与 COS 相同，切换时只换这个实现。 */
@Component
public class LocalObjectStorage implements ObjectStorage {

    private final Path root;

    public LocalObjectStorage(@Value("${healix.storage.local.dir:./data/objects}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] body, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, body);
        } catch (IOException e) {
            throw new BusinessException("保存图片失败");
        }
    }

    @Override
    public byte[] get(String key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target)) {
            throw new BusinessException("图片不存在");
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new BusinessException("读取图片失败");
        }
    }

    private Path resolve(String key) {
        String safe = ObjectKeys.require(key);
        Path target = root.resolve(safe).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("图片不存在");
        }
        return target;
    }
}

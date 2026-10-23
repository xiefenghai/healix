package com.healix.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.healix.common.exception.BusinessException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalObjectStorageTest {

    @TempDir
    Path dir;

    @Test
    void roundTripKeepsBytes() {
        LocalObjectStorage storage = new LocalObjectStorage(dir.toString());
        String key = ObjectKeys.newKey(ObjectKeys.LAB, "jpg");
        byte[] body = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2};
        storage.put(key, body, "image/jpeg");
        assertArrayEquals(body, storage.get(key));
    }

    @Test
    void rejectsPathOutsideRoot() {
        LocalObjectStorage storage = new LocalObjectStorage(dir.toString());
        assertThrows(BusinessException.class, () -> storage.put("../secret.jpg", new byte[] {1}, "image/jpeg"));
        assertThrows(BusinessException.class, () -> storage.get("lab/2026/10/not-a-key.jpg"));
    }

    @Test
    void missingFileIsNotFound() throws Exception {
        LocalObjectStorage storage = new LocalObjectStorage(dir.toString());
        String key = ObjectKeys.newKey(ObjectKeys.DIET, "png");
        Path target = dir.resolve(key);
        Files.createDirectories(target.getParent());
        assertThrows(BusinessException.class, () -> storage.get(key));
    }
}

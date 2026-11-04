package com.healix.core.storage;

/** 对象存储。业务只持有对象键，磁盘与后续 COS 都实现这一层。 */
public interface ObjectStorage {

    void put(String key, byte[] body, String contentType);

    byte[] get(String key);
}

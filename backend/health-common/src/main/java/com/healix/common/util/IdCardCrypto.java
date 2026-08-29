package com.healix.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/** 身份证哈希与 AES-GCM 加解密（密钥由配置注入） */
public final class IdCardCrypto {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LEN = 12;

    private final byte[] aesKey;
    private final String pepper;

    public IdCardCrypto(String aesKeyMaterial, String pepper) {
        this.aesKey = normalizeKey(aesKeyMaterial);
        this.pepper = pepper == null ? "" : pepper;
    }

    public String hash(String tenantId, String normalizedId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String raw = pepper + "|" + tenantId + "|" + normalizedId;
            byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new IllegalStateException("id card hash failed", e);
        }
    }

    public String encrypt(String normalizedId) {
        try {
            byte[] iv = new byte[IV_LEN];
            // 用内容派生确定性 IV，便于同号密文稳定（租户内唯一检索靠 hash）
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest((pepper + normalizedId).getBytes(StandardCharsets.UTF_8));
            System.arraycopy(dig, 0, iv, 0, IV_LEN);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(normalizedId.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            throw new IllegalStateException("id card encrypt failed", e);
        }
    }

    private static byte[] normalizeKey(String material) {
        String m = material == null ? "healix-dev-idcard-aes-key!!" : material;
        byte[] raw = m.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[16];
        for (int i = 0; i < key.length; i++) {
            key[i] = i < raw.length ? raw[i] : (byte) i;
        }
        return key;
    }
}

package com.healix.core.notify.webpush;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Web Push 协议的纯 JDK 实现：RFC 8291 消息加密（aes128gcm）+ RFC 8292 VAPID（ES256 JWT）。
 *
 * <p>密钥均为 P-256；公钥用 65 字节未压缩点、私钥用 32 字节标量，统一 base64url 无填充编码，
 * 与浏览器 {@code PushSubscription.toJSON()} 及 {@code applicationServerKey} 格式一致。
 */
public final class WebPushCrypto {

    /** 单条记录上限；负载远小于此值，整条消息只有一条记录。 */
    private static final int RECORD_SIZE = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ECParameterSpec P256 = p256Params();

    private WebPushCrypto() {}

    public record VapidKeys(String publicKey, String privateKey) {}

    public static VapidKeys generateVapidKeys() {
        KeyPair kp = newKeyPair();
        return new VapidKeys(
                b64(encodePublic((ECPublicKey) kp.getPublic())),
                b64(unsignedBytes(((ECPrivateKey) kp.getPrivate()).getS(), 32)));
    }

    /**
     * 加密推送负载，返回可直接作为 HTTP body 的 aes128gcm 内容。
     *
     * @param p256dh 订阅公钥（base64url）
     * @param auth   订阅鉴权密钥（base64url，16 字节）
     */
    public static byte[] encrypt(byte[] plaintext, String p256dh, String auth) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, decode(p256dh), decode(auth), newKeyPair(), salt);
    }

    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair asKeys, byte[] salt) {
        try {
            byte[] asPublic = encodePublic((ECPublicKey) asKeys.getPublic());
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(asKeys.getPrivate());
            ka.doPhase(decodePublic(uaPublic), true);
            byte[] ecdhSecret = ka.generateSecret();

            byte[] keyInfo = concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[] {0}, uaPublic, asPublic);
            byte[] ikm = hkdf(authSecret, ecdhSecret, keyInfo, 32);
            byte[] cek = hkdf(salt, ikm, info("Content-Encoding: aes128gcm"), 16);
            byte[] nonce = hkdf(salt, ikm, info("Content-Encoding: nonce"), 12);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[] {2}));

            ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
            header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
            return concat(header.array(), ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Web Push 加密失败", e);
        }
    }

    /** VAPID Authorization 头：{@code vapid t=<jwt>, k=<publicKey>}。 */
    public static String vapidAuthorization(String endpoint, String subject, VapidKeys keys, long expEpochSeconds) {
        URI uri = URI.create(endpoint);
        String aud = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String header = b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64(("{\"aud\":\"" + aud + "\",\"exp\":" + expEpochSeconds + ",\"sub\":\"" + subject + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        try {
            Signature sig = Signature.getInstance("SHA256withECDSAinP1363Format");
            sig.initSign(decodePrivate(decode(keys.privateKey())));
            sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return "vapid t=" + signingInput + "." + b64(sig.sign()) + ", k=" + keys.publicKey();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("VAPID 签名失败", e);
        }
    }

    static KeyPair newKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] encodePublic(ECPublicKey key) {
        ECPoint w = key.getW();
        return concat(new byte[] {4}, unsignedBytes(w.getAffineX(), 32), unsignedBytes(w.getAffineY(), 32));
    }

    static ECPublicKey decodePublic(byte[] raw) throws GeneralSecurityException {
        if (raw.length != 65 || raw[0] != 4) {
            throw new GeneralSecurityException("无效的 P-256 公钥");
        }
        ECPoint point = new ECPoint(
                new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)), new BigInteger(1, Arrays.copyOfRange(raw, 33, 65)));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, P256));
    }

    static ECPrivateKey decodePrivate(byte[] raw) throws GeneralSecurityException {
        return (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, raw), P256));
    }

    /** HKDF（RFC 5869），输出不超过一个 SHA-256 块。 */
    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        byte[] prk = hmac(salt, ikm);
        return Arrays.copyOf(hmac(prk, concat(info, new byte[] {1})), length);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] info(String label) {
        return concat(label.getBytes(StandardCharsets.US_ASCII), new byte[] {0});
    }

    private static byte[] unsignedBytes(BigInteger v, int len) {
        byte[] b = v.toByteArray();
        if (b.length == len) {
            return b;
        }
        byte[] out = new byte[len];
        int copy = Math.min(b.length, len);
        System.arraycopy(b, b.length - copy, out, len - copy, copy);
        return out;
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] decode(String b64) {
        return Base64.getUrlDecoder().decode(b64.trim().replace('+', '-').replace('/', '_').replace("=", ""));
    }

    private static ECParameterSpec p256Params() {
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            return params.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}

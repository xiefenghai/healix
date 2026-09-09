package com.healix.common.util;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP（RFC 6238，HMAC-SHA1 / 6 位 / 30 秒），与 Google Authenticator、微软 Authenticator 等通用。
 *
 * <p>自己实现而不引三方库：算法只有几十行且规范固定，省掉一个需要跟随安全公告升级的依赖。
 */
public final class TotpSupport {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int SECRET_BYTES = 20;
    private static final int DIGITS = 6;
    private static final long STEP_SECONDS = 30;
    /** 允许的前后时间窗，抵消手机与服务器时钟偏差 */
    private static final int WINDOW = 1;

    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpSupport() {
    }

    /** 生成 Base32 密钥，供二维码与手工录入。 */
    public static String generateSecret() {
        byte[] buf = new byte[SECRET_BYTES];
        RANDOM.nextBytes(buf);
        return base32Encode(buf);
    }

    /** otpauth 链接，前端可直接转二维码。 */
    public static String otpAuthUri(String issuer, String accountName, String secret) {
        String label = urlEncode(issuer) + ":" + urlEncode(accountName);
        return "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    public static boolean verify(String secret, String code) {
        if (secret == null || code == null) {
            return false;
        }
        String digits = code.trim().replace(" ", "");
        if (digits.length() != DIGITS || !digits.chars().allMatch(Character::isDigit)) {
            return false;
        }
        byte[] key;
        try {
            key = base32Decode(secret);
        } catch (IllegalArgumentException e) {
            return false;
        }
        long counter = System.currentTimeMillis() / 1000 / STEP_SECONDS;
        for (int offset = -WINDOW; offset <= WINDOW; offset++) {
            if (digits.equals(code(key, counter + offset))) {
                return true;
            }
        }
        return false;
    }

    private static String code(byte[] key, long counter) {
        byte[] data = ByteBuffer.allocate(8).putLong(counter).array();
        byte[] hash;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            hash = mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP 计算失败", e);
        }
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        int otp = binary % 1_000_000;
        return String.format("%06d", otp);
    }

    private static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    private static byte[] base32Decode(String secret) {
        String clean = secret.trim().replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("空密钥");
        }
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8 + 1);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int idx = BASE32.indexOf(c);
            if (idx < 0) {
                throw new IllegalArgumentException("非法 Base32 字符: " + c);
            }
            buffer = (buffer << 5) | idx;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xFF));
                bits -= 8;
            }
        }
        byte[] key = new byte[out.position()];
        out.rewind();
        out.get(key);
        return key;
    }

    private static String urlEncode(String text) {
        return java.net.URLEncoder.encode(text == null ? "" : text, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}

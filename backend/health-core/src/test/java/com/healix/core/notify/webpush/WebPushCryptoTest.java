package com.healix.core.notify.webpush;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WebPushCryptoTest {

    /** RFC 8291 Appendix A。 */
    @Test
    void matchesRfc8291TestVector() throws Exception {
        byte[] plaintext = "When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8);
        ECPrivateKey asPrivate = WebPushCrypto.decodePrivate(d("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"));
        ECPublicKey asPublic = WebPushCrypto.decodePublic(
                d("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"));
        byte[] uaPublic =
                d("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
        byte[] auth = d("BTBZMqHH6r4Tts7J_aSIgg");
        byte[] salt = d("DGv6ra1nlYgDCS1FRnbzlw");

        byte[] body = WebPushCrypto.encrypt(plaintext, uaPublic, auth, new KeyPair(asPublic, asPrivate), salt);

        assertEquals(
                "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6Tlz"
                        + "AC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
                WebPushCrypto.b64(body));
    }

    @Test
    void receiverCanDecrypt() throws Exception {
        KeyPair ua = WebPushCrypto.newKeyPair();
        byte[] uaPublic = WebPushCrypto.encodePublic((ECPublicKey) ua.getPublic());
        byte[] auth = new byte[16];
        Arrays.fill(auth, (byte) 7);
        byte[] plaintext = "{\"title\":\"测试\"}".getBytes(StandardCharsets.UTF_8);

        byte[] body = WebPushCrypto.encrypt(plaintext, WebPushCrypto.b64(uaPublic), WebPushCrypto.b64(auth));

        ByteBuffer buf = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        buf.get(salt);
        assertEquals(4096, buf.getInt());
        byte[] asPublic = new byte[buf.get()];
        buf.get(asPublic);
        byte[] ciphertext = new byte[buf.remaining()];
        buf.get(ciphertext);

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(ua.getPrivate());
        ka.doPhase(WebPushCrypto.decodePublic(asPublic), true);
        byte[] keyInfo = WebPushCrypto.concat(
                "WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[] {0}, uaPublic, asPublic);
        byte[] ikm = WebPushCrypto.hkdf(auth, ka.generateSecret(), keyInfo, 32);
        byte[] cek = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = cipher.doFinal(ciphertext);

        assertEquals(2, padded[padded.length - 1]);
        assertArrayEquals(plaintext, Arrays.copyOf(padded, padded.length - 1));
    }

    @Test
    void vapidJwtVerifiesWithPublicKey() throws Exception {
        WebPushCrypto.VapidKeys keys = WebPushCrypto.generateVapidKeys();
        String header = WebPushCrypto.vapidAuthorization(
                "https://fcm.googleapis.com/fcm/send/abc", "mailto:ops@example.com", keys, 1_900_000_000L);

        assertTrue(header.startsWith("vapid t="));
        assertTrue(header.endsWith(", k=" + keys.publicKey()));
        String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertTrue(claims.contains("\"aud\":\"https://fcm.googleapis.com\""));

        Signature sig = Signature.getInstance("SHA256withECDSAinP1363Format");
        sig.initVerify(WebPushCrypto.decodePublic(d(keys.publicKey())));
        sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(sig.verify(Base64.getUrlDecoder().decode(parts[2])));
    }

    private static byte[] d(String s) {
        return WebPushCrypto.decode(s);
    }
}

package com.diegogarciarojo.snowgolemreplay;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** RFC 8188 aes128gcm; interoperable with file-kiwi/node's wormhole-crypto. */
final class KiwiCrypto {
    static final int RECORD_SIZE = 65536;
    private static final SecureRandom RANDOM = new SecureRandom();
    static byte[] randomKey() { byte[] k = new byte[16]; RANDOM.nextBytes(k); return k; }
    static String fragment(byte[] key) { return Base64.getUrlEncoder().withoutPadding().encodeToString(key); }
    static String encryptedText(String s, byte[] key) throws GeneralSecurityException {
        return Base64.getEncoder().encodeToString(encrypt(s.getBytes(StandardCharsets.UTF_8), key));
    }
    static byte[] encrypt(byte[] plain, byte[] key) throws GeneralSecurityException {
        return encrypt(plain, key, randomKey());
    }
    static byte[] encrypt(byte[] plain, byte[] key, byte[] salt) throws GeneralSecurityException {
        if (key.length != 16 || salt.length != 16) throw new IllegalArgumentException("Expected 128-bit key/salt");
        byte[] aesKey = hkdf(key, salt, "Content-Encoding: aes128gcm\0", 16);
        byte[] baseNonce = hkdf(key, salt, "Content-Encoding: nonce\0", 12);
        var out = new ByteArrayOutputStream(plain.length + plain.length / 3000 + 64);
        out.writeBytes(ByteBuffer.allocate(21).put(salt).putInt(RECORD_SIZE).put((byte)0).array());
        int capacity = RECORD_SIZE - 17;
        int count = Math.max(1, (plain.length + capacity - 1) / capacity);
        for (int seq = 0, offset = 0; seq < count; seq++) {
            int n = Math.min(capacity, plain.length - offset);
            byte[] record = Arrays.copyOfRange(plain, offset, offset + n + 1);
            record[n] = (byte)(seq == count - 1 ? 2 : 1);
            byte[] nonce = baseNonce.clone();
            for (int j = 0; j < 4; j++) nonce[11-j] ^= (byte)(seq >>> (8*j));
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, nonce));
            out.writeBytes(cipher.doFinal(record));
            offset += n;
        }
        return out.toByteArray();
    }
    private static byte[] hkdf(byte[] key, byte[] salt, String info, int n) throws GeneralSecurityException {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(key);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info.getBytes(StandardCharsets.UTF_8));
        mac.update((byte)1);
        return Arrays.copyOf(mac.doFinal(), n);
    }
}

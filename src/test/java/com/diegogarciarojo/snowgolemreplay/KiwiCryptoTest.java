package com.diegogarciarojo.snowgolemreplay;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class KiwiCryptoTest {
    @Test void matchesIndependentOfficialSdkCiphertextAcrossRecordBoundaries() throws Exception {
        byte[] key = new byte[16], salt = new byte[16];
        for (int i = 0; i < 16; i++) { key[i] = (byte)i; salt[i] = (byte)(31-i); }
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/kiwi-vectors.json"))) {
            for (var element : JsonParser.parseReader(reader).getAsJsonArray()) {
                var vector = element.getAsJsonObject();
                int size = vector.get("size").getAsInt();
                byte[] plain = new byte[size];
                for (int i = 0; i < size; i++) plain[i] = (byte)(i % 251);
                byte[] cipher = KiwiCrypto.encrypt(plain, key, salt);
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cipher));
                assertEquals(vector.get("sha256").getAsString(), hash, "size=" + size);
            }
        }
    }
    @Test void independentEncryptionsUseDifferentSaltsAndSafeFragments() throws Exception {
        byte[] key = KiwiCrypto.randomKey();
        assertEquals(22, KiwiCrypto.fragment(key).length());
        assertTrue(KiwiCrypto.fragment(key).matches("[A-Za-z0-9_-]+"));
        assertFalse(java.util.Arrays.equals(KiwiCrypto.encrypt(new byte[]{1}, key), KiwiCrypto.encrypt(new byte[]{1}, key)));
    }
}

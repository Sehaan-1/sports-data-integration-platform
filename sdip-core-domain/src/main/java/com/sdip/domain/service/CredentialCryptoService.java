package com.sdip.domain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM-256 application-level encryption of vendor API credentials (§4.5).
 * The 256-bit key is supplied via the {@code SDIP_CREDENTIAL_KEY_B64}
 * environment variable (base64-encoded 32 bytes). Output layout per blob:
 * {@code 12-byte IV || ciphertext||tag}. Protects against database-dump
 * exfiltration; app-server compromise is out of scope per the threat model.
 */
@Service
public class CredentialCryptoService {

    /** Deterministic development-only key (bytes 0x00..0x1f). NEVER for production. */
    public static final String DEFAULT_DEV_KEY_B64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private static final Logger log = LoggerFactory.getLogger(CredentialCryptoService.class);
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CredentialCryptoService(
            @Value("${sdip.security.credential-key-b64:}") String keyB64) {
        String effective = (keyB64 == null || keyB64.isBlank()) ? DEFAULT_DEV_KEY_B64 : keyB64;
        byte[] keyBytes = Base64.getDecoder().decode(effective);
        if (keyBytes.length != 32) {
            throw new IllegalStateException("sdip.security.credential-key-b64 must decode to 32 bytes (AES-256)");
        }
        if (DEFAULT_DEV_KEY_B64.equals(effective)) {
            log.warn("Using the built-in DEVELOPMENT credential key. Set SDIP_CREDENTIAL_KEY_B64 for any real deployment.");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public byte[] encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Credential encryption failed", e);
        }
    }

    public String decrypt(byte[] blob) {
        try {
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(blob, 0, iv, 0, IV_LEN);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(blob, IV_LEN, blob.length - IV_LEN),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Credential decryption failed (wrong key or corrupted data)", e);
        }
    }
}

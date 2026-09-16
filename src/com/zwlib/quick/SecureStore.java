package com.zwlib.quick;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.Charset;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Tiny wrapper over Android Keystore (AES-256/GCM) + SharedPreferences.
 * The key never leaves the TEE/StrongBox; the prefs file only ever holds ciphertext.
 */
final class SecureStore {

    private static final String ALIAS = "zw_quick_v1";
    private static final String PREFS = "zw_secure";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final SharedPreferences sp;

    SecureStore(Context c) {
        sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean available() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    void put(String key, String value) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] ct = c.doFinal(value.getBytes(UTF8));
            byte[] iv = c.getIV();
            byte[] out = new byte[1 + iv.length + ct.length];
            out[0] = (byte) iv.length;
            System.arraycopy(iv, 0, out, 1, iv.length);
            System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
            sp.edit().putString(key, Base64.encodeToString(out, Base64.NO_WRAP)).apply();
        } catch (Exception ignored) {
        }
    }

    String get(String key) {
        String s = sp.getString(key, null);
        if (s == null) {
            return null;
        }
        try {
            byte[] in = Base64.decode(s, Base64.NO_WRAP);
            int n = in[0] & 0xFF;
            if (n <= 0 || n + 1 >= in.length) {
                return null;
            }
            byte[] iv = new byte[n];
            System.arraycopy(in, 1, iv, 0, n);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(in, 1 + n, in.length - 1 - n), UTF8);
        } catch (Exception e) {
            return null;
        }
    }

    void remove(String key) {
        sp.edit().remove(key).apply();
    }
}

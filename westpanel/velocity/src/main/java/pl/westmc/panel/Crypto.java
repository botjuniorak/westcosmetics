package pl.westmc.panel;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/** Hasla (PBKDF2), kody 2FA (TOTP, zgodne z Google Authenticator) i losowe tokeny. */
final class Crypto {
    private static final SecureRandom RNG = new SecureRandom();
    private static final int ITER = 150_000;
    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Crypto() { }

    static String token(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Czytelne haslo startowe, np. "kq7m-2xwp-9fhd". */
    static String tempPassword() {
        String a = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) s.append('-');
            s.append(a.charAt(RNG.nextInt(a.length())));
        }
        return s.toString();
    }

    static String hash(String password) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        return "pbkdf2$" + ITER + "$" + Base64.getEncoder().encodeToString(salt) + "$"
            + Base64.getEncoder().encodeToString(pbkdf2(password, salt, ITER));
    }

    static boolean verify(String password, String stored) {
        try {
            String[] p = stored.split("\\$");
            int iter = Integer.parseInt(p[1]);
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] want = Base64.getDecoder().decode(p[3]);
            return MessageDigest.isEqual(want, pbkdf2(password, salt, iter));
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iter) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iter, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------ TOTP

    static String newTotpSecret() {
        byte[] b = new byte[20];
        RNG.nextBytes(b);
        return base32(b);
    }

    /** Akceptuje kod z biezacego okna 30 s oraz jednego przed i po (rozjechany zegar w telefonie). */
    static boolean totpOk(String secret, String code) {
        if (secret == null || code == null) return false;
        code = code.replaceAll("\\s", "");
        if (!code.matches("\\d{6}")) return false;
        long step = System.currentTimeMillis() / 1000 / 30;
        byte[] key = unbase32(secret);
        for (long s = step - 1; s <= step + 1; s++) {
            if (MessageDigest.isEqual(String.format(Locale.ROOT, "%06d", totp(key, s)).getBytes(), code.getBytes())) return true;
        }
        return false;
    }

    private static int totp(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0xf;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return bin % 1_000_000;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buf = 0, bits = 0;
        for (byte b : data) {
            buf = (buf << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) { sb.append(B32.charAt((buf >> (bits - 5)) & 31)); bits -= 5; }
        }
        if (bits > 0) sb.append(B32.charAt((buf << (5 - bits)) & 31));
        return sb.toString();
    }

    private static byte[] unbase32(String s) {
        s = s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z2-7]", "");
        ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
        int buf = 0, bits = 0;
        for (char c : s.toCharArray()) {
            buf = (buf << 5) | B32.indexOf(c);
            bits += 5;
            if (bits >= 8) { out.put((byte) (buf >> (bits - 8))); bits -= 8; }
        }
        return out.array();
    }
}

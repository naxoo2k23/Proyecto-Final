package com.playmatch.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Utilidades de seguridad criptográfica (PBKDF2WithHmacSHA256) y validación de entradas.
 */
public class SeguridadUtil {
    private static final String ALGORITMO = "PBKDF2WithHmacSHA256";
    private static final int ITERACIONES = 20000;
    private static final int TAMANO_SALT = 16;
    private static final int LONGITUD_CLAVE = 256;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern EMAIL_REGEX = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern USERNAME_REGEX = Pattern.compile("^[A-Za-z0-9_. -]{3,32}$");
    private static final Pattern DATA_IMAGE_REGEX = Pattern.compile("^data:image/(png|jpe?g|webp|gif);base64,[A-Za-z0-9+/=]+$");
    private static final Pattern DISCORD_MODERN_REGEX = Pattern.compile("^[a-z0-9._]{2,32}$");
    private static final Pattern DISCORD_LEGACY_REGEX = Pattern.compile("^.{2,32}#[0-9]{4}$");
    private static final Pattern DISCORD_ID_REGEX = Pattern.compile("^[0-9]{17,22}$");

    private SeguridadUtil() {}

    /**
     * Genera un hash salado unidireccional usando PBKDF2WithHmacSHA256.
     * Formato almacenado: pbkdf2$iteraciones$saltBase64$hashBase64
     */
    public static String hashPassword(String rawPassword) {
        if (rawPassword == null) {
            throw new IllegalArgumentException("La contraseña no puede ser nula.");
        }
        try {
            byte[] salt = new byte[TAMANO_SALT];
            RANDOM.nextBytes(salt);
            byte[] hash = deriveKey(rawPassword.toCharArray(), salt, ITERACIONES, LONGITUD_CLAVE);
            return "pbkdf2$" + ITERACIONES + "$" +
                    Base64.getEncoder().encodeToString(salt) + "$" +
                    Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Error generando hash de contraseña", e);
        }
    }

    /**
     * Verifica una contraseña en texto plano contra el hash almacenado usando comparación en tiempo constante.
     */
    public static boolean verifyPassword(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null || storedHash.isEmpty()) {
            return false;
        }
        if (storedHash.startsWith("pbkdf2$")) {
            String[] parts = storedHash.split("\\$");
            if (parts.length != 4) return false;
            try {
                int iteraciones = Integer.parseInt(parts[1]);
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expectedHash = Base64.getDecoder().decode(parts[3]);
                byte[] actualHash = deriveKey(rawPassword.toCharArray(), salt, iteraciones, expectedHash.length * 8);
                return MessageDigest.isEqual(expectedHash, actualHash);
            } catch (Exception e) {
                return false;
            }
        }
        // Fallback legado si existiese prefijo hash_ antiguo
        return ("hash_" + rawPassword).equals(storedHash);
    }

    private static byte[] deriveKey(char[] password, byte[] salt, int iterations, int keyLengthBits) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLengthBits);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITMO);
        return factory.generateSecret(spec).getEncoded();
    }

    public static boolean isValidEmail(String email) {
        return email != null && EMAIL_REGEX.matcher(email.trim()).matches();
    }

    public static boolean isValidUsername(String username) {
        return username != null && USERNAME_REGEX.matcher(username.trim()).matches();
    }

    public static boolean isValidPhone(String phone) {
        if (phone == null) return false;
        String digits = phone.replaceAll("[^0-9]", "");
        return digits.length() >= 8 && digits.length() <= 15;
    }

    public static String normalizarUsername(String username) {
        if (username == null) return "";
        return username.trim().replaceAll("\\s+", "_");
    }

    public static String generarCodigoJuego(String input) {
        if (input == null) return "game";
        String clean = input.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return clean.isEmpty() ? "game-" + System.currentTimeMillis() : clean;
    }

    public static boolean isValidAvatarUrl(String url) {
        if (url == null) return false;
        String clean = url.trim();
        if (clean.isEmpty()) return false;
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.startsWith("data:image/svg")) return false;
        if (lower.startsWith("data:image/")) {
            return clean.length() <= 750_000 && DATA_IMAGE_REGEX.matcher(clean).matches();
        }
        if (clean.length() > 1024 || clean.matches(".*[\\s<>\"'].*")) return false;
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            return true;
        }
        return (clean.startsWith("/assets/") || clean.startsWith("/api/avatar?")) && !clean.contains("..");
    }

    public static String normalizarMarcoPerfil(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "NONE";
        String up = raw.trim().toUpperCase(Locale.ROOT);
        if ("GOLD".equals(up) || "EMERALD".equals(up) || "RUBY".equals(up)
                || "DIAMOND".equals(up) || "CHALLENGER".equals(up)) {
            return up;
        }
        return "NONE";
    }

    public static String normalizarDiscordTag(String raw) {
        if (raw == null) return "";
        String clean = raw.trim();
        if (clean.startsWith("@")) clean = clean.substring(1).trim();
        return clean.contains("#") ? clean : clean.toLowerCase(Locale.ROOT);
    }

    public static boolean isValidDiscordTag(String raw) {
        String clean = normalizarDiscordTag(raw);
        if (clean.isEmpty()) return false;
        if (clean.contains("#")) {
            return DISCORD_LEGACY_REGEX.matcher(clean).matches();
        }
        return !clean.contains("..") && DISCORD_MODERN_REGEX.matcher(clean).matches();
    }

    public static boolean isValidDiscordId(String id) {
        if (id == null || id.trim().isEmpty()) return true;
        return DISCORD_ID_REGEX.matcher(id.trim()).matches();
    }

    public static String generarCodigoVerificacion6Digitos() {
        int code = 100000 + RANDOM.nextInt(900000);
        return String.valueOf(code);
    }
}

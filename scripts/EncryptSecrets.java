import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class EncryptSecrets {
    static final byte[] SALT = new byte[]{
        (byte)0x73, (byte)0x74, (byte)0x72, (byte)0x61,
        (byte)0x6E, (byte)0x67, (byte)0x65, (byte)0x72,
        (byte)0x5F, (byte)0x6B, (byte)0x64, (byte)0x66,
        (byte)0x5F, (byte)0x76, (byte)0x31, (byte)0x21
    };

    static byte[] deriveKey(String pin) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), SALT, 100000, 256);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return skf.generateSecret(spec).getEncoded();
    }

    static byte[] encrypt(byte[] key, byte ivSeed, String plaintext) throws Exception {
        byte[] iv = new byte[12];
        for (int i = 0; i < 12; i++) iv[i] = (byte)(ivSeed + i * 17);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ct, 0, combined, iv.length, ct.length);
        return combined;
    }

    static String formatKotlinByteArray(String marker, String name, byte[] data) {
        StringBuilder sb = new StringBuilder();
        sb.append("    // BEGIN_").append(marker).append("\n");
        sb.append("    private val ").append(name).append(" = byteArrayOf(\n        ");
        for (int i = 0; i < data.length; i++) {
            sb.append(String.format("0x%02X.toByte()", data[i] & 0xFF));
            if (i < data.length - 1) {
                sb.append(", ");
                if ((i + 1) % 8 == 0) sb.append("\n        ");
            }
        }
        sb.append("\n    )\n");
        sb.append("    // END_").append(marker);
        return sb.toString();
    }

    static String replaceBlock(String content, String marker, String replacement) {
        String startTag = "    // BEGIN_" + marker;
        String endTag = "    // END_" + marker;
        int start = content.indexOf(startTag);
        int end = content.indexOf(endTag);
        if (start == -1 || end == -1) {
            throw new IllegalStateException("Marker not found: " + marker);
        }
        return content.substring(0, start) + replacement + content.substring(end + endTag.length());
    }

    public static void main(String[] args) throws Exception {
        File propFile = new File("local.properties");
        if (!propFile.exists()) {
            System.err.println("ERROR: local.properties not found!");
            System.exit(1);
        }

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(propFile)) {
            props.load(fis);
        }

        String pin = props.getProperty("r2.pin", "0529").trim();
        String endpoint = props.getProperty("r2.endpoint", "").trim();
        String bucket = props.getProperty("r2.bucket.name", "chat").trim();
        String accessKey = props.getProperty("r2.access.key.id", "").trim();
        String secretKey = props.getProperty("r2.secret.access.key", "").trim();

        if (endpoint.isEmpty() || accessKey.isEmpty() || secretKey.isEmpty() || accessKey.startsWith("PASTE_")) {
            System.err.println("ERROR: Please fill in r2.endpoint, r2.access.key.id, and r2.secret.access.key in local.properties first!");
            System.exit(1);
        }

        byte[] key = deriveKey(pin);
        String endpointArr = formatKotlinByteArray("ENDPOINT_ENC", "endpointEnc", encrypt(key, (byte)0x11, endpoint));
        String bucketArr = formatKotlinByteArray("BUCKET_ENC", "bucketEnc", encrypt(key, (byte)0x22, bucket));
        String accessArr = formatKotlinByteArray("ACCESS_ENC", "accessKeyEnc", encrypt(key, (byte)0x33, accessKey));
        String secretArr = formatKotlinByteArray("SECRET_ENC", "secretKeyEnc", encrypt(key, (byte)0x44, secretKey));

        Path targetPath = Path.of("app/src/main/java/com/tk/stranger/SecurityConfig.kt");
        String content = Files.readString(targetPath, StandardCharsets.UTF_8);

        content = replaceBlock(content, "ENDPOINT_ENC", endpointArr);
        content = replaceBlock(content, "BUCKET_ENC", bucketArr);
        content = replaceBlock(content, "ACCESS_ENC", accessArr);
        content = replaceBlock(content, "SECRET_ENC", secretArr);

        Files.writeString(targetPath, content, StandardCharsets.UTF_8);

        System.out.println("SUCCESS! Encrypted R2 keys into SecurityConfig.kt (and kept your copy in local.properties).");
    }
}

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import java.nio.file.*;
Path dir = Paths.get(System.getProperty("fixtures.dir"));
byte[] pt = Files.readAllBytes(dir.resolve("plaintext.txt"));
for (String n : new String[]{"aes256", "aes128", "aes192"}) {
  Crypto.loadKey(n, dir.resolve("key_" + n + ".txt").toString());
  Files.write(dir.resolve("java_" + n + ".bin"), Crypto.encryptData(n, pt));
}
Files.write(dir.resolve("java_aes256_empty.bin"), Crypto.encryptData("aes256", new byte[0]));
/exit

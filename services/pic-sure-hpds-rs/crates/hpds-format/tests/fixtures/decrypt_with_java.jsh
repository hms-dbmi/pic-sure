import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import java.nio.file.*;
Crypto.loadKey("k", System.getProperty("key.file"));
byte[] plain = Crypto.decryptData("k", Files.readAllBytes(Paths.get(System.getProperty("cipher.file"))));
Files.write(Paths.get(System.getProperty("out.file")), plain);
/exit

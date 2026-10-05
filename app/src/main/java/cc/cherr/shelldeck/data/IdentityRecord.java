package cc.cherr.shelldeck.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.*;

@Entity(tableName = "identities")
public class IdentityRecord {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String label = "";
    @NonNull public String fingerprint = "";
    @NonNull public String algorithm = "";
    @Nullable public String publicKey;
    @NonNull public byte[] encryptedKey = new byte[0];
}

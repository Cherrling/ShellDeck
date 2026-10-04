package cc.cherr.shelldeck.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.*;

@Entity(tableName = "hosts", foreignKeys = @ForeignKey(entity = IdentityRecord.class, parentColumns = "id", childColumns = "identityId", onDelete = ForeignKey.RESTRICT), indices = @Index("identityId"))
public class HostRecord {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String label = "";
    @NonNull public String hostname = "";
    public int port = 22;
    @NonNull public String username = "";
    @Nullable public String identityId;
}

package cc.cherr.shelldeck.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.*;

@Entity(tableName = "known_hosts", primaryKeys = {"hostname", "port"})
public class KnownHostRecord {
    @NonNull public String hostname = "";
    public int port;
    @NonNull public String algorithm = "";
    @NonNull public String fingerprint = "";
}

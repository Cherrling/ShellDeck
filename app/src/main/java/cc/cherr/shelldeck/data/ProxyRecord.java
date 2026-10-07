package cc.cherr.shelldeck.data;

import androidx.annotation.NonNull;
import androidx.room.*;

@Entity(tableName = "proxies")
public class ProxyRecord {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String label = "";
    @NonNull public String hostname = "";
    public int port = 1080;
    public boolean remoteDns = true;
    public boolean authenticated = false;
    @NonNull public byte[] encryptedCredentials = new byte[0];
}

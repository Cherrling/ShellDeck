package cc.cherr.shelldeck.data;

import androidx.room.*;
import java.util.List;

@Dao
public interface StoreDao {
    @Query("SELECT * FROM proxies ORDER BY label COLLATE NOCASE") List<ProxyRecord> proxies();
    @Query("SELECT * FROM proxies WHERE id = :id") ProxyRecord proxy(String id);
    @Upsert void saveProxy(ProxyRecord proxy);
    @Query("DELETE FROM proxies WHERE id = :id AND NOT EXISTS (SELECT 1 FROM hosts WHERE proxyId = :id)") int deleteProxy(String id);
    @Query("SELECT * FROM hosts ORDER BY label COLLATE NOCASE") List<HostRecord> hosts();
    @Query("SELECT * FROM identities ORDER BY label COLLATE NOCASE") List<IdentityRecord> identities();
    @Query("SELECT * FROM identities WHERE id = :id") IdentityRecord identity(String id);
    @Query("SELECT * FROM hosts WHERE id = :id") HostRecord host(String id);
    @Query("UPDATE hosts SET favorite = NOT favorite WHERE id = :id") void toggleFavorite(String id);
    @Query("UPDATE hosts SET lastUsedAt = MAX(lastUsedAt, :timestamp) WHERE id = :id") void markUsed(String id, long timestamp);
    @Upsert void saveHost(HostRecord host);
    @Insert void insertIdentity(IdentityRecord identity);
    @Upsert void saveIdentity(IdentityRecord identity);
    @Query("UPDATE identities SET publicKey = :publicKey WHERE id = :id") void savePublicKey(String id, String publicKey);
    @Query("DELETE FROM hosts WHERE id = :id") void deleteHost(String id);
    @Query("SELECT COUNT(*) FROM hosts WHERE identityId = :id") int identityUsers(String id);
    @Query("DELETE FROM identities WHERE id = :id") void deleteIdentity(String id);
    @Query("SELECT * FROM known_hosts WHERE hostname = :hostname AND port = :port") KnownHostRecord knownHost(String hostname, int port);
    @Upsert void saveKnownHost(KnownHostRecord host);
}

package cc.cherr.shelldeck.data;

import androidx.room.*;
import java.util.List;

@Dao
public interface StoreDao {
    @Query("SELECT * FROM hosts ORDER BY label COLLATE NOCASE") List<HostRecord> hosts();
    @Query("SELECT * FROM identities ORDER BY label COLLATE NOCASE") List<IdentityRecord> identities();
    @Query("SELECT * FROM identities WHERE id = :id") IdentityRecord identity(String id);
    @Upsert void saveHost(HostRecord host);
    @Insert void insertIdentity(IdentityRecord identity);
    @Query("DELETE FROM hosts WHERE id = :id") void deleteHost(String id);
    @Query("SELECT COUNT(*) FROM hosts WHERE identityId = :id") int identityUsers(String id);
    @Query("DELETE FROM identities WHERE id = :id") void deleteIdentity(String id);
    @Query("SELECT * FROM known_hosts WHERE hostname = :hostname AND port = :port") KnownHostRecord knownHost(String hostname, int port);
    @Upsert void saveKnownHost(KnownHostRecord host);
}

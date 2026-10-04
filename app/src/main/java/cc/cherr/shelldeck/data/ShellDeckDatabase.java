package cc.cherr.shelldeck.data;

import androidx.room.*;

@Database(entities = {HostRecord.class, IdentityRecord.class, KnownHostRecord.class}, version = 1, exportSchema = true)
public abstract class ShellDeckDatabase extends RoomDatabase {
    public abstract StoreDao records();
}

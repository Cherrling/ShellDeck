package cc.cherr.shelldeck.data;

import androidx.room.*;

@Database(entities = {HostRecord.class, IdentityRecord.class, KnownHostRecord.class, ProxyRecord.class}, version = 6, exportSchema = true)
public abstract class ShellDeckDatabase extends RoomDatabase {
    public static final androidx.room.migration.Migration MIGRATION_1_2 = new androidx.room.migration.Migration(1, 2) {
        @Override public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE hosts ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE hosts ADD COLUMN lastUsedAt INTEGER NOT NULL DEFAULT 0");
        }
    };
    public static final androidx.room.migration.Migration MIGRATION_2_3 = new androidx.room.migration.Migration(2, 3) {
        @Override public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE hosts ADD COLUMN startupCommand TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE identities ADD COLUMN publicKey TEXT");
        }
    };
    public static final androidx.room.migration.Migration MIGRATION_3_4 = new androidx.room.migration.Migration(3, 4) {
        @Override public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE hosts ADD COLUMN jumpHostId TEXT");
        }
    };
    public static final androidx.room.migration.Migration MIGRATION_4_5 = new androidx.room.migration.Migration(4, 5) {
        @Override public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS proxies (id TEXT NOT NULL PRIMARY KEY, label TEXT NOT NULL, hostname TEXT NOT NULL, port INTEGER NOT NULL, remoteDns INTEGER NOT NULL, authenticated INTEGER NOT NULL, encryptedCredentials BLOB NOT NULL)");
            db.execSQL("ALTER TABLE hosts ADD COLUMN proxyId TEXT");
        }
    };
    public static final androidx.room.migration.Migration MIGRATION_5_6 = new androidx.room.migration.Migration(5, 6) {
        @Override public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE hosts ADD COLUMN protocol TEXT NOT NULL DEFAULT 'ssh'");
            db.execSQL("ALTER TABLE hosts ADD COLUMN moshPort INTEGER NOT NULL DEFAULT 0");
        }
    };
    public abstract StoreDao records();
}

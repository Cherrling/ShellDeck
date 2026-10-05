package cc.cherr.shelldeck.data;

import androidx.room.*;

@Database(entities = {HostRecord.class, IdentityRecord.class, KnownHostRecord.class}, version = 3, exportSchema = true)
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
    public abstract StoreDao records();
}

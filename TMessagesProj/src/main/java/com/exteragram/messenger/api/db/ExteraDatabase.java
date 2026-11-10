package com.exteragram.messenger.api.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * StaticGram replacement of exteraGram's Room database: the same "extera-database" file and
 * ProfileDTO table, opened with a plain SQLiteOpenHelper. Kept shape for reflection:
 * {@code ExteraDatabase.INSTANCE.getInstance().getInvalidationTracker().refreshAsync()}.
 */
public final class ExteraDatabase {

    /** Kotlin companion, named INSTANCE like exteraGram's renamed Companion field. */
    public static final Companion INSTANCE = new Companion();

    private static final String NAME = "extera-database";
    /** 2: frameParams column (avatar frames v2); the upgrade forces a full profile resync. */
    private static final int VERSION = 2;
    private static volatile ExteraDatabase instance;

    private final Helper helper;
    private final ProfileDao_Impl profileDao;
    private final InvalidationTracker invalidationTracker = new InvalidationTracker();

    private ExteraDatabase(Context context) {
        helper = new Helper(context);
        profileDao = new ProfileDao_Impl(this);
    }

    public ProfileDao profileDao() {
        return profileDao;
    }

    public InvalidationTracker getInvalidationTracker() {
        return invalidationTracker;
    }

    public SQLiteDatabase getWritableDatabase() {
        return helper.getWritableDatabase();
    }

    public static final class Companion {

        private Companion() {
        }

        public ExteraDatabase getInstance() {
            ExteraDatabase db = instance;
            if (db != null) {
                return db;
            }
            synchronized (this) {
                if (instance == null) {
                    instance = new ExteraDatabase(ApplicationLoader.applicationContext);
                }
                return instance;
            }
        }
    }

    /** Minimal stand-in for Room's InvalidationTracker: refreshAsync() notifies observers on their own thread. */
    public static final class InvalidationTracker {

        private final CopyOnWriteArrayList<Runnable> observers = new CopyOnWriteArrayList<>();

        public void addObserver(Runnable observer) {
            observers.addIfAbsent(observer);
        }

        public void removeObserver(Runnable observer) {
            observers.remove(observer);
        }

        public void refreshAsync() {
            for (Runnable r : observers) {
                try {
                    r.run();
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }
        }
    }

    private static final class Helper extends SQLiteOpenHelper {

        Helper(Context context) {
            super(context, NAME, null, VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            createTables(db);
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            super.onOpen(db);
            if (!db.isReadOnly()) {
                createTables(db);
            }
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            createTables(db);
            if (oldVersion < 2) {
                // rows stored by an older build lack frame_params: forget the sync watermark so the
                // next sync is a full list
                com.exteragram.messenger.api.ApiController.resetSyncState();
            }
        }

        @Override
        public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS `ProfileDTO`");
            createTables(db);
        }

        private static void createTables(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `ProfileDTO` (`id` INTEGER NOT NULL, `type` TEXT NOT NULL, `status` TEXT NOT NULL, `badge` TEXT, `nowPlaying` TEXT, `deleted` INTEGER, `canChangeBadge` INTEGER, `frame` TEXT, `frameParams` TEXT, `bannerId` TEXT, `bannerType` TEXT, `number` INTEGER, PRIMARY KEY(`id`))");
            addColumn(db, "frame");
            addColumn(db, "frameParams");
            addColumn(db, "bannerId");
            addColumn(db, "bannerType");
            // exteraGram 13.1.1 (MIGRATION_5_6): the supporter badge number
            addColumn(db, "number", "INTEGER");
        }

        /**
         * StaticGram avatar frames: a table created by an older build lacks the nullable `frame` /
         * `frameParams` columns. Add them and forget the sync watermark, so the next sync is a full
         * list that brings the frames of rows the old build already stored without them.
         */
        private static void addColumn(SQLiteDatabase db, String column) {
            addColumn(db, column, "TEXT");
        }

        private static void addColumn(SQLiteDatabase db, String column, String sqlType) {
            boolean has = false;
            try (android.database.Cursor c = db.rawQuery("PRAGMA table_info(`ProfileDTO`)", null)) {
                int nameIdx = c.getColumnIndex("name");
                while (c.moveToNext()) {
                    if (column.equals(c.getString(nameIdx))) {
                        has = true;
                        break;
                    }
                }
            }
            if (!has) {
                db.execSQL("ALTER TABLE `ProfileDTO` ADD COLUMN `" + column + "` " + sqlType);
                com.exteragram.messenger.api.ApiController.resetSyncState();
            }
        }
    }
}

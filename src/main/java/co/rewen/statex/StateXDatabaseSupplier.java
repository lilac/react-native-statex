/**
 * Copyright (c) 2015-present, Junjun Deng
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package co.rewen.statex;

import javax.annotation.Nullable;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

// VisibleForTesting
public class StateXDatabaseSupplier extends SQLiteOpenHelper {

    // VisibleForTesting
    public static final String DATABASE_NAME = "StateXStorage";

    private static final int DATABASE_VERSION = 1;
    private static final int SLEEP_TIME_MS = 30;
    private static final long DEFAULT_MAX_DB_SIZE = 6L * 1024L * 1024L; // 6 MB in bytes

    static final String TABLE_STATE = "state";
    static final String KEY_COLUMN = "key";
    static final String VALUE_COLUMN = "value";

    static final String VERSION_TABLE_CREATE =
            "CREATE TABLE " + TABLE_STATE + " (" +
                    KEY_COLUMN + " TEXT PRIMARY KEY, " +
                    VALUE_COLUMN + " TEXT NOT NULL" +
                    ")";

    private Context mContext;
    private @Nullable SQLiteDatabase mDb;

    public StateXDatabaseSupplier(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        mContext = context;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(VERSION_TABLE_CREATE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion != newVersion) {
            deleteDatabase();
            onCreate(db);
        }
    }

    /**
     * Verify the database exists and is open.
     */
  /* package */ synchronized boolean ensureDatabase() {
        if (mDb != null && mDb.isOpen()) {
            return true;
        }
        // Sometimes retrieving the database fails. We do 2 retries: first without database deletion
        // and then with deletion.
        SQLiteException lastSQLiteException = null;
        for (int tries = 0; tries < 2; tries++) {
            try {
                if (tries > 0) {
                    deleteDatabase();
                }
                mDb = getWritableDatabase();
                break;
            } catch (SQLiteException e) {
                lastSQLiteException = e;
            }
            // Wait before retrying.
            try {
                Thread.sleep(SLEEP_TIME_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        if (mDb == null) {
            throw lastSQLiteException;
        }
        // This is a sane limit to protect the user from the app storing too much data in the database.
        // This also protects the database from filling up the disk cache and becoming malformed
        // (endTransaction() calls will throw an exception, not rollback, and leave the db malformed).
        mDb.setMaximumSize(DEFAULT_MAX_DB_SIZE);
        return true;
    }

    /**
     * Create and/or open the database.
     */
  /* package */ synchronized SQLiteDatabase get() {
        ensureDatabase();
        return mDb;
    }

    /* package */ synchronized boolean deleteDatabase() {
        if (mDb != null && mDb.isOpen()) {
            mDb.close();
            mDb = null;
        }
        return mContext.deleteDatabase(DATABASE_NAME);
    }
}

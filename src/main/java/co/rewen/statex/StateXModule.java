package co.rewen.statex;

/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 * <p>
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */


import android.database.Cursor;
import android.database.sqlite.SQLiteStatement;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.GuardedAsyncTask;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.common.SetBuilder;
import com.facebook.react.modules.common.ModuleDataCleaner;

import java.util.HashSet;

import static co.rewen.statex.StateXDatabaseSupplier.KEY_COLUMN;
import static co.rewen.statex.StateXDatabaseSupplier.TABLE_STATE;
import static co.rewen.statex.StateXDatabaseSupplier.VALUE_COLUMN;

public final class StateXModule
        extends ReactContextBaseJavaModule implements ModuleDataCleaner.Cleanable {

    // SQL variable number limit, defined by SQLITE_LIMIT_VARIABLE_NUMBER:
    // https://raw.githubusercontent.com/android/platform_external_sqlite/master/dist/sqlite3.c
    private static final int MAX_SQL_KEYS = 999;

    private StateXDatabaseSupplier mStateXDatabaseSupplier;
    private boolean mShuttingDown = false;

    public StateXModule(ReactApplicationContext reactContext) {
        super(reactContext);
        mStateXDatabaseSupplier = new StateXDatabaseSupplier(reactContext);
    }

    @Override
    public String getName() {
        return "StateX";
    }

    @Override
    public void initialize() {
        super.initialize();
        mShuttingDown = false;
    }

    @Override
    public void onCatalystInstanceDestroy() {
        mShuttingDown = true;
    }

    @Override
    public void clearSensitiveData() {
        // Clear local storage. If fails, crash, since the app is potentially in a bad state and could
        // cause a privacy violation. We're still not recovering from this well, but at least the error
        // will be reported to the server.
        clear(
                new Callback() {
                    @Override
                    public void invoke(Object... args) {
                        if (args.length == 0) {
                            FLog.d(ReactConstants.TAG, "Cleaned StateX.");
                            return;
                        }
                        // Clearing the database has failed, delete it instead.
                        if (mStateXDatabaseSupplier.deleteDatabase()) {
                            FLog.d(ReactConstants.TAG, "Deleted Local Database StateX.");
                            return;
                        }
                        // Everything failed, crash the app
                        throw new RuntimeException("Clearing and deleting database failed: " + args[0]);
                    }
                });
    }

    /**
     * Given an array of keys, this returns a map of (key, value) pairs for the keys found, and
     * (key, null) for the keys that haven't been found.
     */
    @ReactMethod
    public void multiGet(final ReadableArray keys, final Callback callback) {
        if (keys == null) {
            callback.invoke(AsyncStorageErrorUtil.getInvalidKeyError(null), null);
            return;
        }

        new GuardedAsyncTask<Void, Void>(getReactApplicationContext()) {
            @Override
            protected void doInBackgroundGuarded(Void... params) {
                if (!ensureDatabase()) {
                    callback.invoke(AsyncStorageErrorUtil.getDBError(null), null);
                    return;
                }

                String[] columns = {KEY_COLUMN, VALUE_COLUMN};
                HashSet<String> keysRemaining = SetBuilder.newHashSet();
                WritableArray data = Arguments.createArray();
                for (int keyStart = 0; keyStart < keys.size(); keyStart += MAX_SQL_KEYS) {
                    int keyCount = Math.min(keys.size() - keyStart, MAX_SQL_KEYS);
                    Cursor cursor = mStateXDatabaseSupplier.get().query(
                            TABLE_STATE,
                            columns,
                            AsyncLocalStorageUtil.buildKeySelection(keyCount),
                            AsyncLocalStorageUtil.buildKeySelectionArgs(keys, keyStart, keyCount),
                            null,
                            null,
                            null);
                    keysRemaining.clear();
                    try {
                        if (cursor.getCount() != keys.size()) {
                            // some keys have not been found - insert them with null into the final array
                            for (int keyIndex = keyStart; keyIndex < keyStart + keyCount; keyIndex++) {
                                keysRemaining.add(keys.getString(keyIndex));
                            }
                        }

                        if (cursor.moveToFirst()) {
                            do {
                                WritableArray row = Arguments.createArray();
                                row.pushString(cursor.getString(0));
                                row.pushString(cursor.getString(1));
                                data.pushArray(row);
                                keysRemaining.remove(cursor.getString(0));
                            } while (cursor.moveToNext());
                        }
                    } catch (Exception e) {
                        FLog.w(ReactConstants.TAG, e.getMessage(), e);
                        callback.invoke(AsyncStorageErrorUtil.getError(null, e.getMessage()), null);
                        return;
                    } finally {
                        cursor.close();
                    }

                    for (String key : keysRemaining) {
                        WritableArray row = Arguments.createArray();
                        row.pushString(key);
                        row.pushNull();
                        data.pushArray(row);
                    }
                    keysRemaining.clear();
                }

                callback.invoke(null, data);
            }
        }.execute();
    }

    /**
     * Inserts multiple (key, value) pairs. If one or more of the pairs cannot be inserted, this will
     * return StateXFailure, but all other pairs will have been inserted.
     * The insertion will replace conflicting (key, value) pairs.
     */
    @ReactMethod
    public void multiSet(final ReadableArray keyValueArray, final Callback callback) {
        if (keyValueArray.size() == 0) {
            callback.invoke(AsyncStorageErrorUtil.getInvalidKeyError(null));
            return;
        }

        new GuardedAsyncTask<Void, Void>(getReactApplicationContext()) {
            @Override
            protected void doInBackgroundGuarded(Void... params) {
                if (!ensureDatabase()) {
                    callback.invoke(AsyncStorageErrorUtil.getDBError(null));
                    return;
                }

                String sql = "INSERT OR REPLACE INTO " + TABLE_STATE + " VALUES (?, ?);";
                SQLiteStatement statement = mStateXDatabaseSupplier.get().compileStatement(sql);
                WritableMap error = null;
                try {
                    mStateXDatabaseSupplier.get().beginTransaction();
                    for (int idx = 0; idx < keyValueArray.size(); idx++) {
                        if (keyValueArray.getArray(idx).size() != 2) {
                            error = AsyncStorageErrorUtil.getInvalidValueError(null);
                            return;
                        }
                        if (keyValueArray.getArray(idx).getString(0) == null) {
                            error = AsyncStorageErrorUtil.getInvalidKeyError(null);
                            return;
                        }
                        if (keyValueArray.getArray(idx).getString(1) == null) {
                            error = AsyncStorageErrorUtil.getInvalidValueError(null);
                            return;
                        }

                        statement.clearBindings();
                        statement.bindString(1, keyValueArray.getArray(idx).getString(0));
                        statement.bindString(2, keyValueArray.getArray(idx).getString(1));
                        statement.execute();
                    }
                    mStateXDatabaseSupplier.get().setTransactionSuccessful();
                } catch (Exception e) {
                    FLog.w(ReactConstants.TAG, e.getMessage(), e);
                    error = AsyncStorageErrorUtil.getError(null, e.getMessage());
                } finally {
                    try {
                        mStateXDatabaseSupplier.get().endTransaction();
                    } catch (Exception e) {
                        FLog.w(ReactConstants.TAG, e.getMessage(), e);
                        if (error == null) {
                            error = AsyncStorageErrorUtil.getError(null, e.getMessage());
                        }
                    }
                }
                if (error != null) {
                    callback.invoke(error);
                } else {
                    callback.invoke();
                }
            }
        }.execute();
    }

    /**
     * Removes all rows of the keys given.
     */
    @ReactMethod
    public void multiRemove(final ReadableArray keys, final Callback callback) {
        if (keys.size() == 0) {
            callback.invoke(AsyncStorageErrorUtil.getInvalidKeyError(null));
            return;
        }

        new GuardedAsyncTask<Void, Void>(getReactApplicationContext()) {
            @Override
            protected void doInBackgroundGuarded(Void... params) {
                if (!ensureDatabase()) {
                    callback.invoke(AsyncStorageErrorUtil.getDBError(null));
                    return;
                }

                WritableMap error = null;
                try {
                    mStateXDatabaseSupplier.get().beginTransaction();
                    for (int keyStart = 0; keyStart < keys.size(); keyStart += MAX_SQL_KEYS) {
                        int keyCount = Math.min(keys.size() - keyStart, MAX_SQL_KEYS);
                        mStateXDatabaseSupplier.get().delete(
                                TABLE_STATE,
                                AsyncLocalStorageUtil.buildKeySelection(keyCount),
                                AsyncLocalStorageUtil.buildKeySelectionArgs(keys, keyStart, keyCount));
                    }
                    mStateXDatabaseSupplier.get().setTransactionSuccessful();
                } catch (Exception e) {
                    FLog.w(ReactConstants.TAG, e.getMessage(), e);
                    error = AsyncStorageErrorUtil.getError(null, e.getMessage());
                } finally {
                    try {
                        mStateXDatabaseSupplier.get().endTransaction();
                    } catch (Exception e) {
                        FLog.w(ReactConstants.TAG, e.getMessage(), e);
                        if (error == null) {
                            error = AsyncStorageErrorUtil.getError(null, e.getMessage());
                        }
                    }
                }
                if (error != null) {
                    callback.invoke(error);
                } else {
                    callback.invoke();
                }
            }
        }.execute();
    }

    /**
     * Given an array of (key, value) pairs, this will merge the given values with the stored values
     * of the given keys, if they exist.
     */
    @ReactMethod
    public void multiMerge(final ReadableArray keyValueArray, final Callback callback) {
        new GuardedAsyncTask<Void, Void>(getReactApplicationContext()) {
            @Override
            protected void doInBackgroundGuarded(Void... params) {
                if (!ensureDatabase()) {
                    callback.invoke(AsyncStorageErrorUtil.getDBError(null));
                    return;
                }
                WritableMap error = null;
                try {
                    mStateXDatabaseSupplier.get().beginTransaction();
                    for (int idx = 0; idx < keyValueArray.size(); idx++) {
                        if (keyValueArray.getArray(idx).size() != 2) {
                            error = AsyncStorageErrorUtil.getInvalidValueError(null);
                            return;
                        }

                        if (keyValueArray.getArray(idx).getString(0) == null) {
                            error = AsyncStorageErrorUtil.getInvalidKeyError(null);
                            return;
                        }

                        if (keyValueArray.getArray(idx).getString(1) == null) {
                            error = AsyncStorageErrorUtil.getInvalidValueError(null);
                            return;
                        }

                        if (!AsyncLocalStorageUtil.mergeImpl(
                                mStateXDatabaseSupplier.get(),
                                keyValueArray.getArray(idx).getString(0),
                                keyValueArray.getArray(idx).getString(1))) {
                            error = AsyncStorageErrorUtil.getDBError(null);
                            return;
                        }
                    }
                    mStateXDatabaseSupplier.get().setTransactionSuccessful();
                } catch (Exception e) {
                    FLog.w(ReactConstants.TAG, e.getMessage(), e);
                    error = AsyncStorageErrorUtil.getError(null, e.getMessage());
                } finally {
                    try {
                        mStateXDatabaseSupplier.get().endTransaction();
                    } catch (Exception e) {
                        FLog.w(ReactConstants.TAG, e.getMessage(), e);
                        if (error == null) {
                            error = AsyncStorageErrorUtil.getError(null, e.getMessage());
                        }
                    }
                }
                if (error != null) {
                    callback.invoke(error);
                } else {
                    callback.invoke();
                }
            }
        }.execute();
    }

    /**
     * Clears the database.
     */
    @ReactMethod
    public void clear(final Callback callback) {
        new GuardedAsyncTask<Void, Void>(getReactApplicationContext()) {
            @Override
            protected void doInBackgroundGuarded(Void... params) {
                if (!mStateXDatabaseSupplier.ensureDatabase()) {
                    callback.invoke(AsyncStorageErrorUtil.getDBError(null));
                    return;
                }
                try {
                    mStateXDatabaseSupplier.get().delete(TABLE_STATE, null, null);
                    callback.invoke();
                } catch (Exception e) {
                    FLog.w(ReactConstants.TAG, e.getMessage(), e);
                    callback.invoke(AsyncStorageErrorUtil.getError(null, e.getMessage()));
                }
            }
        }.execute();
    }

    /**
     * Returns an array with all keys from the database.
     */
    @ReactMethod
    public void getAllKeys(final Callback callback) {
        new GuardedAsyncTask<Void, Void>(getReactApplicationContext()) {
            @Override
            protected void doInBackgroundGuarded(Void... params) {
                if (!ensureDatabase()) {
                    callback.invoke(AsyncStorageErrorUtil.getDBError(null), null);
                    return;
                }
                WritableArray data = Arguments.createArray();
                String[] columns = {KEY_COLUMN};
                Cursor cursor = mStateXDatabaseSupplier.get()
                        .query(TABLE_STATE, columns, null, null, null, null, null);
                try {
                    if (cursor.moveToFirst()) {
                        do {
                            data.pushString(cursor.getString(0));
                        } while (cursor.moveToNext());
                    }
                } catch (Exception e) {
                    FLog.w(ReactConstants.TAG, e.getMessage(), e);
                    callback.invoke(AsyncStorageErrorUtil.getError(null, e.getMessage()), null);
                    return;
                } finally {
                    cursor.close();
                }
                callback.invoke(null, data);
            }
        }.execute();
    }

    /**
     * Verify the database is open for reads and writes.
     */
    private boolean ensureDatabase() {
        return !mShuttingDown && mStateXDatabaseSupplier.ensureDatabase();
    }
}


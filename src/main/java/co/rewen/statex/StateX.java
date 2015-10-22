package co.rewen.statex;

import android.net.Uri;

import com.facebook.react.bridge.ReadableArray;

import java.util.ArrayList;

/**
 * Copyright (c) 2015-present, Junjun Deng
 * All rights reserved.
 * <p/>
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
public class StateX {
    public static final String ACTION_STATE_CHANGED = "co.rewen.intent.action.STATE_CHANGED";
    public static final String EXTRA_KEYS = "co.rewen.intent.extra.key";

    public static final String SCHEME = "state";
    public static final String AUTHORITY = "co.rewen";

    public static Uri uriForKey(String key) {
        Uri.Builder builder = new Uri.Builder();
        builder.scheme(SCHEME);
        builder.authority(AUTHORITY);
        builder.path(key);
        return builder.build();
    }

    public static ArrayList<String> toStringArray(ReadableArray array) {
        ArrayList<String> strings = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            strings.add(array.getString(i));
        }
        return strings;
    }
}

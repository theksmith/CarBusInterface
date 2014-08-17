package com.theksmith.android.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.theksmith.android.car_bus_interface.BuildConfig;


/**
 * static helper class for managing application-wide state (non-user-defined preferences)
 * these items persist between app launches
 * suggested to use strings_class_app_state.xml for items keys (example entry: <string name="app_state_b_some_item">app_state_b_some_item</string>)
 *
 * @author Kristoffer Smith <kristoffer@theksmith.com>
 */
public class AppState {
    private static final String TAG = "AppState";
    private static final boolean DD = BuildConfig.SHOW_DEBUG_LOG_LEVEL > 1;

    private final static String APP_STATE_PREFS_NAME = "APP_STATE";


    private static SharedPreferences getPreferences(Context context) {
        if (DD) Log.d(TAG, "getPreferences()");

        return context.getApplicationContext().getSharedPreferences(APP_STATE_PREFS_NAME, Context.MODE_MULTI_PROCESS);
    }

    /**
     * gets the value of an application-wide state item
     *
     * @param appContext  the application context
     * @param keyResId  a resource identifier from strings_class_app_state.xml (example: R.string.app_state_b_some_item)
     * @param defaultValue  the default value to return if the key is not found
     * @return  the stored value or the provided default
     */
    public static String getString(final Context appContext, final int keyResId, final String defaultValue) {
        if (DD) Log.d(TAG, "getPreferences()");

        return getPreferences(appContext).getString(appContext.getString(keyResId), defaultValue);
    }

    /**
     * set the value of an application-wide state item
     *
     * @param appContext  the application context
     * @param keyResId  a resource identifier from strings_class_app_state.xml (example: R.string.app_state_b_some_item)
     * @param value  the value to store
     */
    public static void setString(final Context appContext, final int keyResId, final String value) {
        if (DD) Log.d(TAG, "getPreferences()");

        getPreferences(appContext).edit().putString(appContext.getString(keyResId), value).apply();
    }

    /**
     * like AppState.getString() for Booleans
     */
    public static boolean getBoolean(final Context appContext, final int keyResId, final boolean defaultValue) {
        if (DD) Log.d(TAG, "getPreferences()");

        return getPreferences(appContext).getBoolean(appContext.getString(keyResId), defaultValue);
    }

    /**
     * like AppState.setString() for Booleans
     */
    public static void setBoolean(final Context appContext, final int keyResId, final boolean value) {
        if (DD) Log.d(TAG, "getPreferences()");

        getPreferences(appContext).edit().putBoolean(appContext.getString(keyResId), value).apply();
    }

    //todo: implement get/set methods for other data types as needed
}

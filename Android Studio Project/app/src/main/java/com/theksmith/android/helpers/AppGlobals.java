package com.theksmith.android.helpers;

import android.content.Context;
import android.util.Log;

import com.theksmith.android.car_bus_interface.BuildConfig;

import java.util.HashMap;


/**
 * singleton helper class for managing application-wide (global) variables
 * these items do NOT persist between app launches
 * suggested to use strings_class_app_globals.xml for items keys (example entry: <string name="app_global_b_some_item">app_global_b_some_item</string>)
 *
 * Created by KLS on 8/17/14.
 */
public class AppGlobals {
    private static final String TAG = "App";
    private static final boolean DD = BuildConfig.SHOW_DEBUG_LOG_LEVEL > 1;

    private static AppGlobals mInstance = null;
    private final Context mAppContext;

    private static final HashMap<String, Object> mGlobalVars = new HashMap<String, Object>();


    private AppGlobals(final Context appContext) {
        if (DD) Log.d(TAG, "AppGlobals()");

        mAppContext = appContext.getApplicationContext();
    }

    public static AppGlobals getInstance(final Context appContext) {
        if (DD) Log.d(TAG, "getInstance()");

        if (mInstance == null) {
            mInstance = new AppGlobals(appContext);
        }
        return mInstance;
    }

    /**
     * gets the value of an application-wide global variable item
     *
     * @param keyResId  a resource identifier from strings_class_app_globals.xml (example: R.string.app_global_b_some_item)
     * @return  the stored value
     */
    public Object globalVarGet(final int keyResId) {
        if (DD) Log.d(TAG, "globalVarGet()");

        return mGlobalVars.get(mAppContext.getString(keyResId));
    }

    /**
     * sets the value of an application-wide global variable item
     *
     * @param keyResId  a resource identifier from strings_class_app_globals.xml (example: R.string.app_global_b_some_item)
     * @param value  the value to store
     */
    public void globalVarSet(final int keyResId, final Object value) {
        if (DD) Log.d(TAG, "globalVarSet()");

        mGlobalVars.put(mAppContext.getString(keyResId), value);
    }
}

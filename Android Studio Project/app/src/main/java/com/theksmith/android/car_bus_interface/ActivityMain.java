package com.theksmith.android.car_bus_interface;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.theksmith.android.helpers.AppGlobals;
import com.theksmith.android.helpers.AppState;


/**
 * main entry point Activity, has no UI, only starts the background service by default
 * kills background service (exiting app) if called with Intent.ACTION_DELETE
 * restarts service on Intent.ACTION_REBOOT
 * opens settings screen on Intent.ACTION_EDIT
 *
 * @author Kristoffer Smith <kristoffer@theksmith.com>
 */
public class ActivityMain extends Activity {
    private static final String TAG = "ActivityMain";
    private static final boolean D = BuildConfig.SHOW_DEBUG_LOG_LEVEL > 0;

    private static final int ACTIVITY_ID_SETTINGS = 1;


    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppGlobals globals = AppGlobals.getInstance(getApplicationContext());

        appCheckForFirstRun();

        final String action = getIntent().getAction();

        if (D) Log.d(TAG, "onCreate() : action= " + action);

        if (action.equals(Intent.ACTION_EDIT)) {
            //ensure the service is still running
            serviceMainStart();

            activitySettingsShow();
        } else if (action.equals(Intent.ACTION_REBOOT)) {
            serviceMainKill();

            activitySettingsKill();

            serviceMainStart();
        } else if (action.equals(Intent.ACTION_DELETE)) {
            serviceMainKill();

            activitySettingsKill();
        } else {
            //in certain scenarios onCreate() may fire instead of onResume() even though the app is "alive"
            //if the app really is just launching the global var should be null
            final Boolean isRunning = (Boolean) globals.get(R.string.app_global_b_app_is_running);
            if (isRunning != null && isRunning) {
                //this is a really "resume" instead of a "launch" so show the settings screen
                activitySettingsShow();
            }

            serviceMainStart();
        }

        //use the global var to mark the app as "alive"
        globals.set(R.string.app_global_b_app_is_running, true);

        //exit this activity
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (D) Log.d(TAG, "onResume()");

        activitySettingsShow();

        //ensure the service is still running
        serviceMainStart();

        //exit this activity
        finish();
    }

    private void appCheckForFirstRun() {
        if (D) Log.d(TAG, "appCheckForFirstRun()");

        final boolean firstRunCompleted = AppState.getBoolean(getApplicationContext(), R.string.app_state_b_first_run_completed, false);

        if (!firstRunCompleted) {
            //todo: add any application first run (fresh install) tasks here

            AppState.setBoolean(getApplicationContext(), R.string.app_state_b_first_run_completed, true);
        }
    }

    private void serviceMainStart() {
        if (D) Log.d(TAG, "serviceMainStart()");

        //ensure the service is started (does NOT create duplicate if already running)
        startService(new Intent(getApplicationContext(), ServiceMain.class));
    }

    private void serviceMainKill() {
        if (D) Log.d(TAG, "serviceMainKill()");

        //kill the service
        stopService(new Intent(getApplicationContext(), ServiceMain.class));
    }

    private void activitySettingsShow() {
        if (D) Log.d(TAG, "activitySettingsShow()");

        //show the settings screen
        startActivityForResult(new Intent(getApplicationContext(), ActvitySettings.class), ACTIVITY_ID_SETTINGS);
    }

    private void activitySettingsKill() {
        if (D) Log.d(TAG, "activitySettingsKill()");

        finishActivity(ACTIVITY_ID_SETTINGS);
    }
}
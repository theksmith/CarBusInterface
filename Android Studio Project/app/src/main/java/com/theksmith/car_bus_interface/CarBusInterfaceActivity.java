package com.theksmith.car_bus_interface;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;


/**
 * main entry point Activity, has no UI, just starts the background service by default
 * kills background service (exiting app) if called with Intent.ACTION_DELETE
 * restarts service on Intent.ACTION_REBOOT
 * opens settings screen on Intent.ACTION_EDIT
 *
 * @author Kristoffer Smith <kristoffer@theksmith.com>
 */
public class CarBusInterfaceActivity extends Activity {
    private static final String TAG = "CarBusInterfaceActivity";
    private static final boolean D = BuildConfig.SHOW_DEBUG_LOG;


    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final String action = getIntent().getAction();

        if (D) Log.d(TAG, "onCreate() : action= " + action);

        if (action.equals(Intent.ACTION_EDIT)) {
            //show the settings screen
            startActivityForResult(new Intent(getBaseContext(), CarBusInterfaceSettings.class), 0);
        } else if (action.equals(Intent.ACTION_REBOOT)) {
            final Intent service = new Intent(getBaseContext(), CarBusInterfaceService.class);

            //kill the service
            stopService(service);

            //ensure settings screen is killed
            finishActivity(0);

            //restart the service
            startService(service);

            //exit main activity
            finish();
        } else if (action.equals(Intent.ACTION_DELETE)) {
            //kill the service
            stopService(new Intent(getBaseContext(), CarBusInterfaceService.class));

            //ensure settings screen is killed
            finishActivity(0);

            //exit main activity
            finish();
        } else {
            //start the service
            startService(new Intent(getBaseContext(), CarBusInterfaceService.class));

            //exit main activity
            finish();
        }
    }
}

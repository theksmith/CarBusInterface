package com.theksmith.android.car_bus_interface;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;


/**
 * a BroadcastReceiver to listen for bluetooth on/off even if the app has not been launched (declared in the manifest)
 * does not do anything unless the automatic app launch/close setting is checked
 *
 * @author Kristoffer Smith <kristoffer@theksmith.com>
 */
public class BluetoothBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "BluetoothBroadcastReceiver";
    private static final boolean D = BuildConfig.SHOW_DEBUG_LOG_LEVEL > 0;


    public BluetoothBroadcastReceiver() {

    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        final boolean automatic = settings.getBoolean("auto_launch", false);

        if (D) Log.d(TAG, "onReceive() : automatic= " + automatic);

        if (automatic) {
            if (intent.getAction().equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);

                if (D) Log.d(TAG, "onReceive() : state= " + state);

                final Intent i = new Intent(context, CBIServiceMain.class);

                if (state == BluetoothAdapter.STATE_ON) {
                    context.stopService(i);
                    context.startService(i);
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    context.stopService(i);
                }
            }
        }
    }
}

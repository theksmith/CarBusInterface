package com.theksmith.car_bus_interface;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.util.Log;


/**
 * a simple PreferenceActivity, the app's only UI
 * this is shown when user taps on the persistent notification
 *
 * @author Kristoffer Smith <kristoffer@theksmith.com>
 */
public class CarBusInterfaceSettings extends PreferenceActivity {
    private static final String TAG = "CarBusInterfaceSettings";
    private static final boolean D = BuildConfig.SHOW_DEBUG_LOG;


    @Override
    protected boolean isValidFragment(final String ignored) {
        return true;
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (D) Log.d(TAG, "onCreate()");

        //set title to app name + version
        try {
            final PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            setTitle(getString(R.string.app_name) + " " + info.versionName);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "onCreate() : failed to get version : exception= " + e.getMessage(), e);
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        setupSimplePreferencesScreen();
    }

    @Override
    public boolean onIsMultiPane() {
        return false;
    }

    private void setupSimplePreferencesScreen() {
        addPreferencesFromResource(R.xml.pref_general);

        //action type prefs acting as buttons

        final Preference prefRestart = findPreference("action_restart");
        prefRestart.setOnPreferenceClickListener(mPrefOnClickListener);

        final Preference prefExit = findPreference("action_exit");
        prefExit.setOnPreferenceClickListener(mPrefOnClickListener);

        //bind values to summaries for list and string type prefs

        bindPreferenceSummaryToValue(findPreference("bluetooth_mac"));
        bindPreferenceSummaryToValue(findPreference("elm_commands"));

        //todo: the way we are storing these preferences is a quick hack, we need a custom preference screen to configure any number of these
        for (int m = 1; m <= 10; m++) {
            bindPreferenceSummaryToValue(findPreference("elm_monitor" + m));
        }
    }

    private Preference.OnPreferenceClickListener mPrefOnClickListener = new Preference.OnPreferenceClickListener() {
        @Override
        public boolean onPreferenceClick(final Preference preference) {
            finish();
            return false;
        }
    };

    private static void bindPreferenceSummaryToValue(final Preference preference) {
        preference.setOnPreferenceChangeListener(sBindPreferenceSummaryToValueListener);
        sBindPreferenceSummaryToValueListener.onPreferenceChange(preference, PreferenceManager.getDefaultSharedPreferences(preference.getContext()).getString(preference.getKey(), ""));
    }

    private static Preference.OnPreferenceChangeListener sBindPreferenceSummaryToValueListener = new Preference.OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(final Preference preference, final Object value) {
            final String stringValue = value.toString();

            if (preference instanceof ListPreference) {
                final ListPreference listPreference = (ListPreference) preference;
                final int index = listPreference.findIndexOfValue(stringValue);
                preference.setSummary(index >= 0 ? listPreference.getEntries()[index] : null);
            } else {
                preference.setSummary(stringValue);
            }

            return true;
        }
    };
}

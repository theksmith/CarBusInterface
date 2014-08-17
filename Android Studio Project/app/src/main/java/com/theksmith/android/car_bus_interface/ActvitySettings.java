package com.theksmith.android.car_bus_interface;

import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.util.Log;


/**
 * a Settings screen PreferenceActivity, the app's primary UI
 * this is shown when user taps on the persistent notification created by ServiceMain
 *
 * @author Kristoffer Smith <kristoffer@theksmith.com>
 */
public class ActvitySettings extends PreferenceActivity {
    private static final String TAG = "ActvitySettings";
    private static final boolean D = BuildConfig.SHOW_DEBUG_LOG_LEVEL > 0;


    @Override
    protected boolean isValidFragment(final String ignored) {
        return true;
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        if (D) Log.d(TAG, "onCreate()");

        super.onCreate(savedInstanceState);

        setTitle(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME + " " + getString(R.string.app_breadcrumb_delimiter) + " " + getTitle());
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        if (D) Log.d(TAG, "onPostCreate()");

        super.onPostCreate(savedInstanceState);

        setupSimplePreferencesScreen();
    }

    @Override
    public boolean onIsMultiPane() {
        return false;
    }

    private void setupSimplePreferencesScreen() {
        if (D) Log.d(TAG, "setupSimplePreferencesScreen()");

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
            if (D) Log.d(TAG, "onPreferenceClick()");

            finish();
            return false;
        }
    };

    private static void bindPreferenceSummaryToValue(final Preference preference) {
        if (D) Log.d(TAG, "bindPreferenceSummaryToValue()");

        preference.setOnPreferenceChangeListener(sBindPreferenceSummaryToValueListener);
        sBindPreferenceSummaryToValueListener.onPreferenceChange(preference, PreferenceManager.getDefaultSharedPreferences(preference.getContext()).getString(preference.getKey(), ""));
    }

    private static Preference.OnPreferenceChangeListener sBindPreferenceSummaryToValueListener = new Preference.OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(final Preference preference, final Object value) {
            if (D) Log.d(TAG, "onPreferenceChange()");

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

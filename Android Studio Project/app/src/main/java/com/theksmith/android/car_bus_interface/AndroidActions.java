package com.theksmith.android.car_bus_interface;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import net.dinglisch.android.tasker.TaskerIntent;


/**
 * singleton helper class for performing common actions on android device
 *
 * @author Kristoffer Smith <kristoffer@theksmith.com>
 */
public class AndroidActions {
    private static final String TAG = "AndroidActions";
    private static final boolean D = BuildConfig.SHOW_DEBUG_LOG_LEVEL > 0;

    private static AndroidActions mInstance = null;

    private final Context mAppContext;
    private final boolean mSilentErrors;

    private final String mAppName;


    private AndroidActions(final Context appContext, boolean silentErrors) {
        if (D) Log.d(TAG, "AndroidActions()");

        mAppContext = appContext.getApplicationContext();
        mSilentErrors = silentErrors;

        mAppName = appContext.getApplicationInfo().name;
    }

    public static AndroidActions getInstance(final Context appContext, final boolean silentErrors) {
        if (D) Log.d(TAG, "getInstance()");

        if (mInstance == null) {
            mInstance = new AndroidActions(appContext, silentErrors);
        }
        return mInstance;
    }

    /**
     * send a very basic implicit intent (useful for operations like opening a URL, dialing a phone number, etc.)
     * @param action  full string representation of the intent action, examples: "android.intent.action.DIAL", "android.intent.action.VIEW", etc.
     * @param uri  the intent data URI string, examples: "tel:123", "http://google.com", etc.
     */
    public void sysSendImplicitIntent(final String action, final Uri uri) {
        if (D) Log.d(TAG, "sysSendImplicitIntent() : action= " + action + " uri= " + uri.toString());

        new Handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = new Intent(action, uri);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mAppContext.startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "sysSendImplicitIntent() : unexpected exception : exception= " + e.getMessage(), e);

                    if (!mSilentErrors) {
                        final String text = mAppName + ": " + mAppContext.getString(R.string.msg_error_implicit_intent) + " " + action + " / " + uri;
                        Toast.makeText(mAppContext, text, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
    }

    /**
     * show a toast notification
     * @param text  the data to show
     */
    public void sysAlert(final String text) {
        if (D) Log.d(TAG, "sysAlert() : text= " + text);

        new Handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(mAppContext, text, Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * execute a shell command
     * @param command  the command string. if root is required, begin with "su -c"
     */
    public void sysExecuteCommand(final String command) {
        if (D) Log.d(TAG, "sysExecuteCommand() : command= " + command);

        new Handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Runtime.getRuntime().exec(command);
                } catch (Exception e) {
                    Log.e(TAG, "sysExecuteCommand() : unexpected exception : exception= " + e.getMessage(), e);

                    if (!mSilentErrors) {
                        final String text = mAppName + ": " + mAppContext.getString(R.string.msg_error_executing_command) + " " + command;
                        Toast.makeText(mAppContext, text, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
    }

    /**
     * simulate a device/keyboard button (requires root)
     * @param keyCode  one of the android.view.KeyEvent.KEYCODE_* constants
     */
    public void sysSimulateButton(final int keyCode) {
        if (D) Log.d(TAG, "sysSimulateButton() : keyCode= " + keyCode);

        sysExecuteCommand("su -c input keyevent " + keyCode);
    }

    /**
     * simulate a MEDIA device/keyboard button via root or non-root method
     * @param keyCode  any of the android.view.KeyEvent.KEYCODE_MEDIA_* constants (must be a MEDIA one)
     * @param useRootMethod  root method works most consistently, otherwise may not work correctly when multiple media players are present (gives focus to the default one)
     */
    public void sysSimulateMediaButton(final int keyCode, final boolean useRootMethod) {
        if (D) Log.d(TAG, "sysSimulateMediaButton() : keyCode= " + keyCode + " useRootMethod= " + useRootMethod);

        if (useRootMethod) {
            try {
                sysSimulateButton(keyCode);
            } catch (Exception ignored) {}
        } else {
            new Handler().post(new Runnable() {
                @Override
                public void run() {
                    try{
                        final long now = SystemClock.uptimeMillis();

                        Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
                        KeyEvent event = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
                        intent.putExtra(Intent.EXTRA_KEY_EVENT, event);
                        mAppContext.sendOrderedBroadcast(intent, null);

                        intent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
                        event = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0);
                        intent.putExtra(Intent.EXTRA_KEY_EVENT, event);
                        mAppContext.sendOrderedBroadcast(intent, null);
                    } catch (Exception e) {
                        Log.e(TAG, "sysSimulateMediaButton() : unexpected exception : exception= " + e.getMessage(), e);

                        if (!mSilentErrors) {
                            final String text = mAppName + ": " + mAppContext.getString(R.string.msg_error_simulating_media_btn) + " " + keyCode;
                            Toast.makeText(mAppContext, text, Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });
        }
    }

    public void audioVolumeUp(final boolean visible) {
        if (D) Log.d(TAG, "audioVolumeUp() : visible= " + visible);

        new Handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    final AudioManager mAudioManager = (AudioManager) mAppContext.getSystemService(Context.AUDIO_SERVICE);
                    mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, (visible ? AudioManager.FLAG_SHOW_UI : 0));
                } catch (Exception e) {
                    Log.e(TAG, "audioVolumeUp() : unexpected exception : exception= " + e.getMessage(), e);

                    if (!mSilentErrors) {
                        final String text = mAppName + ": " + mAppContext.getString(R.string.msg_error_changing_volume);
                        Toast.makeText(mAppContext, text, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
    }

    public void audioVolumeDown(final boolean visible) {
        if (D) Log.d(TAG, "audioVolumeDown() : visible= " + visible);

        new Handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    final AudioManager mAudioManager = (AudioManager) mAppContext.getSystemService(Context.AUDIO_SERVICE);
                    mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, (visible ? AudioManager.FLAG_SHOW_UI : 0));
                } catch (Exception e) {
                    Log.e(TAG, "audioVolumeDown() : unexpected exception : exception= " + e.getMessage(), e);

                    if (!mSilentErrors) {
                        final String text = mAppName + ": " + mAppContext.getString(R.string.msg_error_changing_volume);
                        Toast.makeText(mAppContext, text, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
    }

    /**
     * attempts to execute a Tasker task (fails silently if Tasker is disabled, not installed, or if the task does not exist)
     * @param task  the exact name of the Tasker task
     * @param params  values of any params will be available to the Tasker task in variables %par1, %par2, etc.
     */
    public void taskerExecuteTask(final String task, final String[] params) {
        if (D) Log.d(TAG, "taskerExecuteTask() : task= " + task + " params.length= " + params.length);

        new Handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    TaskerIntent intent = new TaskerIntent(task);

                    for (String param : params) {
                        intent.addParameter(param);
                    }

                    mAppContext.sendBroadcast(intent);
                } catch (Exception e) {
                    Log.e(TAG, "taskerExecuteTask() : unexpected exception : exception= " + e.getMessage(), e);

                    if (!mSilentErrors) {
                        final String text = mAppName + ": " + mAppContext.getString(R.string.msg_error_tasker) + " " + task;
                        Toast.makeText(mAppContext, text, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
    }
}

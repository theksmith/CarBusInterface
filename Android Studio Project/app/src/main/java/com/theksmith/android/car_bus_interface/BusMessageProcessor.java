package com.theksmith.android.car_bus_interface;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;


/**
 * a Thread class for logging occurrences of a particular bus msg and responding to them intelligently
 * this is only needed if you have repeating bus messages and want to ignore possible bounces or identify a long versus short group of repeats (such as for a button press)
 *
 * @author Kristoffer Smith <kristoffer@theksmith.com>
 */
public class BusMessageProcessor extends Thread {
    private static final String TAG = "BusMessageProcessor";
    private static final boolean D = BuildConfig.SHOW_DEBUG_LOG_LEVEL > 0;
    private static final boolean DD = BuildConfig.SHOW_DEBUG_LOG_LEVEL > 1;

    private volatile boolean mCancelling;

    private final Context mAppContext;

    private final String mMessage;
    private volatile ArrayList<MessageEvent> mEvents;

    private final boolean mSilenceErrors;

    private final long mTimeToIgnoreAfterAction;
    private final long mMinTimeToGroupAsShort;
    private final long mMinTimeToGroupAsLong;
    private final long mMaxTimeToWatchForLong;

    private final boolean mRespondToEveryEvent;

    private final String mActionForShort;
    private final String mActionForLong;

    private Handler mProcessorHandler;

    private static long PROCESSOR_TICK_TIME = 15; //milliseconds

    private static final String ACTION_PARAM_SEPARATOR_REGEX = "\\*\\*";

    private static final String ACTION_ALERT = "*ALERT=";
    private static final String ACTION_VOLUME = "*VOLUME=";
    private static final String ACTION_VOLUME_HIDDEN = "*VOLUME_HIDDEN=";
    private static final String ACTION_MEDIA_BUTTON = "*MEDIA_BUTTON=";
    private static final String ACTION_BUTTON_ROOT = "*BUTTON_ROOT=";
    private static final String ACTION_INTENT_BASIC = "*INTENT=";
    private static final String ACTION_TASKER = "*TASKER=";

    private final AndroidActions mActionsHelper;


    private static enum EventType {
        UNKNOWN, IGNORED, SHORT, LONG
    }

    private class MessageEvent {
        public long time = 0;
        public EventType type = EventType.UNKNOWN;
        public boolean shouldActOn = false;
        public boolean didActOn = false;
    }


    public BusMessageProcessor(final Context appContext, final String message, final boolean silenceErrors, final long timeToIgnoreRepeatsAfterAction, final long minTimeToGroupRepeatsAsShort, final long minTimeToGroupRepeatsAsLong, final long maxTimeToWatchForLong, final String actionForShortOrAll, final String actionForLong) throws IllegalArgumentException {
        if (D) Log.d(TAG, "BusMessageProcessor() : data= " + message);

        if (timeToIgnoreRepeatsAfterAction > 0 && timeToIgnoreRepeatsAfterAction <= PROCESSOR_TICK_TIME) {
            throw new IllegalArgumentException("BusMessageProcessor() : timeToIgnoreRepeatsAfterAction > 0 but < PROCESSOR_TICK_TIME (" + PROCESSOR_TICK_TIME + ")");
        }

        if (minTimeToGroupRepeatsAsShort > 0 && minTimeToGroupRepeatsAsShort <= PROCESSOR_TICK_TIME) {
            throw new IllegalArgumentException("BusMessageProcessor() : minTimeToGroupRepeatsAsShort > 0 but < PROCESSOR_TICK_TIME (" + PROCESSOR_TICK_TIME + ")");
        }

        if (minTimeToGroupRepeatsAsLong > 0 && minTimeToGroupRepeatsAsLong <= PROCESSOR_TICK_TIME) {
            throw new IllegalArgumentException("BusMessageProcessor() : minTimeToGroupRepeatsAsLong > 0 but < PROCESSOR_TICK_TIME (" + PROCESSOR_TICK_TIME + ")");
        }

        mAppContext = appContext;

        mMessage = message;

        mSilenceErrors = silenceErrors;

        mTimeToIgnoreAfterAction = timeToIgnoreRepeatsAfterAction;
        mMinTimeToGroupAsShort = minTimeToGroupRepeatsAsShort;
        mMinTimeToGroupAsLong = minTimeToGroupRepeatsAsLong;
        mMaxTimeToWatchForLong = maxTimeToWatchForLong;

        if (mTimeToIgnoreAfterAction <=0 && mMinTimeToGroupAsShort <= 0 && mMinTimeToGroupAsLong <= 0) {
            //special case of all time settings zero, then respond to every event with whatever action is defined for a SHORT
            mRespondToEveryEvent = true;
        } else {
            mRespondToEveryEvent = false;
        }

        mActionForShort = actionForShortOrAll;
        mActionForLong = actionForLong;

        mActionsHelper = AndroidActions.getInstance(mAppContext, mSilenceErrors);

        //initialize the event log
        initLog();
    }

    @Override
    public void run() {
        if (D) Log.d(TAG, "run()");

        if (mCancelling || mEvents == null) {
            return;
        }

        Looper.prepare();

        mProcessorHandler = new Handler();
        mProcessorHandler.postDelayed(mProcessor, PROCESSOR_TICK_TIME);

        Looper.loop();
    }

    public void cancel() {
        if (D) Log.d(TAG, "cancel()");

        mCancelling = true;

        if (mProcessorHandler != null) {
            mProcessorHandler.removeCallbacks(mProcessor);
        }

        mEvents = null;
    }

    public synchronized void logEvent() {
        if (D) Log.d(TAG, "logEvent() : (this.mMessage= " + this.mMessage + ")");


        if (mCancelling || mEvents == null) {
            return;
        }

        final MessageEvent event = new MessageEvent();
        event.time = SystemClock.uptimeMillis();
        event.type = EventType.UNKNOWN;
        event.shouldActOn = false;
        event.didActOn = false;
        mEvents.add(event);
    }

    private synchronized void initLog() {
        if (D) Log.d(TAG, "logClear() : (this.mMessage= " + this.mMessage + ")");

        if (mCancelling) {
            return;
        }

        if (mEvents == null) {
            mEvents = new ArrayList<MessageEvent>();
        }

        //seed the log with an IGNORED event with time=0 and didActOn=true
        //this way all the standard logic in analyzeLatestEvent() will just work
        final MessageEvent first = new MessageEvent();
        first.time = 0;
        first.type = EventType.IGNORED;
        first.shouldActOn = false;
        first.didActOn = true;
        mEvents.add(first);
    }

    private synchronized void trimLog(final int indexToKeep) {
        if (D) Log.d(TAG, "trimLog() : indexToKeep= " + indexToKeep);

        if (mCancelling || mEvents == null) {
            return;
        }

        mEvents.subList(0, indexToKeep).clear();
    }

    private synchronized Integer analyzeLatestEvent() {
        if (mCancelling || mEvents == null || mEvents.size() <= 0) {
            //cancelling or for some reason we have no events
            if (DD) Log.d(TAG, "analyzeLatestEvent() : exit path A");
            return null;
        }

        try {
            long now = SystemClock.uptimeMillis();

            final int latestIndex = mEvents.size() - 1;
            final MessageEvent latestEvent = mEvents.get(latestIndex);

            if (latestEvent.type != EventType.UNKNOWN) {
                //already analyzed this event to a positive conclusion, just return it's index
                if (DD) Log.d(TAG, "analyzeLatestEvent() : exit path B");
                return latestIndex;
            }

            if (mRespondToEveryEvent) {
                //responding to every event, no need for logic, mark it a SHORT
                if (DD) Log.d(TAG, "analyzeLatestEvent() : logic path 1");
                latestEvent.type = EventType.SHORT;
            } else {
                //gather info needed for main analysis logic

                long spanNowToLatestActedUpon = 0;
                long spanNowToFirstUnknownSinceLatestActedUpon = 0;
                int countUnknownsSinceLatestActedUpon = 0;
                long spanMaxBetweenUnknownsSinceLatestActedUpon = 0;

                MessageEvent latestActedUpon = null;
                MessageEvent latestUnknown = null;
                MessageEvent firstUnknownSinceLatestActedUpon = null;
                for (int i = 0; i <= latestIndex; i++) {
                    try {
                        latestActedUpon = mEvents.get(latestIndex - i);

                        if (latestActedUpon.type == EventType.UNKNOWN) {
                            countUnknownsSinceLatestActedUpon++;

                            if (latestUnknown == null) {
                                latestUnknown = latestActedUpon;
                            }

                            firstUnknownSinceLatestActedUpon = latestActedUpon;
                            spanNowToFirstUnknownSinceLatestActedUpon = now - firstUnknownSinceLatestActedUpon.time;
                            spanMaxBetweenUnknownsSinceLatestActedUpon = latestUnknown.time - firstUnknownSinceLatestActedUpon.time;
                        }

                        if (latestActedUpon.didActOn) {
                            spanNowToLatestActedUpon = now - latestActedUpon.time;
                            break;
                        }
                    } catch (IndexOutOfBoundsException ignored) {
                        break;
                    }
                }

                //if (DD) Log.d(TAG, "analyzeLatestEvent() : spanNowToLatestActedUpon=" + spanNowToLatestActedUpon + " - countUnknownsSinceLatestActedUpon=" + countUnknownsSinceLatestActedUpon + " - spanNowToFirstUnknownSinceLatestActedUpon=" + spanNowToFirstUnknownSinceLatestActedUpon + " - spanMaxBetweenUnknownsSinceLatestActedUpon=" + spanMaxBetweenUnknownsSinceLatestActedUpon);

                //begin main analysis logic

                if (mTimeToIgnoreAfterAction > 0 && spanNowToLatestActedUpon <= mTimeToIgnoreAfterAction) {
                    //this is an ignore period and we are still within that period, mark it to IGNORE
                    if (DD) Log.d(TAG, "analyzeLatestEvent() : logic path 2");
                    latestEvent.type = EventType.IGNORED;
                } else {
                    //the possibility of an IGNORE has been eliminated

                    if (mMinTimeToGroupAsLong > 0) {
                        //the possibility of an IGNORE has been eliminated

                        if (mMinTimeToGroupAsShort > 0) {
                            //the possibility of an IGNORE has been eliminated
                            //must be watching for a LONG or SHORT

                            if (spanNowToFirstUnknownSinceLatestActedUpon >= mMaxTimeToWatchForLong) {
                                //the LONG watch time was met so must be a LONG or SHORT

                                if (countUnknownsSinceLatestActedUpon > 1) {
                                    //there were multiple UNKNOWN

                                    if (spanMaxBetweenUnknownsSinceLatestActedUpon >= mMinTimeToGroupAsLong) {
                                        //the distance between the multiple UNKNOWN meets the LONG requirement
                                        if (DD) Log.d(TAG, "analyzeLatestEvent() : logic path 3");
                                        latestEvent.type = EventType.LONG;
                                    } else {
                                        //the distance between the multiple UNKNOWN did not meet the LONG requirement
                                        //due to timeout it has to be a SHORT (it may or may not have met the SHORT requirement though)
                                        if (DD) Log.d(TAG, "analyzeLatestEvent() : logic path 4");
                                        latestEvent.type = EventType.SHORT;
                                    }
                                } else {
                                    //there was one or less UNKNOWN so it can't meet the LONG requirement
                                    //due to timeout it has to be a SHORT
                                    if (DD) Log.d(TAG, "analyzeLatestEvent() : logic path 5");
                                    latestEvent.type = EventType.SHORT;
                                }
                            } else {
                                //keep watching for the LONG or SHORT
                                if (DD) Log.d(TAG, "analyzeLatestEvent() : logic path 6");
                                latestEvent.type = EventType.UNKNOWN;
                            }
                        } else {
                            //the possibility of an IGNORE or a SHORT has been eliminated
                            //must be watching for a LONG

                            if (spanNowToFirstUnknownSinceLatestActedUpon >= mMinTimeToGroupAsLong && countUnknownsSinceLatestActedUpon > 0) {
                                //the LONG time was met and there was at least one UNKNOWN that could have been the LONG
                                //we didn't need to wait till the max LONG watch time since we were only watching for LONG
                                if (DD) Log.d(TAG, "analyzeLatestEvent() : logic path 7");
                                latestEvent.type = EventType.LONG;
                            } else {
                                //keep watching for the LONG
                                if (DD) Log.d(TAG, "analyzeLatestEvent() : logic path 8");
                                latestEvent.type = EventType.UNKNOWN;
                            }
                        }
                    } else {
                        //the possibility of an IGNORE or a LONG has been eliminated
                        //must be watching for a SHORT

                        if (spanNowToFirstUnknownSinceLatestActedUpon >= mMinTimeToGroupAsShort && countUnknownsSinceLatestActedUpon > 0) {
                            //the SHORT time was met and there was at least one UNKNOWN that could have been the SHORT
                            //we didn't need to wait till the max SHORT watch time since we were only watching for SHORT
                            if (DD) Log.d(TAG, "analyzeLatestEvent() : logic path 9");
                            latestEvent.type = EventType.SHORT;
                        } else {
                            //keep watching for the SHORT
                            if (DD) Log.d(TAG, "analyzeLatestEvent() : logic path 10");
                            latestEvent.type = EventType.UNKNOWN;
                        }
                    }
                }
            }

            if (latestEvent.type != EventType.IGNORED && latestEvent.type != EventType.UNKNOWN) {
                //we found an event type that should be acted upon
                latestEvent.shouldActOn = true;
            }

            //update the event's info in the log
            mEvents.set(latestIndex, latestEvent);

            if (DD) Log.d(TAG, "analyzeLatestEvent() : exit path C");
            return latestIndex;
        } catch (Exception e) {
            Log.e(TAG, "analyzeLatestEvent() : unexpected exception : exception= " + e.getMessage(), e);
        }

        Log.e(TAG, "analyzeLatestEvent() : unexpected code path encountered");
        return null;
    }

    private void doAction(EventType type) {
        if (D) Log.d(TAG, "doAction() : type= " + type);

        if ((mRespondToEveryEvent || type == EventType.SHORT) && mActionForShort != null && !mActionForShort.equals("")) {
            doAction(mActionForShort);
        } else if (type == EventType.LONG && mActionForLong != null && !mActionForLong.equals("")) {
            doAction(mActionForLong);
        }
    }

    private void doAction(final String action) {
        if (D) Log.d(TAG, "doAction() : action= " + action);

        try {
            String[] args = action.split("=", 2);
            if (args.length == 2) {
                args = args[1].split(ACTION_PARAM_SEPARATOR_REGEX);
            }

            for (int a = 0; a < args.length; a++) {
                args[a] = args[a].trim();
            }

            if (action.contains(ACTION_VOLUME) || action.contains(ACTION_VOLUME_HIDDEN)) {
                final boolean visible = action.contains(ACTION_VOLUME);

                if (args[0].equals("UP")) {
                    mActionsHelper.audioVolumeUp(visible);
                } else if (args[0].equals("DOWN")) {
                    mActionsHelper.audioVolumeDown(visible);
                } else {
                    throw new IllegalArgumentException("Only supports value UP or DOWN");
                }
            } else if (action.contains(ACTION_ALERT)) {
                mActionsHelper.sysAlert(args[0]);
            } else if (action.contains(ACTION_MEDIA_BUTTON)) {
                mActionsHelper.sysSimulateMediaButton(KeyEvent.keyCodeFromString(args[0]), false);
            } else if (action.contains(ACTION_BUTTON_ROOT)) {
                mActionsHelper.sysSimulateButton(KeyEvent.keyCodeFromString(args[0]));
            } else if (action.contains(ACTION_INTENT_BASIC)) {
                final Uri uri = args.length == 2 ? Uri.parse(args[1]) : Uri.EMPTY;
                mActionsHelper.sysSendImplicitIntent(args[0], uri);
            } else if (action.contains(ACTION_TASKER)) {
                mActionsHelper.taskerExecuteTask(args[0], Arrays.copyOfRange(args, 1, args.length));
            } else {
                mActionsHelper.sysExecuteCommand(action);
            }
        } catch (Exception e) {
            Log.e(TAG, "doAction() : failed to execute action : exception= " + e.getMessage(), e);

            if (!mSilenceErrors) {
                new Handler().post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final String text = mAppContext.getApplicationInfo().name + ": " + mAppContext.getString(R.string.msg_error_attempting_action) + " " + action;
                            Toast.makeText(mAppContext, text, Toast.LENGTH_SHORT).show();
                        } catch (Exception ignored) {}
                    }
                });
            }
        }
    }

    private Runnable mProcessor = new Runnable() {
        @Override
        public void run() {
            if (!BusMessageProcessor.this.mCancelling) {
                try {
                    final Integer latestIndex = analyzeLatestEvent();

                    if (latestIndex != null) {
                        final MessageEvent latestEvent = BusMessageProcessor.this.mEvents.get(latestIndex);

                        if (DD) Log.d(TAG, "mProcessor.run() : latestEvent.time= " + latestEvent.time + " latestEvent.type= " + latestEvent.type);

                        if (latestEvent.shouldActOn && !latestEvent.didActOn) {
                            //do the action
                            BusMessageProcessor.this.doAction(latestEvent.type);

                            //mark it as acted upon in the event log
                            latestEvent.didActOn = true;
                            BusMessageProcessor.this.mEvents.set(latestIndex, latestEvent);

                            trimLog(latestIndex);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "mProcessor.run() : unexpected exception : exception= " + e.getMessage(), e);
                }

                //schedule next run
                mProcessorHandler.postDelayed(this, BusMessageProcessor.PROCESSOR_TICK_TIME);
            }
        }
    };
}
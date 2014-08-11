package com.theksmith.car_bus_interface;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.TaskStackBuilder;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/*
todo: need improvements with error handling...
after certain number of connection attempts should we give up? (i.e. don't keep trying to re-connect forever)
if connecting ok, but not receiving for a certain time period, maybe should try a reset (ATZ) at some point? (must have a preference for if/when as some cars might not send anything for long periods)
*/

/**
 * primary foreground Service which runs even after main activity is destroyed
 * attempts to connect with the BT OBD2 device, send commands, and then listens and processes any responses
 *
 * @author Kristoffer Smith <kristoffer@theksmith.com>
 */
public class CarBusInterfaceService extends Service {
    private static final String TAG = "CarBusInterfaceService";
    private static final boolean D = BuildConfig.SHOW_DEBUG_LOG;

    private SharedPreferences mSettings;

    private static final int PERSISTENT_NOTIFICATION_ID = 0;

    private NotificationManager mNoticeManager;
    private final Notification.Builder mNoticeBuilder;

    private String mNoticeStatus;
    private String mNoticeError;

    private static enum BTState {
        DESTROYING, NONE, CONNECTING, CONNECTED, RX, TX
    }

    private volatile BTState mBTState;

    private volatile BluetoothAdapter mBTAdapter;
    private BTConnectThread mBTConnectThread;
    private BTIOThread mBTIOThread;

    private static final long BT_CONNECTION_RETRY_WAIT = 1000; //milliseconds

    //this matches the termination of a MESSAGE (could be multiple within a response) or the entire RESPONSE
    //explained: match "\r" or ">" at a minimum and also variations of " \r\n >" while also trying to trim out extra spaces, CRs, and LFs
    private static final String ELM_RESPONSE_SEPARATOR_REGEX = " *\\r+\\n* *\\r*\\n* *| *\\r*\\n* *> *\\r*\\n* *|>";

    //consider an entire RESPONSE complete when this string occurs
    private static final String ELM_RESPONSE_TERMINATOR = ">";

    private final static String ELM_COMMAND_TERMINATOR = "\r\n";
    private final static long ELM_COMMAND_QUEUE_BUSY_WAIT_TIME = 100; //milliseconds

    private String mELMResponseBuffer;
    private ELMCommandQueueThread mELMCommandQueueThread;

    protected HashMap<String, BusMessageProcessor> mBusMsgProcessors;


    public CarBusInterfaceService() {
        if (D) Log.d(TAG, "CarBusInterfaceService()");

        mBTAdapter = BluetoothAdapter.getDefaultAdapter();
        mNoticeBuilder = new Notification.Builder(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.e(TAG, "onBind() not supported!");

        throw new UnsupportedOperationException("onBind() not supported!");
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (D) Log.d(TAG, "onCreate()");

        mSettings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        final IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        this.registerReceiver(mBTStateReceiver, filter);

        final Intent intent = new Intent(this, CarBusInterfaceActivity.class);
        intent.setAction(Intent.ACTION_EDIT);
        final TaskStackBuilder stack = TaskStackBuilder.create(this);
        stack.addParentStack(CarBusInterfaceActivity.class);
        stack.addNextIntent(intent);
        PendingIntent resultPendingIntent = stack.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);

        mNoticeBuilder.setOngoing(true);
        mNoticeBuilder.setContentIntent(resultPendingIntent);
        mNoticeBuilder.setSmallIcon(R.drawable.ic_notice);
        mNoticeBuilder.setContentTitle(getString(R.string.app_name));
        mNoticeBuilder.setContentText(getString(R.string.msg_app_starting));

        mNoticeManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        mNoticeManager.notify(PERSISTENT_NOTIFICATION_ID, mNoticeBuilder.build());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (D) Log.d(TAG, "onDestroy()");

        mBTState = BTState.DESTROYING;
        this.unregisterReceiver(mBTStateReceiver);
        stop();
        mNoticeManager.cancelAll();
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        super.onStartCommand(intent, flags, startId);

        if (D) Log.d(TAG, "onStartCommand()");

        startForeground(PERSISTENT_NOTIFICATION_ID, mNoticeBuilder.build());

        start();

        return START_STICKY;
    }

    private synchronized void start() {
        if (D) Log.d(TAG, "start()");

        cancelAllThreads();

        if (mBTState == BTState.DESTROYING) {
            stopSelf();
            return;
        }

        if (mBTAdapter == null || !mBTAdapter.isEnabled()) {
            btNotEnabled();
            return;
        }

        final String address = mSettings.getString("bluetooth_mac", "");
        if (address.equals("")) {
            btNotConfigured();
            return;
        }

        mBTState = BTState.NONE;

        BluetoothDevice device = mBTAdapter.getRemoteDevice(address);
        btConnect(device);
    }

    private synchronized void stop() {
        if (D) Log.d(TAG, "stop()");

        cancelAllThreads();

        if (mBTState == BTState.DESTROYING) {
            stopSelf();
            return;
        }

        mBTState = BTState.NONE;
    }

    private synchronized void cancelAllThreads() {
        if (D) Log.d(TAG, "cancelAllThreads()");

        if (mBusMsgProcessors != null) {
            for (String msg : mBusMsgProcessors.keySet()) {
                BusMessageProcessor processor = mBusMsgProcessors.get(msg);
                if (processor != null) {
                    processor.cancel();
                }
            }
            mBusMsgProcessors = null;
        }

        if (mELMCommandQueueThread != null) {
            mELMCommandQueueThread.cancel();
            mELMCommandQueueThread = null;
        }

        if (mBTIOThread != null) {
            mBTIOThread.cancel();
            mBTIOThread = null;
        }

        if (mBTConnectThread != null) {
            mBTConnectThread.cancel();
            mBTConnectThread = null;
        }
    }


    /**
     * update the text for the persistent notification associated with this service
     *
     * @param status  status text, empty to clear or null to keep existing value
     * @param error  error text, empty to clear, or null to keep existing value
     */
    private synchronized void setNotificationText(final String status, final String error) {
        if (status != null) {
            mNoticeStatus = status;
        }

        if (error != null) {
            mNoticeError = error;
        }

        String text = mNoticeStatus == null ? "" : mNoticeStatus;
        if (mNoticeStatus != null & !mNoticeStatus.equals("") && mNoticeError != null & !mNoticeError.equals("")) {
            text += " | ";
        }
        text += mNoticeError == null ? "" : mNoticeError;

        mNoticeBuilder.setContentText(text);

        mNoticeManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        mNoticeManager.notify(PERSISTENT_NOTIFICATION_ID, mNoticeBuilder.build());
    }

    private synchronized void btConnect(final BluetoothDevice device) {
        if (D) Log.d(TAG, "btConnect()");

        if (mBTState == BTState.DESTROYING) {
            stopSelf();
            return;
        }

        mBTConnectThread = new BTConnectThread(device);
        mBTConnectThread.start();

        mBTState = BTState.CONNECTING;

        setNotificationText(getString(R.string.msg_app_connecting) + " " + device.getName() + "...", "");
    }

    private synchronized void btConnected(final BluetoothSocket socket, final BluetoothDevice device) {
        if (D) Log.d(TAG, "btConnected()");

        if (mBTState == BTState.DESTROYING) {
            stopSelf();
            return;
        }

        mBTConnectThread = null;

        mBTIOThread = new BTIOThread(socket);
        mBTIOThread.start();

        mBTState = BTState.CONNECTED;

        setNotificationText(getString(R.string.msg_app_connected) + " " + device.getName(), "");

        elmInit();
    }

    private synchronized void btReceivedData(final byte[] buffer, final int length) {
        if (D) Log.d(TAG, "btReceivedData()");

        mBTState = BTState.RX;
        elmBufferData(new String(buffer, 0, length));
    }

    private synchronized void btWriteData(final byte[] data) {
        if (D) Log.d(TAG, "btWriteData()");

        mBTState = BTState.TX;
        mBTIOThread.write(data);
    }

    private void btNotEnabled() {
        if (D) Log.d(TAG, "btNotEnabled()");

        setNotificationText(getString(R.string.msg_app_stopped), getString(R.string.msg_app_bt_not_enabled));
        stop();
    }

    private void btNotConfigured() {
        if (D) Log.d(TAG, "btNotConfigured()");

        setNotificationText(getString(R.string.msg_app_stopped), getString(R.string.msg_app_bt_not_configured));
        stop();
    }

    private void btConnectionFailed() {
        if (D) Log.d(TAG, "btConnectionFailed()");

        start();
    }

    private void btConnectionLost() {
        if (D) Log.d(TAG, "btConnectionLost()");

        start();
    }

    private final BroadcastReceiver mBTStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (intent.getAction().equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);

                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    btNotEnabled();
                } else if (state == BluetoothAdapter.STATE_ON) {
                    CarBusInterfaceService.this.start();
                }
            }
        }
    };

    private class BTConnectThread extends Thread {
        private volatile boolean mmCancelling;
        private volatile BluetoothSocket mmSocket;
        private volatile BluetoothDevice mmDevice;

        public BTConnectThread(final BluetoothDevice device) {
            if (D) Log.d(TAG, "BTConnectThread.BTConnectThread()");

            mmDevice = device;
            BluetoothSocket tmpSocket = null;

            try {
                /*
                //for some OBD2 dongles you need to specify a "channel"
                //the Android public API for a BluetoothDevice doesn't currently expose a socket creation method with that capability

                //so using createRfcommSocketToServiceRecord() with the standard SPP UUID does not work:
                UUID uuidSPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
                tmpSocket = mmDevice.createRfcommSocketToServiceRecord(uuidSPP);

                //nor even trying each of the devices advertised UUIDs
                for (ParcelUuid uuid : device.getUuids()) {
                    tmpSocket = mmDevice.createRfcommSocketToServiceRecord(uuid.getUuid());
                    sleep(3000);
                    if (tmpSocket.isConnected()) break;
                }

                //instead we use reflection to access the hidden public method createRfcommSocket()
                //channel 1 seems pretty universal with these type of devices

                //todo: should we try the documented methods for a BT socket first and then only resort to reflection as a backup?
                */
                final Method m = mmDevice.getClass().getMethod("createRfcommSocket", new Class[] {int.class});
                tmpSocket = (BluetoothSocket) m.invoke(mmDevice, 1);
            } catch (Exception e) {
                Log.w(TAG, "BTConnectThread.BTConnectThread() : failed to establish socket : exception= " + e.getMessage(), e);
            }

            mmSocket = tmpSocket;
        }

        @Override
        public void run() {
            if (D) Log.d(TAG, "BTConnectThread.run()");

            if (mmCancelling) {
                return;
            }

            try {
                mmSocket.connect();

                CarBusInterfaceService.this.btConnected(mmSocket, mmDevice);
            } catch (Exception e) {
                Log.w(TAG, "BTConnectThread.run() : failed to connect : exception= " + e.getMessage(), e);

                Thread.currentThread().interrupt();

                try {
                    mmSocket.close();
                } catch (Exception e2) {
                    Log.w(TAG, "BTConnectThread.run() : failed to close socket after connection failure : exception= " + e2.getMessage(), e2);
                }

                try {
                    sleep(CarBusInterfaceService.BT_CONNECTION_RETRY_WAIT);
                } catch (InterruptedException ignored) {}

                CarBusInterfaceService.this.btConnectionFailed();
                return;
            }
        }

        public void cancel() {
            if (D) Log.d(TAG, "BTConnectThread.cancel()");

            mmCancelling = true;

            try {
                mmSocket.close();
            } catch (Exception e) {
                Log.w(TAG, "BTConnectThread.cancel() : failed to close socket : exception= " + e.getMessage(), e);
            }
        }
    }

    private class BTIOThread extends Thread {
        private volatile boolean mmCancelling;
        private volatile BluetoothSocket mmSocket;
        private volatile InputStream mmInStream;
        private volatile OutputStream mmOutStream;

        public BTIOThread(final BluetoothSocket socket) {
            if (D) Log.d(TAG, "BTIOThread.BTIOThread()");

            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = mmSocket.getInputStream();
                tmpOut = mmSocket.getOutputStream();
            } catch (Exception e) {
                Log.w(TAG, "BTIOThread.BTIOThread() : failed to obtain io streams : exception= " + e.getMessage(), e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }


        @Override
        public void run() {
            if (D) Log.d(TAG, "BTIOThread.run()");

            byte[] buffer = new byte[1024];
            int length;

            while (!mmCancelling && mmInStream != null) {
                try {
                    length = mmInStream.read(buffer);
                    CarBusInterfaceService.this.btReceivedData(buffer.clone(), length);
                } catch (Exception e) {
                    Log.w(TAG, "BTIOThread.run() : exception while reading : exception= " + e.getMessage(), e);

                    Thread.currentThread().interrupt();

                    CarBusInterfaceService.this.btConnectionLost();
                    break;
                }
            }
        }

        public void write(final byte[] buffer) {
            if (D) Log.d(TAG, "BTIOThread.write()");

            if (mmCancelling) {
                return;
            }

            try {
                mmOutStream.write(buffer);
                mmOutStream.flush();
            } catch (Exception e) {
                Log.w(TAG, "BTIOThread.write() : exception while writing : exception= " + e.getMessage(), e);
            }
        }

        public void cancel() {
            if (D) Log.d(TAG, "BTIOThread.cancel()");

            mmCancelling = true;

            try {
                mmSocket.close();
            } catch (Exception e) {
                Log.w(TAG, "BTIOThread.cancel() : failed to close socket : exception= " + e.getMessage(), e);
            }
        }
    }

    private void elmBadConfig(final String noticeErrorText) {
        if (D) Log.d(TAG, "elmBadConfig()");

        setNotificationText(getString(R.string.msg_app_stopped), noticeErrorText);
        stop();
    }

    private synchronized void elmInit() {
        if (D) Log.d(TAG, "elmInit()");

        //setup monitors and their actions
        //todo: the way we are storing these preferences is a quick hack, we need a custom preference screen to configure any number of these

        /*
        FYI: you don't have to setup these processors if you don't need handle repeating messages (to skip bounces, identify short/long/double-press type scenarios, etc.)
        if you are hacking on this code you can just setup a case statement in the elmParseResponse() method to respond to messages as they come in
        */

        mBusMsgProcessors = new HashMap<String, BusMessageProcessor>();

        final Context appContext = getApplicationContext();

        BusMessageProcessor processor;
        String monitorSetting;
        String[] monitorArgs;

        String msg;
        boolean silenceErrors;
        long bounceTime;
        long shortTime;
        long longTime;
        long longWatchTime;
        String shortAction;
        String longAction;

        for (int m = 1; m <= 10; m++) {
            try {
                monitorSetting = mSettings.getString("elm_monitor" + m, "");
                if (!monitorSetting.equals("")) {
                    monitorArgs = monitorSetting.split("\\|");

                    msg = monitorArgs[0].trim();
                    silenceErrors = Boolean.parseBoolean(monitorArgs[1].trim());
                    bounceTime = Long.parseLong(monitorArgs[2].trim(), 10);
                    shortTime = Long.parseLong(monitorArgs[3].trim(), 10);
                    longTime = Long.parseLong(monitorArgs[4].trim(), 10);
                    longWatchTime = Long.parseLong(monitorArgs[5].trim(), 10);
                    shortAction = monitorArgs[6].trim();
                    longAction = monitorArgs[7].trim();

                    processor = new BusMessageProcessor(appContext, msg, silenceErrors, bounceTime, shortTime, longTime, longWatchTime, shortAction, longAction);
                    processor.start();
                    mBusMsgProcessors.put(msg, processor);
                }
            } catch (Exception e) {
                Log.w(TAG, "elmInit() : exception while setting up message processors : monitor #" + m + " : exception= " + e.getMessage(), e);

                elmBadConfig(getString(R.string.msg_app_elm_monitors_not_configured));
                return;
            }
        }

        if (mBusMsgProcessors == null || mBusMsgProcessors.size() <= 0) {
            Log.w(TAG, "elmInit() : no message processors");

            elmBadConfig(getString(R.string.msg_app_elm_monitors_not_configured));
            return;
        }

        //send startup commands

        final String prefElmCommands = mSettings.getString("elm_commands", "");
        if (prefElmCommands.equals("")) {
            Log.w(TAG, "elmInit() : no startup commands");

            elmBadConfig(getString(R.string.msg_app_elm_commands_not_configured));
            return;
        }

        final String[] commands = prefElmCommands.split("; *");
        if (commands.length <= 0) {
            Log.w(TAG, "elmInit() : invalid startup commands");

            elmBadConfig(getString(R.string.msg_app_elm_commands_not_configured));
            return;
        }

        for (String command : commands) {
            elmSendCommand(command.trim(), false);
        }
    }

    private synchronized void elmSendCommand(String command, final boolean immediate) {
        if (D) Log.d(TAG, "elmSendCommand() : command= " + command + " immediate= " + immediate);

        if (mELMCommandQueueThread == null) {
            mELMCommandQueueThread = new ELMCommandQueueThread();
            mELMCommandQueueThread.start();
        }

        if (immediate) {
            //we actually go ahead and send the command

            if (mBTState != BTState.CONNECTED && mBTState != BTState.RX && mBTState != BTState.TX) {
                Log.w(TAG, "elmSendCommand() : failed to send command (bluetooth not connected)");

                if (mELMCommandQueueThread != null) {
                    mELMCommandQueueThread.cancel();
                    mELMCommandQueueThread = null;
                }

                btConnectionLost();
                return;
            }

            if (mBTState != BTState.CONNECTED) {
                //if we are in the middle of an RX/TX need to also send an empty command first to abort the current operation
                command = ELM_COMMAND_TERMINATOR + command;
            }

            command += ELM_COMMAND_TERMINATOR;
            btWriteData(command.getBytes());
        } else {
            //normally we just queue the command
            mELMCommandQueueThread.add(command);
        }
    }

    private synchronized void elmBufferData(final String data) {
        if (D) Log.d(TAG, "elmBufferData() : data= " + data);

        if (mELMResponseBuffer == null) {
            mELMResponseBuffer = "";
        }

        final Pattern p = Pattern.compile(ELM_RESPONSE_SEPARATOR_REGEX);
        final Matcher m = p.matcher(data);

        if (m.find()) {
            //the data contained a separator or terminator
            final String part1 = data.substring(0, m.start());
            mELMResponseBuffer += part1;
            boolean completed = m.group().contains(ELM_RESPONSE_TERMINATOR);
            elmParseResponse(mELMResponseBuffer, completed);
            mELMResponseBuffer = "";

            final String part2 = data.substring(m.end());
            if (completed && part2.equals("")) {
                //the data was a clean end to a response (terminator with no trailing data)
                mBTState = BTState.CONNECTED;
            } else if (!part2.equals("")) {
                //the data continued after the separator or terminator
                elmBufferData(part2);
            }
        } else {
            //the response is not yet complete so keep appending data to the buffer
            mELMResponseBuffer += data;
        }
    }

    private synchronized void elmParseResponse(String response, final boolean completed) {
        response = response.replaceAll("[\\r\\n]", "");
        response = response.trim();

        if (D) Log.d(TAG, "elmParseResponse() : response= " + response + " completed= " + completed);

        /*
        FYI: here is where you would handle specific bus messages directly if you didn't need the BusMessageProcessor system
        */

        BusMessageProcessor processor = mBusMsgProcessors.get(response);
        if (processor != null) {
            processor.logEvent();
        }
    }

    private class ELMCommandQueueThread extends Thread {
        private volatile boolean mmCancelling;
        private volatile LinkedBlockingQueue<String> mmQueue;

        public ELMCommandQueueThread() {
            if (D) Log.d(TAG, "ELMCommandQueueThread.ELMCommandQueueThread()");

            mmQueue = new LinkedBlockingQueue<String>();
        }

        @Override
        public void run() {
            if (D) Log.d(TAG, "ELMCommandQueueThread.run()");

            try {
                String command;
                while (!mmCancelling) {
                    if (mBTState != CarBusInterfaceService.BTState.CONNECTED) {
                        //socket is busy with a TX or RX
                        try {
                            sleep(CarBusInterfaceService.ELM_COMMAND_QUEUE_BUSY_WAIT_TIME);
                        } catch (InterruptedException ignored) {}
                    } else {
                        command = mmQueue.take();
                        CarBusInterfaceService.this.elmSendCommand(command, true);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "ELMCommandQueueThread.run() : exception while processing queue : exception= " + e.getMessage(), e);

                Thread.currentThread().interrupt();
            }
        }

        public void add(final String command) {
            if (D) Log.d(TAG, "ELMCommandQueueThread.add() : command= " + command);

            if (mmCancelling) {
                return;
            }

            try {
                mmQueue.put(command);
            } catch (Exception e) {
                Log.w(TAG, "ELMCommandQueueThread.add() : failed to add to queue : exception= " + e.getMessage(), e);
            }
        }

        public void cancel() {
            if (D) Log.d(TAG, "ELMCommandQueueThread.cancel()");

            mmCancelling = true;
        }
    }
}

package com.theksmith.android.car_bus_interface;

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
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.theksmith.android.car_bus_interface.BusData.*;


/*
todo: improve handling of potential error conditions...

 -  if connected ok, but not receiving for a certain time period, maybe should try a reset (ATWS or ATZ) at some point?
    we would need a setting for if/when as some cars might not send anything for long periods
    otherwise is there a better way to check the real current health of the device/socket?

 -  after certain number of connection attempts should we give up? (i.e. don't keep trying to re-connect forever)

*/

/**
 * primary foreground Service which runs even after main activity is destroyed
 * attempts to connect with the bluetooth interface device, send commands, then listens and processes any responses
 *
 * @author Kristoffer Smith <kristoffer@theksmith.com>
 */
public class CBIServiceMain extends Service {
    private static final String TAG = "CBIServiceMain";
    private static final boolean D = BuildConfig.SHOW_DEBUG_LOG_LEVEL > 0;
    private static final boolean DD = BuildConfig.SHOW_DEBUG_LOG_LEVEL > 1;

    private final Messenger mBoundIncomingMessenger = new Messenger(new BoundIncomingHandler());
    private ArrayList<Messenger> mBoundClients = new ArrayList<Messenger>();

    public static final int BOUND_MSG_REGISTER_CLIENT = 1;
    public static final int BOUND_MSG_UNREGISTER_CLIENT = 2;
    public static final int BOUND_MSG_NOTIFY_BUS_DATA = 3;
    public static final int BOUND_MSG_SEND_BUS_COMMAND = 4;
    public static final int BOUND_MSG_SEND_STARTUP_COMMANDS = 5;

    private SharedPreferences mSettings;

    private static final int PERSISTENT_NOTIFICATION_ID = 0;

    private NotificationManager mNoticeManager;
    private Notification.Builder mNoticeBuilder;

    private String mNoticeStatus;
    private String mNoticeError;

    private static enum BTState {
        DESTROYING, NONE, CONNECTING, IDLE, RX, TX
    }

    private volatile BTState mBTState = BTState.NONE;

    private volatile BluetoothAdapter mBTAdapter;
    private BTConnectThread mBTConnectThread;
    private BTIOThread mBTIOThread;

    private static final long BT_CONNECTION_RETRY_WAIT = 2000; //milliseconds

    //this matches the termination of a MESSAGE (could be multiple within a response) or the entire RESPONSE
    //explained: match "\r" or ">" at a minimum and also variations of " \r\n >" while also trying to trim out extra spaces, CRs, and LFs
    private static final String ELM_RESPONSE_SEPARATOR_REGEX = " *\\r+\\n* *\\r*\\n* *| *\\r*\\n* *> *\\r*\\n* *|>";

    //consider an entire RESPONSE complete when this string occurs
    private static final String ELM_RESPONSE_TERMINATOR = ">";

    private final static String ELM_COMMAND_TERMINATOR = "\r\n";
    private final static long ELM_COMMAND_QUEUE_BUSY_WAIT_TIME = 100; //milliseconds

    private String mELMResponseBuffer;
    private ELMCommandQueueThread mELMCommandQueueThread;

    private HashMap<String, BusMessageProcessor> mBusMsgProcessors;


    public CBIServiceMain() {
        if (D) Log.d(TAG, "CBIServiceMain()");

        mBTAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (D) Log.d(TAG, "onCreate()");

        //create this as we need to get user preferences several times
        mSettings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        //setup a receiver to watch for bluetooth adapter state changes
        final IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        this.registerReceiver(mBTStateReceiver, filter);

        //setup the persistent notification as required for any service that returns START_STICKY

        final Intent intent = new Intent(this, CBIActivityMain.class);
        intent.setAction(Intent.ACTION_EDIT);
        final TaskStackBuilder stack = TaskStackBuilder.create(this);
        stack.addParentStack(CBIActivityMain.class);
        stack.addNextIntent(intent);
        PendingIntent resultPendingIntent = stack.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);

        mNoticeBuilder = new Notification.Builder(this);

        mNoticeBuilder.setOngoing(true);
        mNoticeBuilder.setPriority(Notification.PRIORITY_LOW);
        mNoticeBuilder.setContentIntent(resultPendingIntent);
        mNoticeBuilder.setSmallIcon(R.drawable.ic_notice);
        mNoticeBuilder.setContentTitle(getString(R.string.app_name));

        mNoticeStatus = getString(R.string.msg_starting);
        mNoticeBuilder.setContentText(mNoticeStatus);

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

        //multiple calls to startService() for this service shouldn't actually restart everything if it's already running smoothly
        if (!isBTConnected()) {
            start();
        }

        //this tells the system to keep the service running
        //it could still be killed due to low resources, but would automatically be re-started when possible
        //in that situation onStartCommand() is called with a null intent (if there are not already other pending start requests)
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (D) Log.d(TAG, "onBind()");

        return mBoundIncomingMessenger.getBinder();
    }

    private boolean isBound() {
        return mBoundClients != null && mBoundClients.size() > 0;
    }

    private void BoundNotifyBusData(final BusData data) {
        if (DD) Log.d(TAG, "BoundNotifyBusData() : data.data= " + data.data);

        if (isBound()) {
            for (int i = mBoundClients.size() - 1; i >= 0; i--) {
                try {
                    mBoundClients.get(i).send(Message.obtain(null, BOUND_MSG_NOTIFY_BUS_DATA, data));
                } catch (RemoteException e) {
                    //this client is no longer connected, remove it from the list
                    mBoundClients.remove(i);
                }
            }
        }
    }

    private void BoundNotifyNotReady() {
        BusData data = new BusData(getString(R.string.msg_error_bound_error_prefix) + " | " + getNotificationText(), BusDataType.ERROR, false);
        BoundNotifyBusData(data);
    }

    class BoundIncomingHandler extends Handler {
        @Override
        public void handleMessage(Message message) {
            if (D) Log.d(TAG, "BoundIncomingHandler : handleMessage() : msg.what= " + message.what);

            synchronized (CBIServiceMain.this) {
                switch (message.what) {
                    case BOUND_MSG_REGISTER_CLIENT:
                        mBoundClients.add(message.replyTo);
                        break;

                    case BOUND_MSG_UNREGISTER_CLIENT:
                        mBoundClients.remove(message.replyTo);
                        break;

                    case BOUND_MSG_SEND_BUS_COMMAND:
                        if (!isBTConnected()) {
                            BoundNotifyNotReady();
                        } else {
                            elmSendCommand(message.obj.toString());
                        }

                        break;

                    case BOUND_MSG_SEND_STARTUP_COMMANDS:
                        if (!isBTConnected()) {
                            BoundNotifyNotReady();
                        } else {
                            elmInitStartupCommands();
                        }

                        break;

                    default:
                        super.handleMessage(message);
                }
            }
        }
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

    private void cancelAllThreads() {
        if (D) Log.d(TAG, "cancelAllThreads()");

        if (mBTConnectThread != null) {
            mBTConnectThread.cancel();
            mBTConnectThread = null;
        }

        if (mBTIOThread != null) {
            mBTIOThread.cancel();
            mBTIOThread = null;
        }

        elmDestroyCommandQueue();

        if (mBusMsgProcessors != null) {
            for (String msg : mBusMsgProcessors.keySet()) {
                BusMessageProcessor processor = mBusMsgProcessors.get(msg);
                if (processor != null) {
                    processor.cancel();
                }
            }
            mBusMsgProcessors = null;
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

        mNoticeBuilder.setContentText(getNotificationText());

        mNoticeManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        mNoticeManager.notify(PERSISTENT_NOTIFICATION_ID, mNoticeBuilder.build());
    }

    private String getNotificationText() {
        String text = mNoticeStatus == null ? "" : mNoticeStatus;
        if (mNoticeStatus != null && !mNoticeStatus.equals("") && mNoticeError != null && !mNoticeError.equals("")) {
            text += " | ";
        }
        text += mNoticeError == null ? "" : mNoticeError;

        return text;
    }

    private synchronized boolean isBTConnected() {
        return mBTState == BTState.IDLE || mBTState == BTState.RX || mBTState == BTState.TX;
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

        setNotificationText(getString(R.string.msg_connecting) + " " + device.getName() + "...", "");
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

        mBTState = BTState.IDLE;

        setNotificationText(getString(R.string.msg_connected) + " " + device.getName(), "");

        elmInit();
    }

    private synchronized void btReceivedData(final byte[] buffer, final int length) {
        if (DD) Log.d(TAG, "btReceivedData()");

        //flag state as RX
        //this will only change back to IDLE once the RX is found to be _complete_
        mBTState = BTState.RX;

        elmBufferData(new String(buffer, 0, length));
    }

    private synchronized void btWriteData(final byte[] data) {
        if (D) Log.d(TAG, "btWriteData()");

        //flag state as TX
        //since we always expect some response from a command, this state will only change once an RX is received
        mBTState = BTState.TX;

        mBTIOThread.write(data);
    }

    private synchronized void btWriteBreak() {
        if (D) Log.d(TAG, "btWriteBreak()");

        //we don't use btWriteData() here as we don't want to set mBTState to TX since this special case may never have a corresponding complete RX event to return mBTState to IDLE
        final String data = String.valueOf((char)0x00);
        mBTIOThread.write(data.getBytes());

        //give the device time to realize the break before whatever called this method tries to continue
        SystemClock.sleep(250);
    }

    private void btNotEnabled() {
        if (D) Log.d(TAG, "btNotEnabled()");

        setNotificationText(getString(R.string.msg_stopped), getString(R.string.msg_bt_not_enabled));
        stop();
    }

    private void btNotPaired() {
        if (D) Log.d(TAG, "btNotEnabled()");

        setNotificationText(getString(R.string.msg_stopped), getString(R.string.msg_bt_not_paired));
        stop();
    }

    private void btNotConfigured() {
        if (D) Log.d(TAG, "btNotConfigured()");

        setNotificationText(getString(R.string.msg_stopped), getString(R.string.msg_bt_not_configured));
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
                if (D) Log.d(TAG, "mBTStateReceiver : onReceive()");

                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);

                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    btNotEnabled();
                } else if (state == BluetoothAdapter.STATE_ON) {
                    CBIServiceMain.this.start();
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
                if (mmDevice == null || mmDevice.getBondState() != BluetoothDevice.BOND_BONDED) {
                    btNotPaired();
                    return;
                }

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
                //doing this BEFORE the connect attempt should ensure that regardless of how we got here we don't try to re-connect too fast which can give the exception "RFCOMM_CreateConnection - already opened state:2, RFC state:4, MCB state:5"
                //we were only doing this in the catch below before the call to btConnectionFailed()
                SystemClock.sleep(CBIServiceMain.BT_CONNECTION_RETRY_WAIT);

                mmSocket.connect();

                CBIServiceMain.this.btConnected(mmSocket, mmDevice);
            } catch (Exception e) {
                Log.w(TAG, "BTConnectThread.run() : failed to connect : exception= " + e.getMessage(), e);

                if (mmSocket != null && mmSocket.isConnected()) {
                    try {
                        mmSocket.close();
                    } catch (Exception e2) {
                        Log.w(TAG, "BTConnectThread.run() : failed to close socket after connection failure : exception= " + e2.getMessage(), e2);
                    }
                }

                CBIServiceMain.this.btConnectionFailed();
            }
        }

        public void cancel() {
            if (D) Log.d(TAG, "BTConnectThread.cancel()");

            mmCancelling = true;

            if (mmSocket != null && mmSocket.isConnected()) {
                try {
                    mmSocket.close();
                } catch (Exception e) {
                    Log.w(TAG, "BTConnectThread.cancel() : failed to close socket : exception= " + e.getMessage(), e);
                }
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
                    //note: only performing the read if mmInStream.available() > 0 did NOT work reliably - long running RX operations would start returning 0 constantly after about a minute
                    length = mmInStream.read(buffer);
                    CBIServiceMain.this.btReceivedData(buffer.clone(), length);
                } catch (Exception e) {
                    Log.w(TAG, "BTIOThread.run() : exception while reading : exception= " + e.getMessage(), e);

                    CBIServiceMain.this.btConnectionLost();
                    return;
                }
            }

            if (!mmCancelling) {
                Log.w(TAG, "BTIOThread.run() : lost input stream");

                CBIServiceMain.this.btConnectionLost();
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

            if (mmSocket != null && mmSocket.isConnected()) {
                try {
                    mmSocket.close();
                } catch (Exception e) {
                    Log.w(TAG, "BTIOThread.cancel() : failed to close socket : exception= " + e.getMessage(), e);
                }
            }
        }
    }

    private void elmBadConfig(final String noticeErrorText) {
        if (D) Log.d(TAG, "elmBadConfig()");

        setNotificationText(getString(R.string.msg_stopped), noticeErrorText);
        stop();
    }

    private synchronized void elmInit() {
        if (D) Log.d(TAG, "elmInit()");

        /*
        FYI: you don't have to make this call to setup the processors if you don't need handle repeating messages (to skip bounces, identify short/long/double-press type scenarios, etc.)
        instead you could just setup a case statement in the elmParseResponse() method to respond to messages as they come in
        */
        elmInitBusMsgProcessors();

        elmInitStartupCommands();
    }

    private synchronized void elmInitBusMsgProcessors() {
        if (D) Log.d(TAG, "elmInitBusMsgProcessors()");

        //todo: the way we are storing these preferences is a quick hack, we need a custom preference screen to configure any number of these

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
                Log.w(TAG, "elmInit() : exception while setting up data processors : monitor #" + m + " : exception= " + e.getMessage(), e);

                elmBadConfig(getString(R.string.msg_bus_monitors_not_configured));
                return;
            }
        }

        if (mBusMsgProcessors == null || mBusMsgProcessors.size() <= 0) {
            Log.w(TAG, "elmInit() : no data processors");

            elmBadConfig(getString(R.string.msg_bus_monitors_not_configured));
        }
    }

    private synchronized void elmInitStartupCommands() {
        if (D) Log.d(TAG, "elmInitStartupCommands()");

        final String prefElmCommands = mSettings.getString("elm_commands", "");
        if (prefElmCommands.equals("")) {
            Log.w(TAG, "elmInit() : no startup commands");

            elmBadConfig(getString(R.string.msg_bus_commands_not_configured));
            return;
        }

        final String[] commands = prefElmCommands.split("; *");
        if (commands.length <= 0) {
            Log.w(TAG, "elmInit() : invalid startup commands");

            elmBadConfig(getString(R.string.msg_bus_commands_not_configured));
            return;
        }

        elmDestroyCommandQueue();
        btWriteBreak();

        for (String command : commands) {
            elmQueueCommand(command.trim());
        }
    }

    private synchronized void elmQueueCommand(String command) {
        if (D) Log.d(TAG, "elmQueueCommand() : command= " + command);

        if (command == null || command.equals("")) {
            return;
        }

        if (mELMCommandQueueThread == null) {
            mELMCommandQueueThread = new ELMCommandQueueThread();
            mELMCommandQueueThread.start();
        }

        mELMCommandQueueThread.add(command);
    }

    private synchronized void elmSendCommand(String command) {
        if (D) Log.d(TAG, "elmSendCommand() : command= " + command);

        if (command == null || command.equals("")) {
            return;
        }

        if (!isBTConnected()) {
            Log.w(TAG, "elmSendCommand() : failed to send command (bluetooth not connected)");

            btConnectionLost();
            return;
        }

        //alert any bound clients of this TX
        BusData data = new BusData(command, BusDataType.TX, false);
        BoundNotifyBusData(data);

        if (mBTState != BTState.IDLE) {
            //we are in the middle of something (like a long RX)
            btWriteBreak();
        }

        command += ELM_COMMAND_TERMINATOR;
        btWriteData(command.getBytes());
    }

    private void elmDestroyCommandQueue() {
        if (D) Log.d(TAG, "elmDestroyCommandQueue()");

        if (mELMCommandQueueThread != null) {
            mELMCommandQueueThread.cancel();
            mELMCommandQueueThread = null;
        }
    }

    private synchronized void elmBufferData(final String data) {
        if (DD) Log.d(TAG, "elmBufferData() : data= " + data);

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
                mBTState = BTState.IDLE;
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

        BusDataType messageType = BusDataType.RX;

        /*
        FYI: here is where you would handle specific bus messages directly if you didn't need the BusMessageProcessor system
        */

        BusMessageProcessor processor = mBusMsgProcessors.get(response);
        if (processor != null) {
            messageType = BusDataType.RX_MONITORED;
            processor.logEvent();
        }

        //alert any bound clients of this RX
        BusData data = new BusData(response, messageType, completed);
        BoundNotifyBusData(data);
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
                    if (mBTState != CBIServiceMain.BTState.IDLE) {
                        //socket is busy with a TX or RX
                        SystemClock.sleep(CBIServiceMain.ELM_COMMAND_QUEUE_BUSY_WAIT_TIME);
                    } else {
                        command = mmQueue.take();
                        CBIServiceMain.this.elmSendCommand(command);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "ELMCommandQueueThread.run() : exception while processing queue : exception= " + e.getMessage(), e);
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

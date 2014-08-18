package com.theksmith.android.car_bus_interface;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.text.Html;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import com.theksmith.android.helpers.AppState;

import java.text.SimpleDateFormat;
import java.util.Calendar;

import static com.theksmith.android.car_bus_interface.BusData.*;


/**
 * a debugging terminal screen Activity
 * binds to ServiceMain to allow monitoring bus data and sending bus commands
 * this is launched from within the Settings screen
 *
 * @author Kristoffer Smith <kristoffer@theksmith.com>
 */
public class ActivityTerminal extends Activity {
    private static final String TAG = "ActivityTerminal";
    private static final boolean D = BuildConfig.SHOW_DEBUG_LOG_LEVEL > 0;
    private static final boolean DD = BuildConfig.SHOW_DEBUG_LOG_LEVEL > 1;

    private MenuItem mMenuItemScroll;
    private MenuItem mMenuItemShowTime;
    private MenuItem mMenuItemShowElapsed;

    private TextView mTxtTerminal;
    private EditText mTxtCommand;
    private TextView mTxtAlertOverlay;

    private boolean mAutoScroll;
    private boolean mShowTime;
    private boolean mShowElapsed;

    private boolean mIsBound;

    private Messenger mServiceMainMessenger = null;
    private final Messenger mServiceMainIncomingMessenger = new Messenger(new ServiceMainHandler());

    private long mLastTerminalTime;

    private static final int MAX_TERMINAL_LINES = 1000;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (D) Log.d(TAG, "onCreate()");

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_terminal);

        setTitle(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME + " " + getString(R.string.app_breadcrumb_delimiter) + " " + getTitle());

        mTxtTerminal = (TextView) findViewById(R.id.txtTerminal);
        mTxtTerminal.setOnTouchListener(mTxtTerminal_OnTouchListener);

        mTxtCommand = (EditText) findViewById(R.id.txtCommand);
        mTxtCommand.setOnEditorActionListener(mTxtCommand_OnEditorActionListener);

        mTxtAlertOverlay = (TextView) findViewById(R.id.txtAlertOverlay);

        final Button btnSend = (Button) findViewById(R.id.btnSend);
        btnSend.setOnClickListener(mBtnSend_OnClickListener);
    }

    @Override
    protected void onStart() {
        if (D) Log.d(TAG, "onStart()");

        super.onStart();

        mTxtTerminal.setText("");

        serviceMainBind();
    }


    @Override
    protected void onDestroy() {
        if (D) Log.d(TAG, "onDestroy()");

        super.onDestroy();

        serviceMainUnBind();
    }

    @Override
    protected void onResume() {
        if (D) Log.d(TAG, "onResume()");

        super.onResume();

        //persist terminal contents
        String text = AppState.getString(getApplicationContext(), R.string.app_state_s_termninal_contents, "");
        mTxtTerminal.setText(Html.fromHtml(text));
    }

    @Override
    protected void onPause() {
        if (D) Log.d(TAG, "onPause()");

        super.onPause();

        //persist terminal contents
        SpannableString span = SpannableString.valueOf(mTxtTerminal.getText());
        AppState.setString(getApplicationContext(), R.string.app_state_s_termninal_contents, Html.toHtml(span));
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        if (D) Log.d(TAG, "onCreateOptionsMenu()");

        getMenuInflater().inflate(R.menu.actvity_terminal, menu);

        mMenuItemScroll = menu.findItem(R.id.action_scroll);
        toggleAutoScroll(mMenuItemScroll.isChecked());

        mMenuItemShowTime = menu.findItem(R.id.action_show_time);
        toggleShowTime(mMenuItemShowTime.isChecked());

        mMenuItemShowElapsed = menu.findItem(R.id.action_show_elapsed);
        toggleShowElapsed(mMenuItemShowElapsed.isChecked());

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (D) Log.d(TAG, "onOptionsItemSelected()");

        final int id = item.getItemId();

        if (id == R.id.action_scroll) {
            toggleAutoScroll(!item.isChecked());
            return true;
        } else if (id == R.id.action_show_time) {
            toggleShowTime(!item.isChecked());
            return true;
        } else if (id == R.id.action_show_elapsed) {
            toggleShowElapsed(!item.isChecked());
            return true;
        } else if (id == R.id.action_send_startup) {
            mTxtCommand.setText("");
            serviceMainSendStartupCommands();
            toggleAutoScroll(true);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private View.OnClickListener mBtnSend_OnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            if (D) Log.d(TAG, "mBtnSend_OnClickListener.onClick()");

            sendCommand();
        }
    };

    private EditText.OnEditorActionListener mTxtCommand_OnEditorActionListener = new TextView.OnEditorActionListener() {
        @Override
        public boolean onEditorAction(final TextView textView, final int actionId, final KeyEvent keyEvent) {
            if (D) Log.d(TAG, "mTxtCommand_OnEditorActionListener.onEditorAction()");

            //handle the the virtual keyboard send button or physical keyboard action/enter button
            if (actionId == EditorInfo.IME_ACTION_SEND || (actionId == EditorInfo.IME_NULL && keyEvent.getAction() == KeyEvent.ACTION_DOWN)) {
                sendCommand();
                return true;
            }

            return false;
        }
    };

    final TextView.OnTouchListener mTxtTerminal_OnTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent motionEvent) {
            if (DD) Log.d(TAG, "mTxtTerminal_OnTouchListener.onTouch()");

            toggleAutoScroll(false);
            return true;
        }
    };

    private void toggleAutoScroll(final boolean scroll) {
        if (D) Log.d(TAG, "toggleAutoScroll() : scroll= " + scroll);

        mAutoScroll = scroll;
        mMenuItemScroll.setChecked(mAutoScroll);

        if (mAutoScroll) {
            mTxtAlertOverlay.setVisibility(View.GONE);
            terminalScroll();
        } else {
            mTxtAlertOverlay.setText(getString(R.string.msg_alert_scroll_off));
            mTxtAlertOverlay.setVisibility(View.VISIBLE);
        }
    }

    private void toggleShowTime(final boolean show) {
        if (D) Log.d(TAG, "toggleShowTime() : show= " + show);

        mShowTime = show;
        mMenuItemShowTime.setChecked(mShowTime);
    }

    private void toggleShowElapsed(final boolean show) {
        if (D) Log.d(TAG, "toggleShowElapsed() : show= " + show);

        mShowElapsed = show;
        mMenuItemShowElapsed.setChecked(mShowElapsed);
    }

    private void terminalScroll() {
        if (DD) Log.d(TAG, "terminalScroll()");

        final ScrollView scrollView = (ScrollView) findViewById(R.id.scrollView);

        scrollView.post(new Runnable()
        {
            public void run()
            {
                scrollView.smoothScrollTo(0, mTxtTerminal.getBottom());
            }
        });
    }

    private void terminalAppend(final BusData data) {
        if (DD) Log.d(TAG, "terminalAppend()");

        //keep a rolling terminal of MAX_TERMINAL_LINES number of lines
        final int lines = mTxtTerminal.getLineCount();
        if (lines > MAX_TERMINAL_LINES) {
            StringBuilder trimmed = new StringBuilder();
            final String html = Html.toHtml(SpannableString.valueOf(mTxtTerminal.getText()));

            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter('\n');
            splitter.setString(html);

            int l = 0;
            for (final String line : splitter) {
                l++;
                if (l > (lines - MAX_TERMINAL_LINES)) {
                    trimmed.append(line);
                }
            }

            mTxtTerminal.setText(Html.fromHtml(trimmed.toString()));
        }

        //color code the data
        ForegroundColorSpan color = new ForegroundColorSpan(Color.GRAY);

        if (data.type == BusDataType.RX_MONITORED) {
            color = new ForegroundColorSpan(Color.GREEN);
        } else if (data.type == BusDataType.TX) {
            color = new ForegroundColorSpan(Color.WHITE);
        } else if (data.type == BusDataType.ERROR) {
            color = new ForegroundColorSpan(Color.RED);
        }

        //do timestamp and elapsed span stuff
        final Calendar now = Calendar.getInstance();

        String nowStamp = "";
        if (mShowTime) {
            final SimpleDateFormat formatter = new SimpleDateFormat("hh:mm:ss.SSS");
            nowStamp = formatter.format(now.getTime()) + " ";
        }

        String diffStamp = "";
        if (mShowElapsed) {
            final Calendar diff = Calendar.getInstance();
            if (mLastTerminalTime <= 0) {
                mLastTerminalTime = now.getTimeInMillis();
            }
            final long diffMillis = now.getTimeInMillis() - mLastTerminalTime;
            diff.setTimeInMillis(diffMillis);
            final SimpleDateFormat formatter = new SimpleDateFormat("mm:ss.SSS");
            diffStamp = "(" + formatter.format(diff.getTime()) + ") ";
        }

        mLastTerminalTime = now.getTimeInMillis();

        //assemble the final line and add to the terminal
        String message = nowStamp + diffStamp + data.data + "\n";
        SpannableString span = new SpannableString(message);
        span.setSpan(color, 0, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        mTxtTerminal.append(span);

        //if RX is complete, show a prompt marker
        if (data.rxComplete) {
            message = ">";
            span = new SpannableString(message);
            span.setSpan(Color.WHITE, 0, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            mTxtTerminal.append(span);
        }

        if (mAutoScroll) {
            terminalScroll();
        }
    }

    private void sendCommand() {
        if (D) Log.d(TAG, "sendCommand()");

        serviceMainSendBusCommand(mTxtCommand.getText().toString());

        mTxtCommand.setText("");

        toggleAutoScroll(true);
    }

    void serviceMainLostBinding() {
        if (D) Log.d(TAG, "serviceMainLostBinding()");

        BusData data = new BusData(getString(R.string.msg_error_terminal_lost_binding), BusDataType.ERROR, false);
        terminalAppend(data);

        toggleAutoScroll(true);
    }

    void serviceMainBind() {
        if (D) Log.d(TAG, "serviceMainBind()");

        bindService(new Intent(ActivityTerminal.this, ServiceMain.class), mServiceMainConnection, Context.BIND_AUTO_CREATE);
        mIsBound = true;
    }

    void serviceMainUnBind() {
        if (D) Log.d(TAG, "serviceMainUnBind()");

        if (mIsBound) {
            if (mServiceMainMessenger != null) {
                try {
                    Message message = Message.obtain(null, ServiceMain.BOUND_MSG_UNREGISTER_CLIENT);
                    message.replyTo = mServiceMainIncomingMessenger;
                    mServiceMainMessenger.send(message);
                } catch (RemoteException ignored) {}
            }

            unbindService(mServiceMainConnection);
            mIsBound = false;
        }
    }

    private final ServiceConnection mServiceMainConnection = new ServiceConnection() {
        public void onServiceConnected(final ComponentName className, final IBinder service) {
            if (D) Log.d(TAG, "mServiceMainConnection : onServiceConnected()");

            mServiceMainMessenger = new Messenger(service);

            try {
                Message message = Message.obtain(null, ServiceMain.BOUND_MSG_REGISTER_CLIENT);
                message.replyTo = mServiceMainIncomingMessenger;
                mServiceMainMessenger.send(message);
            } catch (RemoteException e) {
                serviceMainLostBinding();
            }
        }

        public void onServiceDisconnected(final ComponentName className) {
            if (D) Log.d(TAG, "mServiceMainConnection : onServiceDisconnected()");

            mServiceMainMessenger = null;
        }
    };

    class ServiceMainHandler extends Handler {
        @Override
        public void handleMessage(final Message message) {
            if (DD) Log.d(TAG, "ServiceMainHandler : handleMessage()");

            switch (message.what) {
                case ServiceMain.BOUND_MSG_NOTIFY_BUS_DATA:
                    if (message.obj != null) {
                        BusData data = (BusData) message.obj;
                        terminalAppend(data);
                    }
                    break;

                default:
                    super.handleMessage(message);
            }
        }
    }

    private void serviceMainSendBusCommand(final String command) {
        if (D) Log.d(TAG, "serviceMainSendBusCommand()");

        try {
            Message message = Message.obtain(null, ServiceMain.BOUND_MSG_SEND_BUS_COMMAND, command);
            message.replyTo = mServiceMainIncomingMessenger;
            mServiceMainMessenger.send(message);
        } catch (RemoteException e) {
            serviceMainLostBinding();
        }
    }

    private void serviceMainSendStartupCommands() {
        if (D) Log.d(TAG, "serviceMainSendStartupCommands()");

        try {
            Message message = Message.obtain(null, ServiceMain.BOUND_MSG_SEND_STARTUP_COMMANDS);
            message.replyTo = mServiceMainIncomingMessenger;
            mServiceMainMessenger.send(message);
        } catch (RemoteException e) {
            serviceMainLostBinding();
        }
    }
}

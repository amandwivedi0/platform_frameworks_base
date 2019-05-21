package com.android.systemui.statusbar.policy;

import java.text.DecimalFormat;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff.Mode;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.R;

/*
*
* Seeing how an Integer object in java requires at least 16 Bytes, it seemed awfully wasteful
* to only use it for a single boolean. 32-bits is plenty of room for what we need it to do.
*
*/
public class NetworkTraffic extends TextView {

    private static final int INTERVAL = 1500; //ms
    private static final int BOTH = 0;
    private static final int UP = 1;
    private static final int DOWN = 2;
    private static final int KB = 1024;
    private static final int MB = KB * KB;
    private static final int GB = MB * KB;
    private static final String symbol = "B/s";

    private static DecimalFormat decimalFormat = new DecimalFormat("##0.#");
    static {
        decimalFormat.setMaximumIntegerDigits(3);
        decimalFormat.setMaximumFractionDigits(1);
    }

    private boolean mIsEnabled;
    private boolean mAttached;
    private boolean mHideArrow;
    private long totalRxBytes;
    private long totalTxBytes;
    private long lastUpdateTime;
    private int txtSize;
    private int txtImgPadding;
    private int mTrafficType;
    private int mAutoHideThreshold;
    private int mTintColor;
    private boolean mColorIsStatic = false;
    private int mNetTrafSize = 21;

    private boolean mScreenOn = true;
    private boolean mTrafficInHeaderView;

    private Handler mTrafficHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            long timeDelta = SystemClock.elapsedRealtime() - lastUpdateTime;

            if (timeDelta < INTERVAL * .95) {
                if (msg.what != 1) {
                    // we just updated the view, nothing further to do
                    return;
                }
                if (timeDelta < 1) {
                    // Can't div by 0 so make sure the value displayed is minimal
                    timeDelta = Long.MAX_VALUE;
                }
            }
            lastUpdateTime = SystemClock.elapsedRealtime();

            // Calculate the data rate from the change in total bytes and time
            long newTotalRxBytes = TrafficStats.getTotalRxBytes();
            long newTotalTxBytes = TrafficStats.getTotalTxBytes();
            long rxData = newTotalRxBytes - totalRxBytes;
            long txData = newTotalTxBytes - totalTxBytes;

            if (shouldHide(rxData, txData, timeDelta)) {
                setText("");
                setVisibility(View.GONE);
            } else {
                String output;
                if (mTrafficType == UP){
                    output = formatOutput(timeDelta, txData, symbol);
                } else if (mTrafficType == DOWN){
                    output = formatOutput(timeDelta, rxData, symbol);
                } else {
                    // Get information for uplink ready so the line return can be added
                    output = formatOutput(timeDelta, txData, symbol);
                    // Ensure text size is where it needs to be
                    output += "\n";
                    // Add information for downlink if it's called for
                    output += formatOutput(timeDelta, rxData, symbol);
                }
                // Update view if there's anything new to show
                if (!output.contentEquals(getText())) {
                    setText(output);
                }
		updateTextSize();
                setVisibility(
                mTrafficInHeaderView ? View.GONE : View.VISIBLE);
            }

            // Post delayed message to refresh in ~1000ms
            totalRxBytes = newTotalRxBytes;
            totalTxBytes = newTotalTxBytes;
            clearHandlerCallbacks();
            mTrafficHandler.postDelayed(mRunnable, INTERVAL);
        }

        private String formatOutput(long timeDelta, long data, String symbol) {
            long speed = (long)(data / (timeDelta / 1000F));
            if (speed < KB) {
                return decimalFormat.format(speed) + symbol;
            } else if (speed < MB) {
                return decimalFormat.format(speed / (float)KB) + 'K' + symbol;
            } else if (speed < GB) {
                return decimalFormat.format(speed / (float)MB) + 'M' + symbol;
            }
            return decimalFormat.format(speed / (float)GB) + 'G' + symbol;
        }

        private boolean shouldHide(long rxData, long txData, long timeDelta) {
            long speedTxKB = (long)(txData / (timeDelta / 1000f)) / KB;
            long speedRxKB = (long)(rxData / (timeDelta / 1000f)) / KB;
            if (mTrafficType == UP) {
            return !getConnectAvailable() || speedTxKB < mAutoHideThreshold;
            } else if (mTrafficType == DOWN) {
                return !getConnectAvailable() || speedRxKB < mAutoHideThreshold;
            } else {
                return !getConnectAvailable() ||
                    (speedRxKB < mAutoHideThreshold &&
                    speedTxKB < mAutoHideThreshold);
            }
        }
    };

    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            mTrafficHandler.sendEmptyMessage(0);
        }
    };

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.NETWORK_TRAFFIC_STATE), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.NETWORK_TRAFFIC_TYPE), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.NETWORK_TRAFFIC_HIDEARROW), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.NETWORK_TRAFFIC_VIEW_LOCATION), false,
                    this, UserHandle.USER_ALL); 
       }

        /*
         *  @hide
         */
        @Override
        public void onChange(boolean selfChange) {
            setMode();
            updateSettings();
        }
    }

    /*
     *  @hide
     */
    public NetworkTraffic(Context context) {
        this(context, null);
    }

    /*
     *  @hide
     */
    public NetworkTraffic(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /*
     *  @hide
     */
    public NetworkTraffic(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        final Resources resources = getResources();
        txtImgPadding = resources.getDimensionPixelSize(R.dimen.net_traffic_txt_img_padding);
        mTintColor = resources.getColor(android.R.color.white);
        Handler mHandler = new Handler();
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        settingsObserver.observe();
        setMode();
        updateSettings();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            mContext.registerReceiver(mIntentReceiver, filter, null, getHandler());
        }
        updateSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            mContext.unregisterReceiver(mIntentReceiver);
            mAttached = false;
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION) && mScreenOn) {
                updateSettings();
            } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                mScreenOn = true;
                updateSettings();
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mScreenOn = false;
                clearHandlerCallbacks();
            }
        }
    };

    private boolean getConnectAvailable() {
        ConnectivityManager connManager =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo network = (connManager != null) ? connManager.getActiveNetworkInfo() : null;
        return network != null;
    }

    private void updateSettings() {
	final ContentResolver resolver = getContext().getContentResolver();

        mHideArrow = Settings.System.getIntForUser(mContext.
                getContentResolver(), Settings.System.NETWORK_TRAFFIC_HIDEARROW,
                0, UserHandle.USER_CURRENT) == 1;

        mTrafficInHeaderView = Settings.System.getIntForUser(resolver,
                Settings.System.NETWORK_TRAFFIC_VIEW_LOCATION, 1, UserHandle.USER_CURRENT) == 0;
        updateTextSize();
        if (mIsEnabled) {
            if (mAttached) {
                totalRxBytes = TrafficStats.getTotalRxBytes();
                lastUpdateTime = SystemClock.elapsedRealtime();
                mTrafficHandler.sendEmptyMessage(1);
            }
            updateTrafficDrawable();
            return;
        } else {
            clearHandlerCallbacks();
        }
   	setVisibility(View.GONE);
    }

    private void setMode() {
        ContentResolver resolver = mContext.getContentResolver();
        mIsEnabled = Settings.System.getIntForUser(resolver,
                Settings.System.NETWORK_TRAFFIC_STATE, 0,
                UserHandle.USER_CURRENT) == 1;
        mTrafficType = Settings.System.getIntForUser(resolver,
                Settings.System.NETWORK_TRAFFIC_TYPE, 0,
                UserHandle.USER_CURRENT);
        mAutoHideThreshold = Settings.System.getIntForUser(resolver,
                Settings.System.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD, 0,
                UserHandle.USER_CURRENT);
        mHideArrow = Settings.System.getIntForUser(resolver,
                Settings.System.NETWORK_TRAFFIC_HIDEARROW, 0,
                UserHandle.USER_CURRENT) == 1;
    }

    private void clearHandlerCallbacks() {
        mTrafficHandler.removeCallbacks(mRunnable);
        mTrafficHandler.removeMessages(0);
        mTrafficHandler.removeMessages(1);
    }

    private void updateTextSize() {
        int txtSize;
        mNetTrafSize = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.NETWORK_TRAFFIC_FONT_SIZE, 21);
        if (mTrafficType == BOTH) {
            txtSize = getResources().getDimensionPixelSize(R.dimen.net_traffic_multi_text_size);
        } else {
            txtSize = mNetTrafSize;
        }
        setTextSize(TypedValue.COMPLEX_UNIT_PX, (float)txtSize);
    }

    private void updateTrafficDrawable() {
        int intTrafficDrawable;
        if (mIsEnabled && !mHideArrow) {
           if (mTrafficType == UP) {
              intTrafficDrawable = R.drawable.stat_sys_network_traffic_up;
           } else if (mTrafficType == DOWN) {
              intTrafficDrawable = R.drawable.stat_sys_network_traffic_down;
           } else {
              intTrafficDrawable = R.drawable.stat_sys_network_traffic_updown;
	   }
        } else {
            intTrafficDrawable = 0;
        }
        if (intTrafficDrawable != 0 && !mHideArrow) {
            Drawable d = getContext().getDrawable(intTrafficDrawable);
            d.setColorFilter(mTintColor, Mode.MULTIPLY);
            setCompoundDrawablePadding(txtImgPadding);
            setCompoundDrawablesWithIntrinsicBounds(null, null, d, null);
        } else {
            setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        }
        setTextColor(mTintColor);
    }

    public void onDensityOrFontScaleChanged() {
        final Resources resources = getResources();
        txtImgPadding = resources.getDimensionPixelSize(R.dimen.net_traffic_txt_img_padding);
        setCompoundDrawablePadding(txtImgPadding);
	updateTextSize();
    }
}

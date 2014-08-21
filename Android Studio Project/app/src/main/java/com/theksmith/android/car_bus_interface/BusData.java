package com.theksmith.android.car_bus_interface;

import android.util.Log;


/**
 * helper class to represent bus data within messages passed between CBIServiceMain and bound clients
 *
 * @author Kristoffer Smith <kristoffer@theksmith.com>
 */
public class BusData {
    private static final String TAG = "BusData";
    private static final boolean DD = BuildConfig.SHOW_DEBUG_LOG_LEVEL > 1;

    public static enum BusDataType {
        ERROR, TX, RX, RX_MONITORED
    }


    public String data = null;
    public BusDataType type = BusDataType.ERROR;
    public boolean rxComplete = false;

    public BusData(String data, BusDataType type, boolean rxComplete) {
        if (DD) Log.d(TAG, "BusData()");

        this.data = data;
        this.type = type;
        this.rxComplete = rxComplete;
    }
}
package com.eveningoutpost.dexdrip.insulin.inpen;

import com.eveningoutpost.dexdrip.Models.UserError;
import com.eveningoutpost.dexdrip.utils.BtCallBack;
import com.eveningoutpost.dexdrip.utils.bt.ScanMeister;

// jamorham

public class FindNearby implements BtCallBack {

    private static final String TAG = "InPen Scan";
    private static ScanMeister scanMeister;

    public synchronized void scan() {

        if (scanMeister == null) {
            scanMeister = new InPenScanMeister().addCallBack(this, TAG);
            scanMeister.allowWide();
        } else {
            scanMeister.stop();
        }
        scanMeister.scan();
    }


    @Override
    public void btCallback(final String mac, final String status) {
        switch (status) {
            case ScanMeister.SCAN_FOUND_CALLBACK:
                UserError.Log.e(TAG, "Found! " + mac);
                InPen.setMac(mac);
                InPenEntry.startWithRefresh();
                break;

            case ScanMeister.SCAN_FAILED_CALLBACK:
            case ScanMeister.SCAN_TIMEOUT_CALLBACK:
                UserError.Log.d(TAG, "Scan callback: " + status);
                break;

        }
    }
}


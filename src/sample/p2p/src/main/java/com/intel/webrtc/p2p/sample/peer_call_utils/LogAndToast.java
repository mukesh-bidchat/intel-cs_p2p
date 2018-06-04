package com.intel.webrtc.p2p.sample.peer_call_utils;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import com.intel.webrtc.p2p.sample.R;

public class LogAndToast {
    public static final String TAG = "ICS_P2P";
    public static final int LOGTIME = Toast.LENGTH_LONG;

    public static void log(String Tag, String msg){
        Log.d(Tag, msg);
    }

    public static void log(String msg){
        Log.d(TAG, msg);
    }

    public static void toast(Context context, String msg){
        Toast.makeText(context,msg, LOGTIME).show();
    }

    public static void toast(Context context){
        Toast.makeText(context,context.getString(R.string.someting_went_wrong), LOGTIME).show();
    }

    public static void show(Context context, String Tag, String msg){
        log(Tag,msg);
        toast(context,msg);
    }

    public static void show(Context context, String msg){
        log(msg);
        toast(context,msg);
    }
}

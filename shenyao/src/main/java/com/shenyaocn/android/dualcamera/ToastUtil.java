package com.shenyaocn.android.dualcamera;

import android.content.Context;
import android.widget.Toast;

/**
 * Created by stephon on 2017/5/19.
 */

public class ToastUtil {
    private static Toast sToast;

    public static void show(Context context, String text) {
        if (sToast == null) {
            sToast = Toast.makeText(context, text, Toast.LENGTH_SHORT);
        } else {
            sToast.setText(text);
        }
        sToast.show();
    }

    public static void show(Context context, int resId){
        context = context.getApplicationContext();
        show(context, context.getString(resId));
    }
}

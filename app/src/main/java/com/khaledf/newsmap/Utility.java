package com.khaledf.newsmap;

import android.annotation.TargetApi;
import android.os.AsyncTask;
import android.os.Build;
import android.view.Gravity;

import com.github.johnpersano.supertoasts.SuperToast;
import com.github.johnpersano.supertoasts.util.Style;

import static com.khaledf.newsmap.MapActivity.*;

public class Utility {

    /* Converts an RGB color integer into a 32-bit ARGB int, with
        alpha=255. */
    public static int RGB_To_ARGB(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = (rgb >> 0) & 0xFF;
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    /* Prints a toast message. Network messages are blue while
     * map messages are orange. */
    public static void printToast(String msg, int type) {
        int toastDuration = 500, toastVerticalOffset = 350;
        SuperToast toast = null;
        if((type == ERROR_TOAST_TYPE) || (type == MAX_EVENTS_TOAST_TYPE)) {
            toastDuration *=3;
        }
        Style toastStyle = null;
        switch(type) {
            case CONNETION_TOAST_TYPE:
                toastStyle = Style.getStyle(Style.BLUE, SuperToast.Animations.FADE);
                break;
            case MAP_TOAST_TYPE:
                toastStyle = Style.getStyle(Style.ORANGE, SuperToast.Animations.FADE);
                break;
            case ERROR_TOAST_TYPE:
                toastStyle = Style.getStyle(Style.RED, SuperToast.Animations.FADE);
                break;
            case MAX_EVENTS_TOAST_TYPE:
                toastStyle = Style.getStyle(Style.GREEN, SuperToast.Animations.FADE);
                break;
            default:
                toastStyle = Style.getStyle(Style.GRAY, SuperToast.Animations.FADE);
                break;
        }
        toast = SuperToast.create(App.appContext, msg, toastDuration, toastStyle);
        toast.setGravity(Gravity.BOTTOM, 0, toastVerticalOffset);
        toast.show();
    }

    /* Used to start asynchronous tasks safely, in case of really old API. */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public static void startMyTask(AsyncTask<String, Void, String> asyncTask) {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB)
            asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        else
            asyncTask.execute();
    }
}

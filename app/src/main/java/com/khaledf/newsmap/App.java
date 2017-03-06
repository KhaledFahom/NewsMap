package com.khaledf.newsmap;

import android.app.Application;
import android.content.Context;

public class App extends Application {

    //public static final String USER_AGENT = "android:com.khaledf.newsmap:1.2";
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; WOW64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/56.0.2924.87 Safari/537.36";
    public static Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
    }
}

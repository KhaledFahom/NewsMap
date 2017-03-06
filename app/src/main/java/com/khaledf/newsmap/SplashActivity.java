package com.khaledf.newsmap;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;

public class SplashActivity extends Activity {
	private int splashScreenTimeout = 3000;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        new Handler().postDelayed(new Runnable() {
        	@Override
        	public void run() {
        		Intent intent = new Intent(SplashActivity.this, MapsActivity.class);
        		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        		startActivity(intent);
        		finish();
        	}
        }, splashScreenTimeout);	
    }
    
	/* Catching hardware keypresses. */
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent e) {    
	    if(keyCode == KeyEvent.KEYCODE_BACK) {
	    	finish();
	    	android.os.Process.killProcess(android.os.Process.myPid());
	    	System.exit(0);
	    }
	    return super.onKeyDown(keyCode, e);
	}
}

/*
 *  UVCCamera
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */

package com.serenegiant.usbcameratest4;

import android.app.Fragment;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import com.github.anrwatchdog.ANRWatchDog;
import com.serenegiant.common.BaseActivity;
import com.serenegiant.service.LogService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends BaseActivity {
	private static final boolean DEBUG = false;
	private static final String TAG = "MainActivity";

//    public static final String NAME_FONT = "MICO.ttf";
    public static final String NAME_FONT = "SIMYOU.subset.ttf";


    @Override
	protected void onCreate(final Bundle savedInstanceState) {
		//屏幕常亮
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		createFontsFile();

		super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.activity_main);
		startService(new Intent(this, LogService.class));

		if (savedInstanceState == null) {
			if (DEBUG) Log.w(TAG, "onCreate:new");
			final Fragment fragment = new CameraFragment();
			getFragmentManager().beginTransaction()
					.add(R.id.container, fragment).commit();
		}


//        new ANRWatchDog().setIgnoreDebugger(true).start();
    }

	@Override
	protected void onResume() {
		super.onResume();
		if (DEBUG) Log.w(TAG, "onResume:");
//		updateScreenRotation();
	}

	@Override
	protected void onPause() {
		if (DEBUG) Log.w(TAG, "onPause:isFinishing=" + isFinishing());
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		if (DEBUG) Log.w(TAG, "onDestroy:");
		stopService(new Intent(this, LogService.class));

		super.onDestroy();
	}

	protected final void updateScreenRotation() {
        final int screenRotation = 2;
        switch (screenRotation) {
        case 1:
        	setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        	break;
        case 2:
        	setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        	break;
        default:
        	setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        	break;
        }
	}

	private void createFontsFile() {
		new Thread(new Runnable() {
			@Override
			public void run() {
				File youyuan = getFileStreamPath(NAME_FONT);
				if (!youyuan.exists()){
					AssetManager am = getAssets();
					try {
						InputStream is = am.open("zk/" + NAME_FONT);
						FileOutputStream os = openFileOutput(NAME_FONT, MODE_PRIVATE);
						byte[] buffer = new byte[1024];
						int len = 0;
						while ((len = is.read(buffer)) != -1) {
							os.write(buffer, 0, len);
						}
						os.close();
						is.close();

					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		}).start();
	}

}

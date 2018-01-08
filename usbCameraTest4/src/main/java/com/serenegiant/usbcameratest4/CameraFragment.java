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

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.serenegiant.common.BaseFragment;
import com.serenegiant.encoder.MediaMuxerWrapper;
import com.serenegiant.event.PusherStatus;
import com.serenegiant.event.PusherUrl;
import com.serenegiant.service.UVCService;
import com.serenegiant.serviceclient.CameraClient;
import com.serenegiant.serviceclient.ICameraClientCallback;
import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.DeviceFilter;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.widget.UVCCameraTextureView;
import com.serenegiant.widget.UVCCameraTextureView2;
import com.socks.library.KLog;

import org.easydarwin.push.EasyPusher;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

public class CameraFragment extends BaseFragment {

	private static final boolean DEBUG = true;
	private static final String TAG = "CameraFragment";

	private static final int DEFAULT_WIDTH = 320;
	private static final int DEFAULT_HEIGHT = 240;

	private USBMonitor mUSBMonitor;
	private CameraClient mCameraClient;

	private ToggleButton mPreviewButton;
	private ImageButton mRecordButton;
	private ImageButton mStillCaptureButton;

	private UVCCameraTextureView2 mCameraView2;
	private UVCCameraTextureView mCameraView;
    private Surface mPreviewSurface2;
    private Surface mPreviewSurface;

    private TextView mTvPusherStatus;
    private TextView mTvPusherAddr;

//    private SurfaceView mCameraViewSub;
	private boolean isSubView;

	private boolean isRenderHolder = true; //是否使用RenderHolder, 是采用UVCCameraTextureView, 否采用UVCCameraTextureView2

	public CameraFragment() {
		if (DEBUG) KLog.w(TAG, "Constructor:");
//		setRetainInstance(true);
	}

	@SuppressWarnings("deprecation")
	@Override
	public void onAttach(final Activity activity) {
		super.onAttach(activity);
		if (DEBUG) KLog.w(TAG, "onAttach:");
	}

	@Override
	public void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (DEBUG) KLog.w(TAG, "onCreate:");
		if (mUSBMonitor == null) {
			mUSBMonitor = new USBMonitor(getActivity().getApplicationContext(), mOnDeviceConnectListener);
			final List<DeviceFilter> filters = DeviceFilter.getDeviceFilters(getActivity(), R.xml.device_filter);
			mUSBMonitor.setDeviceFilter(filters);
		}

		EventBus.getDefault().register(this);
	}

	@Override
	public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
		if (DEBUG) KLog.w(TAG, "onCreateView:");
		final View rootView = inflater.inflate(R.layout.fragment_main, container, false);
		View view = rootView.findViewById(R.id.start_button);
		view.setOnClickListener(mOnClickListener);
		view =rootView.findViewById(R.id.stop_button);
		view.setOnClickListener(mOnClickListener);

		mPreviewButton = (ToggleButton)rootView.findViewById(R.id.preview_button);
		setPreviewButton(false);
		mPreviewButton.setEnabled(false);

		mRecordButton = (ImageButton)rootView.findViewById(R.id.record_button);
		mRecordButton.setOnClickListener(mOnClickListener);
		mRecordButton.setEnabled(false);

		mStillCaptureButton = (ImageButton)rootView.findViewById(R.id.still_button);
		mStillCaptureButton.setOnClickListener(mOnClickListener);
		mStillCaptureButton.setEnabled(false);

		mCameraView2 = (UVCCameraTextureView2) rootView.findViewById(R.id.camera_view2);
		mCameraView2.setAspectRatio(DEFAULT_WIDTH / (float)DEFAULT_HEIGHT);

        mCameraView = (UVCCameraTextureView) rootView.findViewById(R.id.camera_view);
        mCameraView.setAspectRatio(DEFAULT_WIDTH / (float)DEFAULT_HEIGHT);

        if (isRenderHolder) {
            mCameraView.setVisibility(View.VISIBLE);
            mCameraView2.setVisibility(View.GONE);
        } else {
            mCameraView.setVisibility(View.GONE);
            mCameraView2.setVisibility(View.VISIBLE);
        }

//		mCameraViewSub = (SurfaceView)rootView.findViewById(R.id.camera_view_sub);
//		mCameraViewSub.setOnClickListener(mOnClickListener);

        mTvPusherStatus = (TextView) rootView.findViewById(R.id.tv_pusher_status);
        mTvPusherAddr = (TextView) rootView.findViewById(R.id.tv_pusher_addr);

        return rootView;
	}

	@Override
	public void onResume() {
		super.onResume();
		if (DEBUG) KLog.w(TAG, "onResume:");
		mUSBMonitor.register();

        if (mCameraView2 != null) {
            mCameraView2.onResume();
        }

        if (mCameraView != null) {
            mCameraView.onResume();
        }
	}

	@Override
	public void onPause() {
		if (DEBUG) KLog.w(TAG, "onPause:");

        /*if (mCameraView2.getSurface() != null) {
            mCameraView2.getSurface().release();
        }*/

        if (mCameraClient != null) {
            if (mPreviewSurface2 != null) {
                mCameraClient.removeSurface(mPreviewSurface2);
                mPreviewSurface2.release();
                mPreviewSurface2 = null;
            }

            if (mPreviewSurface != null) {
                mCameraClient.removeSurface(mPreviewSurface);
                mPreviewSurface.release();
                mPreviewSurface = null;
            }
//			mCameraClient.removeSurface(mCameraViewSub.getHolder().getSurface());
			isSubView = false;
		}



        if (mCameraView2 != null) {
            mCameraView2.onPause();
            mCameraView2 = null;
        }

        if (mCameraView != null) {
            mCameraView.onPause();
            mCameraView = null;
        }

        mUSBMonitor.unregister();
		enableButtons(false);
		super.onPause();
	}

	@Override
	public void onDestroyView() {
		if (DEBUG) KLog.w(TAG, "onDestroyView:");
		super.onDestroyView();
	}

	@Override
	public void onDestroy() {
		if (DEBUG) KLog.w(TAG, "onDestroy:");
		if (mCameraClient != null) {
			mCameraClient.release();
			mCameraClient = null;
		}

		EventBus.getDefault().unregister(this);

		super.onDestroy();
	}

	@Override
	public void onDetach() {
		if (DEBUG) KLog.w(TAG, "onDetach:");
		super.onDetach();
	}

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void postPusherUrl(PusherUrl url) {
        KLog.w(TAG, "推流地址: " + url.getUrl() + ", thread: "
                + Thread.currentThread().getName());
        mTvPusherAddr.setText(url.getUrl());
    }

	@Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
	public void postPusherStatus(PusherStatus status) {
        KLog.w(TAG, "推流状态: " + status.getStatusNo() + ", thread: "
                + Thread.currentThread().getName());

        switch (status.getStatusNo()) {
			case EasyPusher.OnInitPusherCallback.CODE.EASY_ACTIVATE_INVALID_KEY:
				KLog.w(TAG, "pushing invalid Key");
				break;
			case EasyPusher.OnInitPusherCallback.CODE.EASY_ACTIVATE_SUCCESS:
				KLog.w(TAG, "pushing 激活成功");
                mTvPusherStatus.setText("激活成功");
                break;
			case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_CONNECTING:
				KLog.w(TAG, "pushing connecting");
                mTvPusherStatus.setText("连接中");
                break;
			case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_CONNECTED:
				KLog.w(TAG, "pushing connect success");
                mTvPusherStatus.setText("连接成功");

				break;
			case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_CONNECT_FAILED:
				KLog.w(TAG, "pushing connect failed");
//				isPushing = false;
                mTvPusherStatus.setText("连接失败");

				break;
			case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_CONNECT_ABORT:
				KLog.w(TAG, "pushing 连接异常中断");
//				isPushing = false;
                mTvPusherStatus.setText("连接异常中断");

                break;
			case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_PUSHING:
				KLog.w(TAG, "pushing 推流中");
				mTvPusherStatus.setText("推流中");
				break;
			case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_DISCONNECTED:
				KLog.w(TAG, "pushing 断开连接");
//				isPushing = false;
                mTvPusherStatus.setText("断开连接");

                break;
			case EasyPusher.OnInitPusherCallback.CODE.EASY_ACTIVATE_PLATFORM_ERR:
				KLog.w(TAG, "pushing 平台不匹配");
//				isPushing = false;
				break;
			case EasyPusher.OnInitPusherCallback.CODE.EASY_ACTIVATE_COMPANY_ID_LEN_ERR:
				KLog.w(TAG, "pushing 断授权使用商不匹配");
//				isPushing = false;
				break;
			case EasyPusher.OnInitPusherCallback.CODE.EASY_ACTIVATE_PROCESS_NAME_LEN_ERR:
				KLog.w(TAG, "pushing 进程名称长度不匹配");
//				isPushing = false;
				break;
		}
	}

	public USBMonitor getUSBMonitor() {
		return mUSBMonitor;
	}

	private final OnDeviceConnectListener mOnDeviceConnectListener = new OnDeviceConnectListener() {
		@Override
		public void onAttach(final UsbDevice device) {
			if (DEBUG) KLog.w(TAG, "OnDeviceConnectListener#onAttach:");
//			if (!updateCameraDialog() && (mCameraView2.hasSurface())) {
			if (!updateCameraDialog()) {
				tryOpenUVCCamera(true);
			}
		}

		@Override
		public void onConnect(final UsbDevice device, final UsbControlBlock ctrlBlock, final boolean createNew) {
			if (DEBUG) KLog.w(TAG, "OnDeviceConnectListener#onConnect:");
		}

		@Override
		public void onDisconnect(final UsbDevice device, final UsbControlBlock ctrlBlock) {
			if (DEBUG) KLog.w(TAG, "OnDeviceConnectListener#onDisconnect:");
		}

		@Override
		public void onDettach(final UsbDevice device) {
			if (DEBUG) KLog.w(TAG, "OnDeviceConnectListener#onDettach:");
			queueEvent(new Runnable() {
				@Override
				public void run() {
					if (mCameraClient != null) {
						mCameraClient.disconnect();
						mCameraClient.release();
						mCameraClient = null;
					}
				}
			}, 0);
			enableButtons(false);
			updateCameraDialog();
		}

		@Override
		public void onCancel(final UsbDevice device) {
			if (DEBUG) KLog.w(TAG, "OnDeviceConnectListener#onCancel:");
			enableButtons(false);
		}
	};

	private boolean updateCameraDialog() {
		final Fragment fragment = getFragmentManager().findFragmentByTag("CameraDialog");
		if (fragment instanceof CameraDialog) {
			((CameraDialog)fragment).updateDevices();
			return true;
		}
		return false;
	}

	private void tryOpenUVCCamera(final boolean requestPermission) {
		if (DEBUG) KLog.w(TAG, "tryOpenUVCCamera:");
		openUVCCamera(0);
	}

	private void openUVCCamera(final int index) {
		if (DEBUG) KLog.w(TAG, "openUVCCamera:index=" + index);
		if (!mUSBMonitor.isRegistered()) return;
		final List<UsbDevice> list = mUSBMonitor.getDeviceList();
		if (list.size() > index) {
			enableButtons(false);
			if (mCameraClient == null)
				mCameraClient = new CameraClient(getActivity(), mCameraListener);
			mCameraClient.select(list.get(index));
			mCameraClient.resize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
			mCameraClient.connect();
		}
	}

	private final ICameraClientCallback mCameraListener = new ICameraClientCallback() {
		@Override
		public void onConnect() {
            KLog.w(TAG, "onConnect");

            if (isRenderHolder) {
                if (mCameraView == null) {
                    return;
                }
            } else {
                if (mCameraView2 == null
//                    || mCameraViewSub == null
                        ) {
                    return;
                }
            }


			KLog.w(TAG, "onConnect: " + "thread: " + Thread.currentThread().getName());

			isSubView = true;
			enableButtons(true);
			setPreviewButton(true);
			// start UVCService
			final Intent intent = new Intent(getActivity(), UVCService.class);
			getActivity().startService(intent);

            SystemClock.sleep(500);

            if (!isRenderHolder) {
                final SurfaceTexture st = mCameraView2.getSurfaceTexture();
                if (st != null) {
                    KLog.w(TAG, "*******mCameraView2.getSurfaceTexture ok");
                    mPreviewSurface2 = new Surface(st);
                    if (mPreviewSurface2 != null) {
                        KLog.w(TAG, "*******mPreviewSurface2 create ok");
                    }
                }

//            mCameraClient.addSurface(mCameraViewSub.getHolder().getSurface(), false);
                mCameraClient.addSurface(mPreviewSurface2, false);
            } else {
                mPreviewSurface = mCameraView.getSurface();
                mCameraClient.addSurface(mPreviewSurface, true);
            }

        }

		@Override
		public void onDisconnect() {
			if (DEBUG) KLog.w(TAG, "onDisconnect:");
			setPreviewButton(false);
			enableButtons(false);
        }

	};

	private final OnClickListener mOnClickListener = new OnClickListener() {
		@Override
		public void onClick(final View v) {
			switch (v.getId()) {
			case R.id.start_button:
				if (DEBUG) KLog.w(TAG, "onClick:start");
				// start service
				final List<UsbDevice> list = mUSBMonitor.getDeviceList();
				if (list.size() > 0) {
					if (mCameraClient == null)
						mCameraClient = new CameraClient(getActivity(), mCameraListener);
					mCameraClient.select(list.get(0));
					mCameraClient.resize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
					mCameraClient.connect();
					setPreviewButton(false);
				}
				break;
			case R.id.stop_button:
				if (DEBUG) KLog.w(TAG, "onClick:stop");
				// stop service
				if (mCameraClient != null) {
					mCameraClient.disconnect();
					mCameraClient.release();
					mCameraClient = null;
				}
				enableButtons(false);
				break;
			case R.id.camera_view_sub:
				if (DEBUG) KLog.w(TAG, "onClick:sub view");
				/*if (isSubView) {
					mCameraClient.removeSurface(mCameraViewSub.getHolder().getSurface());
				} else {
					mCameraClient.addSurface(mCameraViewSub.getHolder().getSurface(), false);
				}*/
				isSubView = !isSubView;
				break;
			case R.id.record_button:
				if (DEBUG) KLog.w(TAG, "onClick:record");
				if (checkPermissionWriteExternalStorage() && checkPermissionAudio()) {

					queueEvent(new Runnable() {
						@Override
						public void run() {
							if (mCameraClient.isRecording()) {
								mCameraClient.stopRecording();
								runOnUiThread(new Runnable() {
									@Override
									public void run() {
										mRecordButton.setColorFilter(0);
									}
								}, 0);
							} else {
								mCameraClient.startRecording();

								//推流测试
								mCameraClient.startPush();

								runOnUiThread(new Runnable() {
									@Override
									public void run() {
										mRecordButton.setColorFilter(0x7fff0000);
									}
								}, 0);
							}
						}
					}, 0);
				}
				break;
			case R.id.still_button:
				if (DEBUG) KLog.w(TAG, "onClick:still capture");
				if (mCameraClient != null && checkPermissionWriteExternalStorage()) {
					queueEvent(new Runnable() {
						@Override
						public void run() {
							mCameraClient.captureStill(MediaMuxerWrapper.getCaptureFile(Environment.DIRECTORY_DCIM, ".jpg").toString());
						}
					}, 0);
				}

				break;
			}
		}
	};

	private final OnCheckedChangeListener mOnCheckedChangeListener = new OnCheckedChangeListener() {
		@Override
		public void onCheckedChanged(final CompoundButton buttonView, final boolean isChecked) {
            KLog.w(TAG, "onCheckedChanged:" + isChecked + ", addSurface,  isRenderHolder: "
                    + isRenderHolder);

            if (isChecked) {
                if (isRenderHolder) {
                    SystemClock.sleep(500);

                    mPreviewSurface = mCameraView.getSurface();
                    mCameraClient.addSurface(mPreviewSurface, true);
                } else {
                    mCameraClient.addSurface(mPreviewSurface2, false);
                }
//				mCameraClient.addSurface(mCameraViewSub.getHolder().getSurface(), false);
			} else {
                if (isRenderHolder) {
                    mCameraClient.removeSurface(mPreviewSurface);
                } else {
                    mCameraClient.removeSurface(mPreviewSurface2);
                }
//				mCameraClient.removeSurface(mCameraViewSub.getHolder().getSurface());
			}
		}
	};

	private void setPreviewButton(final boolean onoff) {
		getActivity().runOnUiThread(new Runnable() {
			@Override
			public void run() {
				mPreviewButton.setOnCheckedChangeListener(null);
				try {
					mPreviewButton.setChecked(onoff);
				} finally {
					mPreviewButton.setOnCheckedChangeListener(mOnCheckedChangeListener);
				}
			}
		});
	}

	private final void enableButtons(final boolean enable) {
		setPreviewButton(false);
		getActivity().runOnUiThread(new Runnable() {
			@Override
			public void run() {
				mPreviewButton.setEnabled(enable);
				mRecordButton.setEnabled(enable);
				mStillCaptureButton.setEnabled(enable);
				if (enable && mCameraClient.isRecording()) {
					mRecordButton.setColorFilter(0x7fff0000);
				} else {
					mRecordButton.setColorFilter(0);
				}
			}
		});
	}
}

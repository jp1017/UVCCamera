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

package com.serenegiant.service;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaScannerConnection;
import android.media.SoundPool;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.view.Surface;

import com.serenegiant.encoder.MediaEncoder;
import com.serenegiant.glutils.RendererHolder;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usbcameratest4.R;
import com.shenyaocn.android.Encoder.AVWriter;
import com.shenyaocn.android.Encoder.Helper;
import com.socks.library.KLog;

import org.easydarwin.push.Constants;
import org.easydarwin.push.EasyPusher;
import org.easydarwin.push.InitCallback;
import org.easydarwin.sw.TxtOverlay;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class CameraServer extends Handler {
	private static final boolean DEBUG = true;
	private static final String TAG = "CameraServer";

	private int mFrameWidth = Constants.PIC_WIDTH_DEFAULT / 2;
	private int mFrameHeight = Constants.PIC_HEIGHT_DEFAULT / 2;

    private static final float mBandwidth = UVCCamera.DEFAULT_BANDWIDTH / 2;

	private static class CallbackCookie {
		boolean isConnected;
	}

	private final RemoteCallbackList<IUVCServiceCallback> mCallbacks
			= new RemoteCallbackList<IUVCServiceCallback>();
	private int mRegisteredCallbackCount;

	private RendererHolder mRendererHolder;
	private final WeakReference<CameraThread> mWeakThread;

	private boolean isRenderHolder;

	public static CameraServer createServer(final Context context, final UsbControlBlock ctrlBlock, final int vid, final int pid) {
		if (DEBUG) KLog.w(TAG, "createServer:");
		final CameraThread thread = new CameraThread(context, ctrlBlock);
		thread.start();
		return thread.getHandler();
	}

	private CameraServer(final CameraThread thread) {
		if (DEBUG) KLog.w(TAG, "Constructor:");
		mWeakThread = new WeakReference<CameraThread>(thread);
		mRegisteredCallbackCount = 0;
		mRendererHolder = new RendererHolder(mFrameWidth, mFrameHeight, null);
	}

	@Override
	protected void finalize() {
		if (DEBUG) KLog.w(TAG, "finalize:");
		release();
		try {
			super.finalize();
		} catch (Throwable throwable) {
			throwable.printStackTrace();
		}
	}

	public void registerCallback(final IUVCServiceCallback callback) {
		if (DEBUG) KLog.w(TAG, "registerCallback:");
		mCallbacks.register(callback, new CallbackCookie());
		mRegisteredCallbackCount++;
	}

	public boolean unregisterCallback(final IUVCServiceCallback callback) {
		if (DEBUG) KLog.w(TAG, "unregisterCallback:");
		mCallbacks.unregister(callback);
		mRegisteredCallbackCount--;
		if (mRegisteredCallbackCount < 0) mRegisteredCallbackCount = 0;
		return mRegisteredCallbackCount == 0;
	}

	public void release() {
		if (DEBUG) KLog.w(TAG, "release:");
		disconnect();
		mCallbacks.kill();
		if (mRendererHolder != null) {
			mRendererHolder.release();
			mRendererHolder = null;
		}
	}

	//********************************************************************************
//********************************************************************************
	public void resize(final int width, final int height) {
		if (DEBUG) KLog.w(TAG, String.format("resize(%d,%d)", width, height));
		if (!isRecording()) {
			mFrameWidth = width;
			mFrameHeight = height;
			if (mRendererHolder != null) {
				mRendererHolder.resize(width, height);
			}
		}
	}

	public void connect() {
		if (DEBUG) KLog.w(TAG, "connect:");
		final CameraThread thread = mWeakThread.get();
		if (!thread.isCameraOpened()) {
			sendMessage(obtainMessage(MSG_OPEN));
			sendMessage(obtainMessage(MSG_PREVIEW_START, mFrameWidth, mFrameHeight, mRendererHolder.getSurface()));
		} else {
			if (DEBUG) KLog.w(TAG, "already connected, just call callback");
			processOnCameraStart();
		}
	}

	public void connectSlave() {
		if (DEBUG) KLog.w(TAG, "connectSlave:");
		final CameraThread thread = mWeakThread.get();
		if (thread.isCameraOpened()) {
			processOnCameraStart();
		}
	}

	public void disconnect() {
		if (DEBUG) KLog.w(TAG, "disconnect:");
		stopRecording();
		final CameraThread thread = mWeakThread.get();
		if (thread == null) return;
		synchronized (thread.mSync) {
			sendEmptyMessage(MSG_PREVIEW_STOP);
			sendEmptyMessage(MSG_CLOSE);
			// wait for actually preview stopped to avoid releasing Surface/SurfaceTexture
			// while preview is still running.
			// therefore this method will take a time to execute
			try {
				thread.mSync.wait();
			} catch (final InterruptedException e) {
			}
		}
	}

	public boolean isConnected() {
		final CameraThread thread = mWeakThread.get();
		return (thread != null) && thread.isCameraOpened();
	}

	public boolean isRecording() {
		final CameraThread thread = mWeakThread.get();
		return (thread != null) && thread.isRecording();
	}

	public void addSurface(final int id, final Surface surface, final boolean isRecordable) {
		if (DEBUG) KLog.w(TAG, "addSurface:id=" + id + ",surface=" + surface
				+ ", isRecordable: " + isRecordable);

        isRenderHolder = isRecordable;

		if (isRecordable) {
			if (mRendererHolder != null) {
				mRendererHolder.addSurface(id, surface, isRecordable);
			}
		} else {
			final CameraThread thread = mWeakThread.get();
			if (thread != null) {
				thread.addSurface(surface);
			}
		}

    }

	public void removeSurface(final int id, final Surface surface) {
		if (DEBUG) KLog.w(TAG, "removeSurface:id=" + id);
        if (isRenderHolder) {
            if (mRendererHolder != null) {
                mRendererHolder.removeSurface(id);
            }
        } else {
            final CameraThread thread = mWeakThread.get();
            if (thread != null) {
                thread.removeSurface(surface);
            }
        }
    }

	public void startRecording() {
		if (!isRecording())
			sendEmptyMessage(MSG_CAPTURE_START);
	}

	public void stopRecording() {
		if (isRecording())
			sendEmptyMessage(MSG_CAPTURE_STOP);
	}

    public void captureStill(final String path) {
        KLog.w(TAG, "captureStill:path=" + path);

        if (isRenderHolder) {
            if (mRendererHolder != null) {
                KLog.w(TAG, "captureStill, use renderholder");

//                mRendererHolder.captureStill(path);
                sendMessage(obtainMessage(MSG_CAPTURE_STILL, path));
            }
        } else {
            sendMessage(obtainMessage(MSG_CAPTURE_STILL, path));
        }

    }

	public void startPush() {
		sendEmptyMessage(MSG_PUSH_START);
	}

	public void stopPush() {
		sendEmptyMessage(MSG_PUSH_STOP);
	}

	//********************************************************************************
	private void processOnCameraStart() {
		if (DEBUG) KLog.w(TAG, "processOnCameraStart:");
		try {
			final int n = mCallbacks.beginBroadcast();
			for (int i = 0; i < n; i++) {
				if (!((CallbackCookie)mCallbacks.getBroadcastCookie(i)).isConnected)
					try {
						mCallbacks.getBroadcastItem(i).onConnected();
						((CallbackCookie)mCallbacks.getBroadcastCookie(i)).isConnected = true;
					} catch (final Exception e) {
						KLog.e(TAG, "failed to call IOverlayCallback#onFrameAvailable");
					}
			}
			mCallbacks.finishBroadcast();
		} catch (final Exception e) {
			KLog.w(TAG, e);
		}
	}

	private void processOnCameraStop() {
		if (DEBUG) KLog.w(TAG, "processOnCameraStop:");
		final int n = mCallbacks.beginBroadcast();
		for (int i = 0; i < n; i++) {
			if (((CallbackCookie)mCallbacks.getBroadcastCookie(i)).isConnected)
				try {
					mCallbacks.getBroadcastItem(i).onDisConnected();
					((CallbackCookie)mCallbacks.getBroadcastCookie(i)).isConnected = false;
				} catch (final Exception e) {
					KLog.e(TAG, "failed to call IOverlayCallback#onDisConnected");
				}
		}
		mCallbacks.finishBroadcast();
	}

	//**********************************************************************
	private static final int MSG_OPEN = 0;
	private static final int MSG_CLOSE = 1;
	private static final int MSG_PREVIEW_START = 2;
	private static final int MSG_PREVIEW_STOP = 3;
	private static final int MSG_CAPTURE_STILL = 4;
	private static final int MSG_CAPTURE_START = 5;
	private static final int MSG_CAPTURE_STOP = 6;
	private static final int MSG_MEDIA_UPDATE = 7;
	private static final int MSG_RELEASE = 9;
	private static final int MSG_PUSH_START = 10;
	private static final int MSG_PUSH_STOP = 11;

	@Override
	public void handleMessage(final Message msg) {
		final CameraThread thread = mWeakThread.get();
		if (thread == null) return;
		switch (msg.what) {
			case MSG_OPEN:
				thread.handleOpen();
				break;
			case MSG_CLOSE:
				thread.handleClose();
				break;
			case MSG_PREVIEW_START:
				thread.handleStartPreview(msg.arg1, msg.arg2, (Surface) msg.obj);
				break;
			case MSG_PREVIEW_STOP:
				thread.handleStopPreview();
				break;
			case MSG_CAPTURE_STILL:
				thread.handleCaptureStill((String) msg.obj);
				break;
			case MSG_CAPTURE_START:
				thread.handleStartRecording();
				break;
			case MSG_CAPTURE_STOP:
				//停止推流
				thread.handleStopPush();
				thread.handleStopRecording();
				break;
			case MSG_MEDIA_UPDATE:
				thread.handleUpdateMedia((String) msg.obj);
				break;
			case MSG_RELEASE:
				thread.handleRelease();
				break;
			case MSG_PUSH_START:
				thread.handleStartPush();
				break;
			case MSG_PUSH_STOP:
				thread.handleStopPush();
				break;
			default:
				throw new RuntimeException("unsupported message:what=" + msg.what);
		}
	}

	private static final class CameraThread extends Thread implements InitCallback {
		private static final String TAG_THREAD = "CameraThread";
		private final Object mSync = new Object();
		private final WeakReference<Context> mWeakContext;
		private int mFrameWidth, mFrameHeight;

		/**
		 * shutter sound
		 */
		private SoundPool mSoundPool;
		private int mSoundId;
		private CameraServer mHandler;
		private UsbControlBlock mCtrlBlock;
		/**
		 * for accessing UVC camera
		 */
		private volatile UVCCamera mUVCCamera;

        private Surface mSurface;

        private final TxtOverlay mTxtOverlay;
		private final AVWriter mAVWriter = new AVWriter(1);    // 用于左边摄像头录像
        private AudioRecord audioRecord;    //录音

        private OutputStream snapshotOutStream;        // 用于摄像头拍照
        private String snapshotFileName;

        private boolean isRecord = false;
        private long startTakeVideoTime = System.currentTimeMillis();

		private CameraThread(final Context context, final UsbControlBlock ctrlBlock) {
			super("CameraThread");
			if (DEBUG) KLog.w(TAG_THREAD, "Constructor:");
			mWeakContext = new WeakReference<Context>(context);
			mCtrlBlock = ctrlBlock;
			loadShutterSound(context);

            mTxtOverlay = new TxtOverlay(context);

        }

		@Override
		protected void finalize() throws Throwable {
			KLog.w(TAG_THREAD, "CameraThread#finalize");
			super.finalize();
		}

		public CameraServer getHandler() {
			if (DEBUG) KLog.w(TAG_THREAD, "getHandler:");
			synchronized (mSync) {
				if (mHandler == null)
					try {
						mSync.wait();
					} catch (final InterruptedException e) {
					}
			}
			return mHandler;
		}

		public boolean isCameraOpened() {
			return mUVCCamera != null;
		}

		public boolean isRecording() {
            return (mUVCCamera != null)
                    && isRecord;
        }

		public void handleOpen() {
			if (DEBUG) KLog.w(TAG_THREAD, "handleOpen:");
			handleClose();
			synchronized (mSync) {
				mUVCCamera = new UVCCamera();
				mUVCCamera.open(mCtrlBlock);
				if (DEBUG) KLog.w(TAG, "supportedSize:" + mUVCCamera.getSupportedSize());
			}
			mHandler.processOnCameraStart();
		}

		public void handleClose() {
			if (DEBUG) KLog.w(TAG_THREAD, "handleClose:");
			handleStopRecording();
			boolean closed = false;
			synchronized (mSync) {
				if (mUVCCamera != null) {
					mUVCCamera.stopPreview();
					mUVCCamera.destroy();
					mUVCCamera = null;
					closed = true;
				}
				mSync.notifyAll();
			}
			if (closed)
				mHandler.processOnCameraStop();
			if (DEBUG) KLog.w(TAG_THREAD, "handleClose:finished");
		}

		public void handleStartPreview(final int width, final int height, final Surface surface) {
			if (DEBUG) KLog.w(TAG_THREAD, "handleStartPreview:");
			synchronized (mSync) {
				if (mUVCCamera == null) return;
				try {
					mUVCCamera.setPreviewSize(width, height, UVCCamera.FRAME_FORMAT_MJPEG, mBandwidth);
				} catch (final IllegalArgumentException e) {
					try {
						// fallback to YUV mode
						mUVCCamera.setPreviewSize(width, height, UVCCamera.DEFAULT_PREVIEW_MODE, mBandwidth);
					} catch (final IllegalArgumentException e1) {
						mUVCCamera.destroy();
						mUVCCamera = null;
					}
				}
				if (mUVCCamera == null) return;

                mUVCCamera.setFrameCallback(mIFrameCallback, UVCCamera.PIXEL_FORMAT_NV21);


                if (mSurface == null) {
                    mSurface = surface;
                }

                mFrameWidth = width;
				mFrameHeight = height;
				mUVCCamera.setPreviewDisplay(surface);
				mUVCCamera.startPreview();

                String filename = Environment.getExternalStorageDirectory() + "/SIMYOU.ttf";
//            mTxtOverlay.init(320, 240, context.getApplicationContext()
//                    .getFileStreamPath("SIMYOU.ttf").getPath());
                mTxtOverlay.init(mFrameWidth, mFrameHeight, filename);
			}
		}

		public void handleStopPreview() {
			if (DEBUG) KLog.w(TAG_THREAD, "handleStopPreview:");
			synchronized (mSync) {
				if (mUVCCamera != null) {
					mUVCCamera.stopPreview();
				}
			}
		}

        private void handleResize(final int width, final int height, final Surface surface) {
            if (DEBUG) KLog.w(TAG_THREAD, "handleResize begin");
            synchronized (mSync) {
                if (mUVCCamera != null) {
                    final Size sz = mUVCCamera.getPreviewSize();
                    /*if ((sz != null) && ((width != sz.width) || (height != sz.height))) {
                        mUVCCamera.stopPreview();
                        try {
                            mUVCCamera.setPreviewSize(width, height);
                        } catch (final IllegalArgumentException e) {
                            try {
                                mUVCCamera.setPreviewSize(sz.width, sz.height);
                            } catch (final IllegalArgumentException e1) {
                                // unexpectedly #setPreviewSize failed
                                mUVCCamera.destroy();
                                mUVCCamera = null;
                            }
                        }
                        if (mUVCCamera == null) return;
                        mFrameWidth = width;
                        mFrameHeight = height;
                        mUVCCamera.setPreviewDisplay(surface);
                        mUVCCamera.startPreview();
                    }*/

                    mUVCCamera.stopPreview();

                    mUVCCamera.setFrameCallback(mIFrameCallback, UVCCamera.PIXEL_FORMAT_NV21);

                    if (mSurface == null) {
                        mSurface = surface;
                    }

                    mFrameWidth = width;
                    mFrameHeight = height;
                    mUVCCamera.setPreviewDisplay(surface);
                    mUVCCamera.startPreview();

                    String filename = Environment.getExternalStorageDirectory() + "/SIMYOU.ttf";
//            mTxtOverlay.init(320, 240, context.getApplicationContext()
//                    .getFileStreamPath("SIMYOU.ttf").getPath());
                    mTxtOverlay.init(mFrameWidth, mFrameHeight, filename);

                    if (DEBUG) KLog.w(TAG_THREAD, "handleResize finish");
                }
            }
        }

		public void handleCaptureStill(final String path) {
			if (DEBUG) KLog.w(TAG_THREAD, "handleCaptureStill:");

            mSoundPool.play(mSoundId, 0.2f, 0.2f, 0, 0, 1.0f);    // play shutter sound

            captureSnapshot();
        }

		public void handleStartRecording() {
			if (DEBUG) KLog.w(TAG_THREAD, "handleStartRecording:");
            if (mUVCCamera != null) {
                startRecord(mAVWriter, mUVCCamera);
                //playSound("开始录像");
                isRecord = true;
                startTakeVideoTime = System.currentTimeMillis();
            }
        }

        private void startRecord(AVWriter avWriter, UVCCamera uvcCamera) {
            if (avWriter.isOpened() || uvcCamera == null) {
                return;
            }

            String fileName = Helper.getVideoFileName(); // 可以参考函数实现

            openAVWriter(fileName, avWriter, uvcCamera);

            if (avWriter.isOpened()) {
//                startRecTimer(); // 如果编码器打开成功，则开始录像计时
            }
        }

        private void openAVWriter(String fileName, AVWriter avWriter, UVCCamera uvcCamera) {
            Size size = uvcCamera.getPreviewSize();
            if (audioRecord != null) {
                avWriter.open(fileName, size.width, size.height, audioRecord.getSampleRate(), audioRecord.getChannelCount());
            } else {
                avWriter.open(fileName, size.width, size.height, 0, 0);
            }
        }

        private void stopRecord(AVWriter avWriter) {
		    KLog.w(TAG, "录像停止, stopRecord... isOpened: " + avWriter.isOpened());
//            stopRecTimer();
            if (!avWriter.isOpened()) {
                return;
            }

            final String fileName = avWriter.getRecordFileName();
            avWriter.close(new AVWriter.ClosedCallback() {
                @Override
                public void closedCallback() {    // 录像文件编码完成时的异步回调
                    Helper.fileSavedProcess(mWeakContext.get().getApplicationContext(), fileName); // 通知媒体扫描器扫描媒体文件以便在图库等app可见，可以参见函数实现
                    KLog.w("视频成功: \"" + fileName + "\"");
                }
            });
        }

        private void handleStartPush() {
            KLog.w(TAG_THREAD, "handleStartPush");

//			initRtspClient(false);
            synchronized (mSync) {
                MediaEncoder.isStartPush = true;
            }
        }

        private void handleStopPush() {
            KLog.w(TAG_THREAD, "handleStopPush:mMuxer=");
            synchronized (mSync) {
                MediaEncoder.isStartPush = false;
            }
        }

        public void handleStopRecording() {
            if (DEBUG) KLog.w(TAG_THREAD, "handleStopRecording:mMuxer=");
            synchronized (mSync) {
                if (mUVCCamera != null) {
                    mUVCCamera.stopCapture();
                }

                stopRecord(mAVWriter);
                isRecord = false;
            }

        }

		public void handleUpdateMedia(final String path) {
			if (DEBUG) KLog.w(TAG_THREAD, "handleUpdateMedia:path=" + path);
			final Context context = mWeakContext.get();
			if (context != null) {
				try {
					if (DEBUG) KLog.w(TAG, "MediaScannerConnection#scanFile");
					MediaScannerConnection.scanFile(context, new String[]{ path }, null, null);
				} catch (final Exception e) {
					KLog.e(TAG, "handleUpdateMedia:", e);
				}
			} else {
				KLog.w(TAG, "MainActivity already destroyed");
				// give up to add this movice to MediaStore now.
				// Seeing this movie on Gallery app etc. will take a lot of time.
				handleRelease();
			}
		}

		public void handleRelease() {
			if (DEBUG) KLog.w(TAG_THREAD, "handleRelease:");
			handleClose();
//            releaseCamera();

            if (mCtrlBlock != null) {
				mCtrlBlock.close();
				mCtrlBlock = null;
			}
			if (!isRecord)
				Looper.myLooper().quit();
		}

        /**
         * 退出uvccamera, 参考shenyao
         */
        private synchronized void releaseCamera() {
            synchronized (this) {
                if (mAVWriter.isOpened()) {
                    stopRecord(mAVWriter);
                }
                if (mUVCCamera != null) {
                    try {
                        //mUVCCameraL.stopPreview();
                        mUVCCamera.setStatusCallback(null);
                        mUVCCamera.setButtonCallback(null);
                        mUVCCamera.close();
                        mUVCCamera.destroy();
                    } catch (final Exception e) {
                        KLog.e(TAG, e.getMessage());
                    } finally {
                        KLog.w(TAG, "*******releaseCameraL mUVCCameraL=null");
                        mUVCCamera = null;
                    }
                }

                if (mSurface != null) {
                    mSurface.release();
                    mSurface = null;
                }
            }
        }



        // 实现快照抓取
        private synchronized void captureSnapshot() {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd.HH.mm.ss");
            Date currentTime = new Date();

            if (mUVCCamera != null) {
                snapshotFileName = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DualCamera_test4";
                snapshotFileName += "/IPC_";
                snapshotFileName += format.format(currentTime);
                snapshotFileName += ".jpg";
                File recordFile = new File(snapshotFileName);    // 左边摄像头快照的文件名
                if (recordFile.exists()) {
                    recordFile.delete();
                }
                try {
                    recordFile.createNewFile();
                    snapshotOutStream = new FileOutputStream(recordFile);
                } catch (Exception e) {
                }
            }

        }


		private String getCurrentTime() {
			SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			return simpleDateFormat.format(new Date());
		}

        // if you need frame data as ByteBuffer on Java side, you can use this callback method with UVCCamera#setFrameCallback
        private final IFrameCallback mIFrameCallback = new IFrameCallback() {
            @Override
            public void onFrame(final ByteBuffer frame) {
                if (mUVCCamera == null) {
                    return;
                }

                final Size size = mUVCCamera.getPreviewSize();

                int FrameSize = frame.remaining();
                final byte[] buffer = new byte[FrameSize];
                frame.get(buffer);

                //添加水印
                mTxtOverlay.overlay(buffer, "" + getCurrentTime());

                //录像
                if (isRecord) {
                    if (mAVWriter.isVideoEncoderOpened()) { // 将视频帧发送到编码器
                        mAVWriter.putFrame(buffer, size.width, size.height);
                    }

                    // TODO: 18-1-8 下午1:58 录制声音
                    startAudio();
                }

                // 将视频帧压缩成jpeg图片，实现快照捕获
                if (snapshotOutStream != null) {
                    if (!(FrameSize < size.width * size.height * 3 / 2) && (buffer != null)) {
                        try {
                            new YuvImage(buffer, ImageFormat.NV21, size.width, size.height, null).compressToJpeg(new Rect(0, 0, size.width, size.height), 90, snapshotOutStream);
                            snapshotOutStream.flush();
                            snapshotOutStream.close();
                        } catch (Exception ex) {
                        } finally {
                            snapshotOutStream = null;
                        }
                    }
                }
            }
        };

		/**
		 * prepare and load shutter sound for still image capturing
		 */
		@SuppressWarnings("deprecation")
		private void loadShutterSound(final Context context) {
			if (DEBUG) KLog.w(TAG_THREAD, "loadShutterSound:");
			// get system stream type using refrection
			int streamType;
			try {
				final Class<?> audioSystemClass = Class.forName("android.media.AudioSystem");
				final Field sseField = audioSystemClass.getDeclaredField("STREAM_SYSTEM_ENFORCED");
				streamType = sseField.getInt(null);
			} catch (final Exception e) {
				streamType = AudioManager.STREAM_SYSTEM;	// set appropriate according to your app policy
			}
			if (mSoundPool != null) {
				try {
					mSoundPool.release();
				} catch (final Exception e) {
				}
				mSoundPool = null;
			}
			// load sutter sound from resource
			mSoundPool = new SoundPool(2, streamType, 0);
			mSoundId = mSoundPool.load(context, R.raw.camera_click, 1);
		}

		@Override
		public void run() {
			if (DEBUG) KLog.w(TAG_THREAD, "run:");
			Looper.prepare();
			synchronized (mSync) {
				mHandler = new CameraServer(this);
				mSync.notifyAll();
			}
			Looper.loop();
			synchronized (mSync) {
				mHandler = null;
				mSoundPool.release();
				mSoundPool = null;
				mSync.notifyAll();
			}
			if (DEBUG) KLog.w(TAG_THREAD, "run:finished");
		}

        @Override
        public void onCallback(int code) {
            KLog.w(TAG, "推流onCallback: " + code);
            switch (code) {
                case EasyPusher.OnInitPusherCallback.CODE.EASY_ACTIVATE_INVALID_KEY:
                    KLog.w(TAG, "推流无效Key");
                    break;
                case EasyPusher.OnInitPusherCallback.CODE.EASY_ACTIVATE_SUCCESS:
                    KLog.w(TAG, "推流激活成功");
                    synchronized (mSync) {
//                        isPushing = true;
                    }

                    break;
                case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_CONNECTING:
                    KLog.w(TAG, "推流连接中");
                    break;
                case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_CONNECTED:
                    KLog.w(TAG, "推流连接成功");
                    break;
                case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_CONNECT_FAILED:
                    KLog.w(TAG, "推流连接失败");
                    break;
                case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_CONNECT_ABORT:
                    KLog.w(TAG, "推流连接异常中断");
                    break;
                case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_PUSHING:
                    KLog.w(TAG, "推流推流中");

                    break;
                case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_DISCONNECTED:
                    KLog.w(TAG, "推流断开连接");
                    handleStopPush();
                    break;
                case EasyPusher.OnInitPusherCallback.CODE.EASY_ACTIVATE_PLATFORM_ERR:
                    KLog.w(TAG, "推流平台不匹配");
                    break;
                case EasyPusher.OnInitPusherCallback.CODE.EASY_ACTIVATE_COMPANY_ID_LEN_ERR:
                    KLog.w(TAG, "推流断授权使用商不匹配");
                    break;
                case EasyPusher.OnInitPusherCallback.CODE.EASY_ACTIVATE_PROCESS_NAME_LEN_ERR:
                    KLog.w(TAG, "推流进程名称长度不匹配");
                    break;
            }
        }

        public void addSurface(Surface surface) {
            KLog.w(TAG, "addSurface: hash: " + surface.hashCode());

            handleResize(mFrameWidth, mFrameHeight, surface);
        }

        public void removeSurface(Surface surface) {
            if (surface != null) {
                surface.release();
            }
        }






        /*******************    声音录制    ******************/
        private int bufferSize;
        private AudioThread audioThread;                // 录音线程


        private void processAudio(byte[] buffer, int length) {
            if (mAVWriter.isAudioEncoderOpened()) {
                mAVWriter.putAudio(buffer, length);
            }

            /*if (avWriterR.isAudioEncoderOpened()) {
                avWriterR.putAudio(buffer, length);
            }*/
            // 将麦克风捕获的PCM音频数据分别发送给编码器
        }

        private class AudioThread extends Thread {
            private boolean bRun = true;

            public void run() {
                setName("AudioThread");

                byte[] buffer = new byte[bufferSize];
                try {
                    while (bRun) {

                        int bufferReadResult = audioRecord.read(buffer, 0, buffer.length);
                        if (bufferReadResult <= 0) {
                            Thread.sleep(100);
                            continue;
                        }

                        processAudio(buffer, bufferReadResult);
                    }
                } catch (Exception e) {
                    KLog.e("AudioThread", e.getMessage());
                }
            }

            public void cancel() {
                bRun = false;
            }
        }

        // 开始麦克风捕获
        private synchronized boolean startAudio() {

            if (audioRecord != null || audioThread != null) { // 如果已经打开了就直接返回
                return true;
            }

            int[] bufferSizes = new int[1];

            audioRecord = Helper.findAudioRecord(true, bufferSizes); // 参考具体函数实现
            bufferSize = bufferSizes[0];
            if (audioRecord == null) {
                return false;
            }

            audioRecord.startRecording();

            audioThread = new AudioThread();
            audioThread.start();

            return true;
        }

        private synchronized void stopAudio() {

            if (audioThread != null)
                audioThread.cancel();

            if (audioRecord != null) {
                try {
                    audioRecord.stop();
                    audioRecord.release();
                    audioRecord = null;
                } catch (Exception e) {
                }
            }

            audioThread = null;
        }
    }

}

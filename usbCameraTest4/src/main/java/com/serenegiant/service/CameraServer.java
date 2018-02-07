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
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.view.Surface;

import com.serenegiant.app.UvcApp;
import com.serenegiant.glutils.RendererHolder;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usbcameratest4.MainActivity;
import com.serenegiant.usbcameratest4.R;
import com.shenyaocn.android.Encoder.AVWriter;
import com.shenyaocn.android.Encoder.Helper;
import com.socks.library.KLog;

import org.easydarwin.push.EasyPusher;
import org.easydarwin.push.InitCallback;
import org.easydarwin.sw.TxtOverlay;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;


public final class CameraServer extends Handler {
	private static final String TAG = "CameraServer";

	private int mFrameWidth = 320;
	private int mFrameHeight = 240;

	//摄像头带宽, 影响每包最大数据量
	private static final float BANDWIDTH_DEFAULT = 0.5f;

	private static int reason;     //文件生成原因

    private static class CallbackCookie {
		boolean isConnected;
	}

    private final RemoteCallbackList<IUVCServiceCallback> mCallbacks
		= new RemoteCallbackList<>();
    private int mRegisteredCallbackCount;

	private RendererHolder mRendererHolder;
	private final WeakReference<CameraThread> mWeakThread;

	public static CameraServer createServer(final boolean isRing, final Context context,
                                            final USBMonitor.UsbControlBlock ctrlBlock, final boolean isDvr) {
		KLog.w(TAG, "createServer: " + ctrlBlock.getDeviceKeyName());
		final CameraThread thread = new CameraThread(isRing, context, ctrlBlock, isDvr);
		thread.start();
		return thread.getHandler();
	}

	private CameraServer(final CameraThread thread) {
		KLog.w(TAG, "Constructor:");

		mWeakThread = new WeakReference<>(thread);
		mRegisteredCallbackCount = 0;
		mRendererHolder = new RendererHolder(mFrameWidth, mFrameHeight, null);
	}

	@Override
	protected void finalize() {
		KLog.w(TAG, "finalize:");
		release();
		try {
			super.finalize();
		} catch (Throwable throwable) {
			KLog.e(TAG, "CameraServer fanalize failed: " + throwable.getMessage());
		}
	}

	public void registerCallback(final IUVCServiceCallback callback) {
		KLog.w(TAG, "registerCallback:");
		mCallbacks.register(callback, new CallbackCookie());
		mRegisteredCallbackCount++;
	}

	public boolean unregisterCallback(final IUVCServiceCallback callback) {
		KLog.w(TAG, "unregisterCallback:");
		mCallbacks.unregister(callback);
		mRegisteredCallbackCount--;
		if (mRegisteredCallbackCount < 0) mRegisteredCallbackCount = 0;
		return mRegisteredCallbackCount == 0;
	}

	public void release() {
		KLog.w(TAG, "release:");
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
		KLog.w(TAG, String.format("resize(%d,%d)", width, height));
		if (!isRecording()) {
			mFrameWidth = width;
			mFrameHeight = height;
			if (mRendererHolder != null) {
				mRendererHolder.resize(width, height);
			}
		}
	}
	
	public void connect() {
		KLog.w(TAG, "connect:");
		reason = 2;// TODO: 16-7-31 拍照原因

		final CameraThread thread = mWeakThread.get();
		KLog.w(TAG, "connect: id: " + thread.mCtrlBlock.getDeviceKeyName());

		if (!thread.isCameraOpened()) {
			sendMessage(obtainMessage(MSG_OPEN));
			sendMessage(obtainMessage(MSG_PREVIEW_START, mFrameWidth, mFrameHeight, mRendererHolder.getSurface()));
		} else {
			KLog.w(TAG, "already connected, just call callback");
			processOnCameraStart();
		}
	}

	public void disconnect() {
		KLog.w(TAG, "disconnect:");
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
				KLog.e(TAG, "CameraServer#disconnect failed: " + e.getMessage());
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
		KLog.w(TAG, "addSurface:id=" + id +",surface=" + surface);
		if (mRendererHolder != null)
			mRendererHolder.addSurface(id, surface, isRecordable);
	}

	public void removeSurface(final int id) {
		KLog.w(TAG, "removeSurface:id=" + id);
		if (mRendererHolder != null) {
			mRendererHolder.removeSurface(id);
		}
	}

	public void startRecording() {
		sendMessage(obtainMessage(MSG_CAPTURE_START));
	}

	public void stopRecording() {
		sendEmptyMessage(MSG_CAPTURE_STOP);
		/*if (isRecording()) {
		}*/
	}

	public void captureStill(final String path) {
		/*if (mRendererHolder != null) {
			mRendererHolder.captureStill(path);
			sendMessage(obtainMessage(MSG_CAPTURE_STILL, path));
		}*/

		sendMessage(obtainMessage(MSG_CAPTURE_STILL, path));
	}

	public void startPush() {
		sendEmptyMessage(MSG_PUSH_START);
	}

	public void stopPush() {
		sendEmptyMessage(MSG_PUSH_STOP);
	}

    public boolean isPushing() {
        final CameraThread thread = mWeakThread.get();
        return (thread != null) && thread.isPushing();
    }

//********************************************************************************
	private void processOnCameraStart() {
		KLog.w(TAG, "processOnCameraStart:");
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
		KLog.w(TAG, "processOnCameraStop:");
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
	private static final int MSG_PUSH_START = 7;
	private static final int MSG_PUSH_STOP = 8;

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
				thread.handleStartRecording(msg.arg1, msg.arg2);
				break;
			case MSG_CAPTURE_STOP:
				thread.handleStopRecording();
				break;
			case MSG_PUSH_START:
				thread.handleStartPush();
				break;
			case MSG_PUSH_STOP:
				thread.handleStopPush();
				break;
		}
	}

	private static final class CameraThread extends Thread implements InitCallback {
		private static final String TAG_THREAD = "CameraThread";
		private final byte[] mSync = new byte[0];
		private boolean mIsRecording;
		private boolean mIsPushing;
	    private final WeakReference<Context> mWeakContext;

	    private final boolean isDvr;

		private CameraServer mHandler;
		private USBMonitor.UsbControlBlock mCtrlBlock;
		/**
		 * for accessing UVC camera
		 */
		private volatile UVCCamera mUVCCamera;

		private TxtOverlay mTxtOverlay;
        private AVWriter mAVWriter;    // 用于摄像头录像

        private OutputStream snapshotOutStream;        // 用于摄像头拍照

        private int bufferSize;
        private AudioThread audioThread;                // 录音线程
        private AudioRecord audioRecord;    //录音

		private MediaPlayer mMediaPlayer;

		private EasyPusher mPusher;

		private CameraThread(final boolean isRing, final Context context,
                             final USBMonitor.UsbControlBlock ctrlBlock, final boolean isDvr) {
			super("CameraServer-CameraThread");
			KLog.w(TAG_THREAD, "Constructor:");

            this.isDvr = isDvr;

			mWeakContext = new WeakReference<>(context);
			mCtrlBlock = ctrlBlock;

            mTxtOverlay = new TxtOverlay(context);

			//是否播放拍照声音
			if (isRing) {
				mMediaPlayer = MediaPlayer.create(context, Uri.parse("android.resource://"
						+ context.getPackageName() + "/" + R.raw.camera_click));
			}

			initRtspClient();
		}

		@Override
		protected void finalize() {
			KLog.w(TAG_THREAD, "CameraServer#CameraThread#finalize");
			try {
				handleRelease();
				super.finalize();
			} catch (Throwable throwable) {
				KLog.e(TAG_THREAD, "CameraServer#CameraThread#fanalize failed: " + throwable.getMessage());
			}
		}

		public CameraServer getHandler() {
			KLog.w(TAG_THREAD, "getCameraHandler:");
			synchronized (mSync) {
				if (mHandler == null)
				try {
					mSync.wait();
				} catch (final InterruptedException e) {
				    e.printStackTrace();
				}
			}
			return mHandler;
		}

		private boolean isCameraOpened() {
			return mUVCCamera != null;
		}

		/**
		 * 是否正在录制
		 * 20170721蒋朋添加一个条件: mIsRecording
		 * @return
		 */
		public boolean isRecording() {
			return mUVCCamera != null
					&& mIsRecording;
		}

        /**
         * 是否正在推流
         * @return
         */
        public boolean isPushing() {
            return isRecording()
                    && mPusher != null
                    && mIsPushing;
        }

		private void handleOpen() {
			KLog.w(TAG_THREAD, "handleOpen:");
			handleClose();
			synchronized (mSync) {
				mUVCCamera = new UVCCamera();
				if (mCtrlBlock != null) {
					mUVCCamera.open(mCtrlBlock);
				}
				KLog.w(TAG, "supportedSize:" + mUVCCamera.getSupportedSize());
			}
			mHandler.processOnCameraStart();
		}

		private void handleClose() {
			KLog.w(TAG_THREAD, "handleClose:");
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
			if (closed) {
				mHandler.processOnCameraStop();
			}
			KLog.w(TAG_THREAD, "handleClose:finished");
		}

		private void handleStartPreview(final int width, final int height, final Surface surface) {
			KLog.w(TAG_THREAD, "handleStartPreview: w: " + width + ", h: " + height);

			synchronized (mSync) {
				if (mUVCCamera == null) {
					return;
				}

				try {
					KLog.w(TAG_THREAD, "handleStartPreview, mode-mjpeg");
					mUVCCamera.setPreviewSize(width, height, UVCCamera.FRAME_FORMAT_MJPEG, BANDWIDTH_DEFAULT);
				} catch (final IllegalArgumentException e) {
					try {
						KLog.e(TAG_THREAD, "handleStartPreview: catch : " + e.getMessage());
						// fallback to YUV mode
						mUVCCamera.setPreviewSize(width, height, UVCCamera.FRAME_FORMAT_YUYV, BANDWIDTH_DEFAULT);
					} catch (final IllegalArgumentException e1) {
						KLog.e(TAG_THREAD, "handleStartPreview: failed: " + e1.getMessage());
						mUVCCamera.destroy();
						mUVCCamera = null;
					}
				}
				if (mUVCCamera == null) {
					return;
				}

				try {
					mTxtOverlay.init(width, height, mWeakContext.get()
                        .getFileStreamPath(MainActivity.NAME_FONT).getPath());

                } catch (Exception e) {
					mTxtOverlay = null;
					KLog.e(TAG, "水印初始化失败: " + e.getMessage());
				}

                if (mTxtOverlay != null) {
                    KLog.w(TAG, "水印初始化成功");
                }

				mUVCCamera.setFrameCallback(mIFrameCallback, UVCCamera.PIXEL_FORMAT_YUV420SP);

				mUVCCamera.setPreviewDisplay(surface);
				mUVCCamera.startPreview();
			}
		}

		private void handleStopPreview() {
			KLog.w(TAG_THREAD, "handleStopPreview:");
			synchronized (mSync) {
				if (mUVCCamera != null) {
					mUVCCamera.stopPreview();
				}
			}
		}

		private void handleResize(final int width, final int height, final Surface surface) {
			synchronized (mSync) {
				if (mUVCCamera != null) {
					final Size sz = mUVCCamera.getPreviewSize();
					if ((sz != null) && ((width != sz.width) || (height != sz.height))) {
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
						mUVCCamera.setPreviewDisplay(surface);
						mUVCCamera.startPreview();
					}
				}
			}
		}
		
		private void handleCaptureStill(final String path) {
			KLog.w(TAG_THREAD, "handleCaptureStill: path: " + path);

			//拍照声音
            if (mMediaPlayer != null) {
                playRing();
            }

            captureSnapshot(path);
		}

        /**
         * 实现快照抓取
         * @param picFile
         */
		private synchronized void captureSnapshot(final String picFile) {
			if (mUVCCamera != null) {
				File recordFile = new File(picFile);    // 摄像头快照的文件名
				if (recordFile.exists()) {
					recordFile.delete();
				}
				try {
					recordFile.createNewFile();
					snapshotOutStream = new FileOutputStream(recordFile);
				} catch (Exception e) {
				    e.printStackTrace();
				}
			}
		}

		/**
		 * 拍照声音
		 * 通知  {@link RingtoneManager#TYPE_NOTIFICATION}
		 * 闹钟  {@link RingtoneManager#TYPE_ALARM}
		 * 铃声  {@link RingtoneManager#TYPE_RINGTONE}
		 */
		public void playRing() {
			if (mMediaPlayer != null) {
				mMediaPlayer.start();
			}
		}

		private void handleStartRecording(final int hasSound, final int cameraId) {
			KLog.w(TAG_THREAD, "handleStartRecording: isDVR: " + isDvr + ", hasSound: " + hasSound);

            if (mUVCCamera != null) {
				String videoPath = Helper.getVideoFileName();

                mAVWriter = new AVWriter();

                startRecord(mAVWriter, mUVCCamera, videoPath);

                //开启录音
                if (false) {
                    // TODO: 18-1-9 下午3:46 添加音频编码后,视频只有34k,没法播放
                    startAudio();
                }

                mIsRecording = true;
            }
		}

        private void startRecord(AVWriter avWriter, UVCCamera uvcCamera, String videoPath) {
            if (avWriter.isOpened() || uvcCamera == null) {
                return;
            }

            openAVWriter(videoPath, avWriter, uvcCamera);
        }

        private void openAVWriter(String fileName, AVWriter avWriter, UVCCamera uvcCamera) {
            Size size = uvcCamera.getPreviewSize();
            /*if (audioRecord != null) {
                avWriter.open(fileName, size.width, size.height, audioRecord.getSampleRate(), audioRecord.getChannelCount());
            } else {
                avWriter.open(fileName, size.width, size.height, 0, 0);
            }*/
            avWriter.open(fileName, size.width, size.height, mPusher);
        }

        private void stopRecord(AVWriter avWriter) {
            KLog.w(TAG, "音/视频录像停止, stopRecord... isOpened: " + avWriter.isOpened()
                    + ", idDvr: " + isDvr);

            if (!avWriter.isOpened()) {
                return;
            }

            final String fileName = avWriter.getRecordFileName();
            avWriter.close(new AVWriter.ClosedCallback() {
                @Override
                public void closedCallback() {    // 录像文件编码完成时的异步回调
                    fileSavedProcess(mWeakContext.get().getApplicationContext(), fileName); // 通知媒体扫描器扫描媒体文件以便在图库等app可见，可以参见函数实现
                    KLog.w("音/视频编码成功: \"" + fileName + "\"");
                }
            });
        }

		/**
		 * 通知视频录制完成,可在相册等看到刚录制的文件
		 * @param c
		 * @param fileName
		 */
		public static void fileSavedProcess(Context c, String fileName) {
            c.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(new File(fileName))));
            MediaScannerConnection.scanFile(c, new String[]{fileName}, null, null);
        }

		private void handleStopRecording() {
			KLog.w(TAG_THREAD, "handleStopRecording:mMuxer=");

            synchronized (mSync) {
                if (mUVCCamera != null) {
                    mUVCCamera.stopCapture();
                }

                mIsRecording = false;
                stopAudio();
                if (mAVWriter != null) {
                    stopRecord(mAVWriter);
                }
            }

            final Context context = mWeakContext.get();
            if (context == null) {
                KLog.e(TAG, "Activity already destroyed");
                // give up to add this movice to MediaStore now.
                // Seeing this movie on Gallery app etc. will take a lot of time.
                handleRelease();
            }
		}


		/**
		 * 初始化推流
		 */
		private void initRtspClient() {
			String id = "107700000088_2";

			String videoIp = "video.qdsxkj.com";

			//todo 开源的库需要获取ip
//            videoIp = NetUtils.getIpFromHostSync(videoIp);

			String tcpPort = "10554";

			KLog.w(TAG, "推流: url: " + String.format("rtsp://%s:%s/%s.sdp", videoIp, tcpPort, id)
					+ ", idDvr: " + isDvr);

			mPusher = new EasyPusher();

			mPusher.initPush(videoIp, tcpPort + "", String.format("%s.sdp", id),
					UvcApp.getApplication(), this);

			//新版本
				/*mPusher.initPush(CarApplicationLike.getAppInstance(), Constants.KEY_EASYPUSHER, this);
                mPusher.setMediaInfo(Pusher.Codec.EASY_SDK_VIDEO_CODEC_H264, FRAME_RATE, Pusher.Codec.EASY_SDK_AUDIO_CODEC_AAC, 1, 8000, 16);
                mPusher.start(videoIp, tcpPort + "", String.format("%s.sdp", id), Pusher.TransType.EASY_RTP_OVER_TCP);
*/
		}

		@Override
		public void onCallback(int code) {
			KLog.w(TAG, "推流 onCallback: " + code);
			switch (code) {
				case EasyPusher.OnInitPusherCallback.CODE.EASY_ACTIVATE_SUCCESS:
					KLog.w(TAG, "推流 激活成功");
					break;
				case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_CONNECTING:
					KLog.w(TAG, "推流 connecting");
					break;
				case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_CONNECTED:
					KLog.w(TAG, "推流 connect success");
                    mIsPushing = true;
//					mAVWriter.setPusher(mPusher);

                    break;
				case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_CONNECT_FAILED:
					KLog.w(TAG, "推流 connect failed");
					break;
				case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_CONNECT_ABORT:
					KLog.w(TAG, "推流 连接异常中断");
					break;
				case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_PUSHING:
					KLog.w(TAG, "推流 pushing");
//					isPushing = true;
					break;
				case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_DISCONNECTED:
					KLog.w(TAG, "推流 断开连接");
//					mAVWriter.setPusher(null);
                    mIsPushing = false;
                    break;
			}
		}

		private void handleStartPush() {
			KLog.w(TAG_THREAD, "handleStartPush:推流 isDvr: " + isDvr);

			//开始推流
//            initRtspClient();
        }

		private void handleStopPush() {
			KLog.w(TAG_THREAD, "handleStopPush:推流mMuxer=");

            mIsPushing = false;

            //结束推流
            if (mPusher != null) {
                mPusher.stop();
                mPusher = null;
            }
//            mAVWriter.setPusher(null);
        }

		private void handleRelease() {
			KLog.w(TAG_THREAD, "handleRelease:");
			handleClose();
			if (mCtrlBlock != null) {
				mCtrlBlock.close();
				mCtrlBlock = null;
			}
			if (!mIsRecording) {
				Looper.myLooper().quitSafely();
			}

		}

		private String getCurrentTime() {
			SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			return simpleDateFormat.format(new Date());
		}

        /**
         * 实时获取到码流
         */
		private final IFrameCallback mIFrameCallback = new IFrameCallback() {

            //			@Override
			public void onFrame(final ByteBuffer frame) {
                if (mUVCCamera == null) {
                    return;
                }

                final Size size = mUVCCamera.getPreviewSize();
				final int frameSize = frame.remaining();

				final byte[] buffer = new byte[frameSize];
                frame.get(buffer);

                //添加水印
                mTxtOverlay.overlay(buffer, "" + getCurrentTime());

                //录像
                if (mIsRecording) {
                    if (mAVWriter.isVideoEncoderOpened()) { // 将视频帧发送到编码器
                        mAVWriter.putFrame(buffer);
                    }
                }

                // 将视频帧压缩成jpeg图片，实现快照捕获
                if (snapshotOutStream != null) {
                    if (!(frameSize < size.width * size.height * 3 / 2)) {
                        try {
                            new YuvImage(buffer, ImageFormat.NV21, size.width, size.height, null)
                                    .compressToJpeg(new Rect(0, 0, size.width, size.height),
                                            90, snapshotOutStream);

                            snapshotOutStream.flush();
                            KLog.w(TAG, "音/视频 快照捕捉完成, hash: " + mUVCCamera.hashCode());
                            snapshotOutStream.close();
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        } finally {
                            snapshotOutStream = null;
                        }
                    }
                }

			}
		};

		@Override
		public void run() {
			KLog.w(TAG_THREAD, "run:");
			Looper.prepare();
			synchronized (mSync) {
				mHandler = new CameraServer(this);
				mSync.notifyAll();
			}
			Looper.loop();
			synchronized (mSync) {
				if (mHandler != null) {
					mHandler = null;
				}

				if (mMediaPlayer != null) {
					mMediaPlayer.stop();
					mMediaPlayer.release();
					mMediaPlayer = null;
				}

                if (mTxtOverlay != null) {
				    mTxtOverlay.release();
                    mTxtOverlay = null;
                }

				mSync.notifyAll();
			}
			KLog.w(TAG_THREAD, "run:finished");
		}





        /*******************    声音录制    ******************/


        private void processAudio(byte[] buffer, int length) {
            if (mAVWriter.isAudioEncoderOpened()) {
//                mAVWriter.putAudio(buffer, length);
            }

            // 将麦克风捕获的PCM音频数据分别发送给编码器
        }

        private class AudioThread extends Thread {
            private boolean bRun = true;

            public void run() {
                setName("CameraServer-AudioThread");

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

            if (audioThread != null) {
                audioThread.interrupt();
                audioThread.cancel();
            }

            if (audioRecord != null) {
                try {
                    audioRecord.stop();
                    audioRecord.release();
                    audioRecord = null;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            audioThread = null;
        }

        /*******************    声音录制    ******************/

	}

}

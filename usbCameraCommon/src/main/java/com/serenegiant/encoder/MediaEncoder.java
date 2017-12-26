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

package com.serenegiant.encoder;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Pair;

import com.pedro.rtsp.rtsp.Protocol;
import com.pedro.rtsp.rtsp.RtspClient;
import com.pedro.rtsp.utils.ConnectCheckerRtsp;
import com.serenegiant.app.UvcApp;
import com.socks.library.KLog;

import org.easydarwin.push.Constants;
import org.easydarwin.push.EasyPusher;
import org.easydarwin.push.InitCallback;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

public abstract class MediaEncoder implements Runnable, InitCallback {
	private static final boolean DEBUG = true;	// TODO set false on release
	private static final String TAG = "MediaEncoder";

	protected static final int TIMEOUT_USEC = 10000;	// 10[msec]
	protected static final int MSG_FRAME_AVAILABLE = 1;
	protected static final int MSG_STOP_RECORDING = 9;

	public interface MediaEncoderListener {
		public void onPrepared(MediaEncoder encoder);
		public void onStopped(MediaEncoder encoder);
	}

	protected final Object mSync = new Object();
	/**
	 * Flag that indicate this encoder is capturing now.
	 */
    protected volatile boolean mIsCapturing;
	/**
	 * Flag that indicate the frame data will be available soon.
	 */
	private int mRequestDrain;
    /**
     * Flag to request stop capturing
     */
    protected volatile boolean mRequestStop;
    /**
     * Flag that indicate encoder received EOS(End Of Stream)
     */
    protected boolean mIsEOS;
    /**
     * Flag the indicate the muxer is running
     */
    protected boolean mMuxerStarted;
    /**
     * Track Number
     */
    protected int mTrackIndex;
    /**
     * MediaCodec instance for encoding
     */
    protected MediaCodec mMediaCodec;				// API >= 16(Android4.1.2)
    /**
     * Weak refarence of MediaMuxerWarapper instance
     */
    protected final WeakReference<MediaMuxerWrapper> mWeakMuxer;
    /**
     * BufferInfo instance for dequeuing
     */
    private final MediaCodec.BufferInfo mBufferInfo;		// API >= 16(Android4.1.2)

    protected final MediaEncoderListener mListener;

	private EasyPusher mPusher;
	private RtspClient mRtspClient;

    public MediaEncoder(final MediaMuxerWrapper muxer, final MediaEncoderListener listener) {
    	if (listener == null) throw new NullPointerException("MediaEncoderListener is null");
    	if (muxer == null) throw new NullPointerException("MediaMuxerWrapper is null");
		mWeakMuxer = new WeakReference<MediaMuxerWrapper>(muxer);
		muxer.addEncoder(this);
		mListener = listener;

		synchronized (mSync) {
            // create BufferInfo here for effectiveness(to reduce GC)
            mBufferInfo = new MediaCodec.BufferInfo();
            // wait for starting thread
            new Thread(this, getClass().getSimpleName()).start();
            try {
            	mSync.wait();
            } catch (final InterruptedException e) {
            }
        }
	}

	@Override
	public void onCallback(int code) {
		KLog.w(TAG, "pushing onCallback: " + code);
		switch (code) {
			case EasyPusher.OnInitPusherCallback.CODE.EASY_ACTIVATE_INVALID_KEY:
				KLog.w(TAG, "pushing invalid Key");
				break;
			case EasyPusher.OnInitPusherCallback.CODE.EASY_ACTIVATE_SUCCESS:
				KLog.w(TAG, "pushing 激活成功");
				break;
			case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_CONNECTING:
				KLog.w(TAG, "pushing connecting");
				break;
			case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_CONNECTED:
				KLog.w(TAG, "pushing connect success");
				isPushing = true;

				break;
			case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_CONNECT_FAILED:
				KLog.w(TAG, "pushing connect failed");
//				isPushing = false;

				break;
			case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_CONNECT_ABORT:
				KLog.w(TAG, "pushing 连接异常中断");
//				isPushing = false;
				break;
			case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_PUSHING:
				KLog.w(TAG, "pushing pushing");
				isPushing = true;
				break;
			case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_DISCONNECTED:
				KLog.w(TAG, "pushing 断开连接");
//				isPushing = false;
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

	public static boolean isStartPush;	//是否开始pushing 
	private volatile boolean isPushing;		//是否正在pushing 

	/**
	 * 初始化easypusher
	 */
	protected void initEasyPusher(Context context, String videoIp, int tcpPort) {
        if (mRtspClient != null) {
            return;
        }

        mRtspClient = new RtspClient(new ConnectCheckerRtsp() {
			@Override
			public void onConnectionSuccessRtsp() {
				KLog.w(TAG, "pushing  connect success");
                isPushing = true;

			}

			@Override
			public void onConnectionFailedRtsp(String reason) {
				KLog.w(TAG, "pushing  connect failed");
                isPushing = false;

			}

			@Override
			public void onDisconnectRtsp() {
				KLog.w(TAG, "pushing  disconnect");
                isPushing = false;

			}

			@Override
			public void onAuthErrorRtsp() {
				KLog.w(TAG, "pushing  auth failed");
                isPushing = true;

			}

			@Override
			public void onAuthSuccessRtsp() {
				KLog.w(TAG, "pushing  auth success");

			}
		});

        mRtspClient.setUrl("rtsp://139.224.226.23:10554/107700000016_11.sdp");
        mRtspClient.connect();

        /*if (mPusher == null) {
			mPusher = new EasyPusher();
		} else {
			return;
		}

		String id = Constants.PUSHER_BACK_ID;
		KLog.w(TAG, "pushing : url: " + String.format("rtsp://%s:%s/%s.sdp", videoIp, tcpPort, id));

		//新版本
		mPusher.initPush(context.getApplicationContext(), this);
		mPusher.setMediaInfo(Pusher.Codec.EASY_SDK_VIDEO_CODEC_H264, 25, Pusher.Codec.EASY_SDK_AUDIO_CODEC_AAC, 1, 8000, 16);
		mPusher.start(videoIp, tcpPort + "", String.format("%s.sdp", id), Pusher.TransType.EASY_RTP_OVER_TCP);
		*/
	}

    public String getOutputPath() {
    	final MediaMuxerWrapper muxer = mWeakMuxer.get();
    	return muxer != null ? muxer.getOutputPath() : null;
    }

    /**
     * the method to indicate frame data is soon available or already available
     * @return return true if encoder is ready to encod.
     */
    public boolean frameAvailableSoon() {
//    	if (DEBUG) KLog.w(TAG, "frameAvailableSoon");
        synchronized (mSync) {
            if (!mIsCapturing || mRequestStop) {
                return false;
            }
            mRequestDrain++;
            mSync.notifyAll();
        }
        return true;
    }

    /**
     * encoding loop on private thread
     */
	@Override
	public void run() {
//		android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        synchronized (mSync) {
            mRequestStop = false;
    		mRequestDrain = 0;
            mSync.notify();
        }
        final boolean isRunning = true;
        boolean localRequestStop;
        boolean localRequestDrain;
        while (isRunning) {
        	synchronized (mSync) {
        		localRequestStop = mRequestStop;
        		localRequestDrain = (mRequestDrain > 0);
        		if (localRequestDrain)
        			mRequestDrain--;
        	}

//			KLog.w(TAG, "running, localRequestStop: " + localRequestStop + ", drain: " + localRequestDrain);

			if (localRequestStop) {
	           	drain();
	           	// request stop recording
	           	signalEndOfInputStream();
	           	// process output data again for EOS signale
	           	drain();
	           	// release all related objects
	           	release();
	           	break;
	        }
	        if (localRequestDrain) {
//				darwinPush();
				drain();
	        } else {
	        	synchronized (mSync) {
		        	try {
						mSync.wait();
					} catch (final InterruptedException e) {
						break;
					}
	        	}
        	}
        } // end of while
		if (DEBUG) KLog.w(TAG, "Encoder thread exiting");
        synchronized (mSync) {
        	mRequestStop = true;
            mIsCapturing = false;
        }
	}

	/*
    * prepareing method for each sub class
    * this method should be implemented in sub class, so set this as abstract method
    * @throws IOException
    */
   /*package*/ abstract void prepare(Context context) throws IOException;

	/*package*/ void startRecording() {
   	if (DEBUG) KLog.w(TAG, "startRecording");
		synchronized (mSync) {
			mIsCapturing = true;
			mRequestStop = false;
			mSync.notifyAll();
		}
	}

   /**
    * the method to request stop encoding
    */
	/*package*/ void stopRecording() {
		if (DEBUG) KLog.w(TAG, "stopRecording");
		synchronized (mSync) {
			if (!mIsCapturing || mRequestStop) {
				return;
			}
			mRequestStop = true;	// for rejecting newer frame
			mSync.notifyAll();
	        // We can not know when the encoding and writing finish.
	        // so we return immediately after request to avoid delay of caller thread
		}
	}

//********************************************************************************
//********************************************************************************
    /**
     * Release all releated objects
     */
    protected void release() {
		if (DEBUG) KLog.w(TAG, "release:");

		try {
			mListener.onStopped(this);
		} catch (final Exception e) {
			KLog.e(TAG, "failed onStopped", e);
		}
		mIsCapturing = false;

		isPushing = false;

		if (mPusher != null) {
			mPusher.stop();
			mPusher = null;
		}

        if (mMediaCodec != null) {
			try {
	            mMediaCodec.stop();
	            mMediaCodec.release();
	            mMediaCodec = null;
			} catch (final Exception e) {
				KLog.e(TAG, "failed releasing MediaCodec", e);
			}
        }
        if (mMuxerStarted) {
       		final MediaMuxerWrapper muxer = mWeakMuxer.get();
       		if (muxer != null) {
       			try {
           			muxer.stop();
    			} catch (final Exception e) {
    				KLog.e(TAG, "failed stopping muxer", e);
    			}
       		}
        }
    }

    protected void signalEndOfInputStream() {
		if (DEBUG) KLog.w(TAG, "sending EOS to encoder");
        // signalEndOfInputStream is only avairable for video encoding with surface
        // and equivalent sending a empty buffer with BUFFER_FLAG_END_OF_STREAM flag.
//		mMediaCodec.signalEndOfInputStream();	// API >= 18
        encode((byte[])null, 0, getPTSUs());
	}

    /**
     * Method to set byte array to the MediaCodec encoder
     * @param buffer
     * @param length　length of byte array, zero means EOS.
     * @param presentationTimeUs
     */
    @SuppressWarnings("deprecation")
	protected void encode(final byte[] buffer, final int length, final long presentationTimeUs) {
//    	if (DEBUG) KLog.w(TAG, "encode:buffer=" + buffer);
    	if (!mIsCapturing) return;
    	int ix = 0, sz;
        final ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
        while (mIsCapturing && ix < length) {
	        final int inputBufferIndex = mMediaCodec.dequeueInputBuffer(TIMEOUT_USEC);
	        if (inputBufferIndex >= 0) {
	            final ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
	            inputBuffer.clear();
	            sz = inputBuffer.remaining();
	            sz = (ix + sz < length) ? sz : length - ix;
	            if (sz > 0 && (buffer != null)) {
	            	inputBuffer.put(buffer, ix, sz);
	            }
	            ix += sz;
//	            if (DEBUG) KLog.w(TAG, "encode:queueInputBuffer");
	            if (length <= 0) {
	            	// send EOS
	            	mIsEOS = true;
	            	if (DEBUG) KLog.w(TAG, "send BUFFER_FLAG_END_OF_STREAM");
	            	mMediaCodec.queueInputBuffer(inputBufferIndex, 0, 0,
	            		presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
		            break;
	            } else {
	            	mMediaCodec.queueInputBuffer(inputBufferIndex, 0, sz,
	            		presentationTimeUs, 0);
	            }
	        } else if (inputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
	        	// wait for MediaCodec encoder is ready to encode
	        	// nothing to do here because MediaCodec#dequeueInputBuffer(TIMEOUT_USEC)
	        	// will wait for maximum TIMEOUT_USEC(10msec) on each call
	        }
        }
    }

    /**
     * Method to set ByteBuffer to the MediaCodec encoder
     * @param buffer null means EOS
     * @param presentationTimeUs
     */
    @SuppressWarnings("deprecation")
	protected void encode(final ByteBuffer buffer, final int length, final long presentationTimeUs) {
//    	if (DEBUG) KLog.w(TAG, "encode:buffer=" + buffer);
    	if (!mIsCapturing) return;
    	int ix = 0, sz;
        final ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
        while (mIsCapturing && ix < length) {
	        final int inputBufferIndex = mMediaCodec.dequeueInputBuffer(TIMEOUT_USEC);
	        if (inputBufferIndex >= 0) {
	            final ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
	            inputBuffer.clear();
	            sz = inputBuffer.remaining();
	            sz = (ix + sz < length) ? sz : length - ix;
	            if (sz > 0 && (buffer != null)) {
					buffer.position(ix + sz);
					buffer.flip();
	            	inputBuffer.put(buffer);
	            }
	            ix += sz;
//	            if (DEBUG) KLog.w(TAG, "encode:queueInputBuffer");
	            if (length <= 0) {
	            	// send EOS
	            	mIsEOS = true;
	            	if (DEBUG) KLog.w(TAG, "send BUFFER_FLAG_END_OF_STREAM");
	            	mMediaCodec.queueInputBuffer(inputBufferIndex, 0, 0,
	            		presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
		            break;
	            } else {
	            	mMediaCodec.queueInputBuffer(inputBufferIndex, 0, sz,
	            		presentationTimeUs, 0);
	            }
	        } else if (inputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
	        	// wait for MediaCodec encoder is ready to encode
	        	// nothing to do here because MediaCodec#dequeueInputBuffer(TIMEOUT_USEC)
	        	// will wait for maximum TIMEOUT_USEC(10msec) on each call
	        }
        }
    }

    private boolean spsPpsSetted = false;

    /**
     * drain encoded data and write them to muxer
     */
    @SuppressWarnings("deprecation")
	protected void drain() {
    	if (mMediaCodec == null) return;
        ByteBuffer[] encoderOutputBuffers = mMediaCodec.getOutputBuffers();
        int encoderStatus, count = 0;
        final MediaMuxerWrapper muxer = mWeakMuxer.get();
        if (muxer == null) {
//        	throw new NullPointerException("muxer is unexpectedly null");
        	KLog.w(TAG, "muxer is unexpectedly null");
        	return;
        }

LOOP:	while (mIsCapturing) {


	if (isStartPush) {
		initEasyPusher(UvcApp.getApplication(), Constants.PUSHER_ADDR, Constants.PUSHER_PORT);
	} else {
		//结束pushing 
		isPushing = false;
		if (mPusher != null) {
			mPusher.stop();
			mPusher = null;
		}
	}


			// get encoded data with maximum timeout duration of TIMEOUT_USEC(=10[msec])
            encoderStatus = mMediaCodec.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // wait 5 counts(=TIMEOUT_USEC x 5 = 50msec) until data/EOS come
                if (!mIsEOS) {
                	if (++count > 5)
                		break LOOP;		// out of while
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            	if (DEBUG) KLog.w(TAG, "INFO_OUTPUT_BUFFERS_CHANGED");
                // this shoud not come when encoding
				encoderOutputBuffers = mMediaCodec.getOutputBuffers();
			} else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
				if (DEBUG) KLog.w(TAG, "INFO_OUTPUT_FORMAT_CHANGED");
				// this status indicate the output format of codec is changed
				// this should come only once before actual encoded data
				// but this status never come on Android4.3 or less
				// and in that case, you should treat when MediaCodec.BUFFER_FLAG_CODEC_CONFIG come.
				if (mMuxerStarted) {    // second time request is error
					throw new RuntimeException("format changed twice");
				}
				// get output format from codec and pass them to muxer
				// getOutputFormat should be called after INFO_OUTPUT_FORMAT_CHANGED otherwise crash.
				final MediaFormat mediaFormat = mMediaCodec.getOutputFormat(); // API >= 16

				mTrackIndex = muxer.addTrack(mediaFormat);
				mMuxerStarted = true;
				if (!muxer.start()) {
					// we should wait until muxer is ready
					synchronized (muxer) {
						while (!muxer.isStarted())
							try {
								muxer.wait(100);
							} catch (final InterruptedException e) {
								break LOOP;
							}
					}
				}

                mRtspClient.setSPSandPPS(mediaFormat.getByteBuffer("csd-0"),
                        mediaFormat.getByteBuffer("csd-1"));
                spsPpsSetted = true;
				mRtspClient.connect();


			} else {
				if (encoderStatus < 0) {
					// unexpected status
					if (DEBUG)
						KLog.w(TAG, "drain:unexpected result from encoder#dequeueOutputBuffer: " + encoderStatus);
				} else {

//                KLog.w(TAG, "pushing : " + isPushing + ", mpusher: "
//                        + (mPusher == null ? "" : mPusher.getClass().getName()));

					ByteBuffer outputBuffer;
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
						outputBuffer = mMediaCodec.getOutputBuffer(encoderStatus);
					} else {
						if (encoderOutputBuffers == null) {
							continue;
						}
						outputBuffer = encoderOutputBuffers[encoderStatus];
					}


                    //1. save mp4 file

                    if (outputBuffer == null) {
                        // this never should come...may be a MediaCodec internal error
                        throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                    }
                    if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // You shoud set output format to muxer here when you target Android4.3 or less
                        // but MediaCodec#getOutputFormat can not call here(because INFO_OUTPUT_FORMAT_CHANGED don't come yet)
                        // therefor we should expand and prepare output format from buffer data.
                        // This sample is for API>=18(>=Android 4.3), just ignore this flag here
                        if (DEBUG) KLog.w(TAG, "drain:BUFFER_FLAG_CODEC_CONFIG");
                        mBufferInfo.size = 0;
                    }

                    if (mBufferInfo.size != 0) {
                        // encoded data is ready, clear waiting counter
                        count = 0;
                        if (!mMuxerStarted) {
                            // muxer is not ready...this will prrograming failure.
                            throw new RuntimeException("drain:muxer hasn't started");
                        }

                        // write encoded data to muxer(need to adjust presentationTimeUs.
                        mBufferInfo.presentationTimeUs = getPTSUs();

                        muxer.writeSampleData(mTrackIndex, outputBuffer, mBufferInfo);
                        prevOutputPTSUs = mBufferInfo.presentationTimeUs;
                    }



					//2. pushing
					if (mRtspClient != null && isPushing) {
						KLog.w(TAG, "开始pushing ");

                        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            KLog.w(TAG, "开始pushing , 设置sps pps");

                            if (!spsPpsSetted) {
                                Pair<ByteBuffer, ByteBuffer> buffers =
                                        decodeSpsPpsFromBuffer(outputBuffer.duplicate(), mBufferInfo.size);
                                if (buffers != null) {
                                    mRtspClient.setSPSandPPS(buffers.first, buffers.second);
                                    spsPpsSetted = true;
									mRtspClient.connect();

								}
                            }
                        }

						KLog.w(TAG, String.format("push i video stamp:%d", mBufferInfo.presentationTimeUs / 1000));
						mRtspClient.sendVideo(outputBuffer, mBufferInfo);

					} /*else {
					if (mPusher != null) {
						mPusher.stop();
					}
				}*/


					//3. return buffer to encoder
					mMediaCodec.releaseOutputBuffer(encoderStatus, false);


					if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						// when EOS come.
						mMuxerStarted = false;
						mIsCapturing = false;
						break;      // out of while
					}
				}
			}
}
    }

    /**
     * decode sps and pps if the encoder never call to MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
     */
    private Pair<ByteBuffer, ByteBuffer> decodeSpsPpsFromBuffer(ByteBuffer outputBuffer,
                                                                int length) {
        byte[] mSPS = null, mPPS = null;
        byte[] csd = new byte[length];
        outputBuffer.get(csd, 0, length);
        int i = 0;
        int spsIndex = -1;
        int ppsIndex = -1;
        while (i < length - 4) {
            if (csd[i] == 0 && csd[i + 1] == 0 && csd[i + 2] == 0 && csd[i + 3] == 1) {
                if (spsIndex == -1) {
                    spsIndex = i;
                } else {
                    ppsIndex = i;
                    break;
                }
            }
            i++;
        }
        if (spsIndex != -1 && ppsIndex != -1) {
            mSPS = new byte[ppsIndex];
            System.arraycopy(csd, spsIndex, mSPS, 0, ppsIndex);
            mPPS = new byte[length - ppsIndex];
            System.arraycopy(csd, ppsIndex, mPPS, 0, length - ppsIndex);
        }
        if (mSPS != null && mPPS != null) {
            return new Pair<>(ByteBuffer.wrap(mSPS), ByteBuffer.wrap(mPPS));
        }
        return null;
    }


    /**
     * previous presentationTimeUs for writing
     */
	private long prevOutputPTSUs = 0;
	/**
	 * get next encoding presentationTimeUs
	 * @return
	 */
    protected long getPTSUs() {
		long result = System.nanoTime() / 1000L;
		// presentationTimeUs should be monotonic
		// otherwise muxer fail to write
		if (result < prevOutputPTSUs)
			result = (prevOutputPTSUs - result) + result;
		return result;
    }

}


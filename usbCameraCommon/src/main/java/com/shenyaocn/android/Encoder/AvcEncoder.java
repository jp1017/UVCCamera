package com.shenyaocn.android.Encoder;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;
import android.util.Pair;

import com.pedro.rtsp.rtsp.RtspClient;
import com.pedro.rtsp.utils.ConnectCheckerRtsp;
import com.serenegiant.app.UvcApp;
import com.socks.library.KLog;

import org.easydarwin.push.Constants;
import org.easydarwin.push.EasyPusher;
import org.easydarwin.push.InitCallback;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by ShenYao on 2014/6/21.
 */

public class AvcEncoder implements InitCallback, ConnectCheckerRtsp {
	private static final String TAG = "AvcEncoder";
	private static final String MIME_TYPE = "video/avc";

	// 设定超时，一般情况下用不到，可以设置成-1让编码器无限等待，不过要是遇上一些奇葩的设备可能app就卡死了
	private final static int TIMEOUT_USEC = 11000;


	private MediaCodec.BufferInfo mVideoBufferInfo;
	private MediaCodec mEncoderVideo;
	private MediaMuxer mMuxer;
	private int mVideoTrackIndex = -1;

	private boolean mMuxerStarted = false;
	private String recordFileName;

	private byte[] yuvTempBuffer;
	private boolean isYuv420p = true;
	private boolean bHwUvReversed = false;

	private long startWhenVideo;
	private long lastEncodedVideoTimeStamp = 0;

	private static final boolean USEEASYPUSHER = true;  //是否使用easypusher
	private static final boolean BACK_CAMERA = false;    //是否使用后置拍摄

	private EasyPusher mPusher;
	private RtspClient mRtspClient;

	/**
	 * easypusher推流状态
	 * @param code
	 */
	@Override
	public void onCallback(int code) {
		KLog.w(TAG, "pushing onCallback: " + code);
		/*PusherStatus status = new PusherStatus();
		status.setStatusNo(code);

		*//** test4$CameraFragment#postPusherStatus*//*
		EventBus.getDefault().post(status);*/

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

	public static boolean isStartPush;	//是否开始pushing
	private volatile boolean isPushing;		//是否正在pushing

	/**
	 * 初始化rtsp
	 */
	protected void initRtspClient(Context context, String videoIp, int tcpPort) {

		if (USEEASYPUSHER) {
			if (mPusher == null) {
				String id = BACK_CAMERA ? Constants.PUSHER_BACK_ID : Constants.PUSHER_DVR_ID;
				final String url = String.format("rtsp://%s:%s/%s.sdp", videoIp, tcpPort, id);

				/*PusherUrl pusherUrl = new PusherUrl();
				pusherUrl.setUrl(url);
				*//** test4$CameraFragment#postPusherUrl*//*
				EventBus.getDefault().post(pusherUrl);*/

				mPusher = new EasyPusher();

				KLog.w(TAG, "pushing : url: " + url);

				mPusher.initPush(videoIp, tcpPort + "", String.format("%s.sdp", id), Constants.KEY_EASYPUSHER,
						context.getApplicationContext(), this);

				//新版本
//                mPusher.initPush(context.getApplicationContext(), this);
//                mPusher.setMediaInfo(Pusher.Codec.EASY_SDK_VIDEO_CODEC_H264, 25, Pusher.Codec.EASY_SDK_AUDIO_CODEC_AAC, 1, 8000, 16);
//                mPusher.start(videoIp, tcpPort + "", String.format("%s.sdp", id), Pusher.TransType.EASY_RTP_OVER_TCP);

			}
		} else {
			if (mRtspClient == null) {

				String id = BACK_CAMERA ? Constants.PUSHER_BACK_ID : Constants.PUSHER_DVR_ID;
				final String url = String.format("rtsp://%s:%s/%s.sdp", videoIp, tcpPort, id);

				/*PusherUrl pusherUrl = new PusherUrl();
				pusherUrl.setUrl(url);
				*//** test4$CameraFragment#postPusherUrl*//*
				EventBus.getDefault().post(pusherUrl);*/

				mRtspClient = new RtspClient(this);

				mRtspClient.setUrl(url);
				mRtspClient.connect();
			}
		}

	}


	public static void NV21toI420(byte[] nv21, byte[] i420, int width, int height) {

		final int frameSize = width * height;
		final int qFrameSize = frameSize/4;

		System.arraycopy(nv21, 0, i420, 0, frameSize);

		for (int i = 0; i < qFrameSize; i++) {
			i420[frameSize + i] = nv21[frameSize + i*2 + 1];
			i420[frameSize + qFrameSize + i] = nv21[frameSize + i*2];
		}
	}

	public static void NV21toYV12(byte[] nv21, byte[] yv12, int width, int height) {

		final int frameSize = width * height;
		final int qFrameSize = frameSize/4;

		System.arraycopy(nv21, 0, yv12, 0, frameSize);

		for (int i = 0; i < qFrameSize; i++) {
			yv12[frameSize + qFrameSize + i] = nv21[frameSize + i*2 + 1];
			yv12[frameSize + i] = nv21[frameSize + i*2];
		}
	}

	public static void NV21toNV12(byte[] nv21, byte[] nv12, int width, int height){

		final int frameSize = width*height;
		int i = 0,j = 0;
		System.arraycopy(nv21, 0, nv12, 0, frameSize);

		for (j = 0; j < frameSize/2; j+=2) {
			nv12[frameSize + j + 1] = nv21[frameSize + j];
			nv12[frameSize + j] = nv21[frameSize + j + 1];
		}
	}

	public synchronized boolean open(String fileName, int width, int height) {
		if(Build.VERSION.SDK_INT < 18)
			return false;

		mMuxerStarted = false;

		try {
			prepareVideoEncoder(width, height);
			recordFileName = fileName;
			mMuxer = new MediaMuxer(fileName, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
			return true;
		} catch (Exception ex) {
		}
		mMuxer = null;
		mEncoderVideo = null;

		return false;
	}

	public void setHwUvReversed(boolean bReversed) {
		bHwUvReversed = bReversed;
	}

	public String getRecordFileName() {
		return recordFileName;
	}

	private void prepareVideoEncoder(int width, int height) throws IOException {
		startWhenVideo = 0;
		mVideoBufferInfo = new MediaCodec.BufferInfo();

		MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);

		format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
		format.setInteger(MediaFormat.KEY_BIT_RATE, width * height * 6);
		format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
		format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

		mEncoderVideo = MediaCodec.createEncoderByType(MIME_TYPE);

		boolean bConf = false;
		try {
			mEncoderVideo.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); // 有些设备可能不支持，会引发异常
			bConf = true;
		} catch (Exception ex) {
			Log.e("AvcEncoder", ex.getMessage());
		}

		if(!bConf) {
			format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
			mEncoderVideo.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
			isYuv420p = false;
		}

		mEncoderVideo.start();
	}

	public synchronized void close() {
		if(Build.VERSION.SDK_INT < 18)
			return;

		drainEncoder(mEncoderVideo, mVideoBufferInfo, mVideoTrackIndex, true);

		releaseEncoder();

		mMuxerStarted = false;
	}

	// 编码视频帧，里面包含yuv格式转换
	public synchronized void putFrame(byte[] pixelNv21, int width, int height) {
		if (mEncoderVideo == null) {
			return;
		}

		if(isYuv420p) {
			if (yuvTempBuffer == null || yuvTempBuffer.length != pixelNv21.length)
				yuvTempBuffer = new byte[pixelNv21.length];

			if(bHwUvReversed) {
				NV21toYV12(pixelNv21, yuvTempBuffer, width, height);
			} else {
				NV21toI420(pixelNv21, yuvTempBuffer, width, height);
			}

			VideoEncode(yuvTempBuffer, System.currentTimeMillis(), false);
		} else {

			if(!bHwUvReversed) {
				if (yuvTempBuffer == null || yuvTempBuffer.length != pixelNv21.length)
					yuvTempBuffer = new byte[pixelNv21.length];

				NV21toNV12(pixelNv21, yuvTempBuffer, width, height);
				VideoEncode(yuvTempBuffer, System.currentTimeMillis(), false);
			} else {
				VideoEncode(pixelNv21, System.currentTimeMillis(), false);
			}
		}
		mVideoTrackIndex = drainEncoder(mEncoderVideo, mVideoBufferInfo, mVideoTrackIndex, false);
	}

	public boolean isOpened() {
		return !(mMuxer == null);
	}

	public boolean isStarted() {
		return mMuxerStarted;
	}

	private void releaseEncoder() {
		if (mEncoderVideo != null) {
			mEncoderVideo.stop();
			mEncoderVideo.release();
			mEncoderVideo = null;
		}

		if (mMuxer != null) {
			try {
				mMuxer.stop();
				mMuxer.release();
				mMuxer = null;
			} catch (Exception e) {
				e.printStackTrace();
			}

		}
	}

	private void VideoEncode(final byte[] pixelsNv21, long presentationTimeMs, boolean endOfStream) {

		if (mEncoderVideo == null) {
			return;
		}

		try{
			final ByteBuffer[] inputBuffers = mEncoderVideo.getInputBuffers();
			final int inputBufferIndex = mEncoderVideo.dequeueInputBuffer(TIMEOUT_USEC);
			if (inputBufferIndex >= 0) {
				final ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
				inputBuffer.clear();
				inputBuffer.put(pixelsNv21);

				long duration = 0;

				if(startWhenVideo == 0) {
					startWhenVideo = System.currentTimeMillis();
				} else {
					duration = (presentationTimeMs - startWhenVideo) * 1001;
				}

				if (endOfStream) {
					mEncoderVideo.queueInputBuffer(inputBufferIndex, 0, pixelsNv21.length, duration, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
				} else {
					mEncoderVideo.queueInputBuffer(inputBufferIndex, 0, pixelsNv21.length, duration, MediaCodec.BUFFER_FLAG_KEY_FRAME);
				}
			}
		} catch (Exception ex){

		}
	}

    private boolean spsPpsSetted = false;
    private long mPresentTimeUs = System.nanoTime();

	private int drainEncoder(MediaCodec mediaCodec, MediaCodec.BufferInfo bufferInfo, int trackIndex, boolean endOfStream) {
		if (mMuxer == null || mediaCodec == null) {
			return trackIndex;
		}

		if (endOfStream) {
			if(mediaCodec == mEncoderVideo) {
				VideoEncode(new byte[0], System.currentTimeMillis(), true);
			}
		}

		try {
			ByteBuffer[] encoderOutputBuffers = mediaCodec.getOutputBuffers();
			byte[] mPpsSps = new byte[0];
			byte[] h264 = new byte[320 * 240];

            initRtspClient(UvcApp.getApplication(), Constants.PUSHER_ADDR, Constants.PUSHER_PORT);

			while (true) {
				int encoderStatus = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
				if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {

					if (!endOfStream) {
						break;
					}
				} else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {

					encoderOutputBuffers = mediaCodec.getOutputBuffers();
				} else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
					if (mMuxerStarted) {
						throw new RuntimeException("format changed twice");
					}

                    MediaFormat newFormat = mediaCodec.getOutputFormat();
                    if(mediaCodec == mEncoderVideo) {
						mVideoTrackIndex = mMuxer.addTrack(newFormat);
						trackIndex = mVideoTrackIndex;
						mMuxer.start();
						lastEncodedVideoTimeStamp = 0;
						mMuxerStarted = true;
					}


                    if (!USEEASYPUSHER) {
                        mRtspClient.setSPSandPPS(newFormat.getByteBuffer("csd-0"),
                                newFormat.getByteBuffer("csd-1"));
                        spsPpsSetted = true;
                        mRtspClient.connect();

                    }
				} else if (encoderStatus < 0) {

				} else {
					ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
					if (encodedData == null) {
						throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
								" was null");
					}

					if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
						bufferInfo.size = 0;
					}

					if (bufferInfo.size != 0) {
						if (!mMuxerStarted) {
							throw new RuntimeException("muxer hasn't started");
						}

						encodedData.position(bufferInfo.offset);
						encodedData.limit(bufferInfo.offset + bufferInfo.size);

						if(mediaCodec == mEncoderVideo){
							if(bufferInfo.presentationTimeUs < lastEncodedVideoTimeStamp)
								bufferInfo.presentationTimeUs = lastEncodedVideoTimeStamp += 100000;
							lastEncodedVideoTimeStamp = bufferInfo.presentationTimeUs;
						}
						if(bufferInfo.presentationTimeUs < 0){
							bufferInfo.presentationTimeUs = 0;
						}
						mMuxer.writeSampleData(trackIndex, encodedData, bufferInfo);
					}

                    //2. pushing

                    ByteBuffer encodedData_ = encoderOutputBuffers[encoderStatus];
                    if (encodedData_ == null) {
                        throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                                " was null");
                    }

                    if (!USEEASYPUSHER) {
                        if (mRtspClient != null && isPushing) {
                            KLog.w(TAG, "开始pushing ");

                            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                KLog.w(TAG, "开始pushing , 设置sps pps");

                                if (!spsPpsSetted) {
                                    Pair<ByteBuffer, ByteBuffer> buffers =
                                            decodeSpsPpsFromBuffer(encodedData_.duplicate(), bufferInfo.size);
                                    if (buffers != null) {
                                        mRtspClient.setSPSandPPS(buffers.first, buffers.second);
                                        spsPpsSetted = true;
                                        mRtspClient.connect();

                                    }
                                }
                            }

                            KLog.w(TAG, String.format("push video stamp:%d", bufferInfo.presentationTimeUs / 1000));
                            mRtspClient.sendVideo(encodedData_.duplicate(), bufferInfo);
                        }
                    } else {
                        //easypusher 推流
                        if (mPusher != null && isPushing) {

                            /*outputBuffer.position(mBufferInfo.offset);
                            outputBuffer.limit(mBufferInfo.offset + mBufferInfo.size);

                            boolean sync = false;
                            if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {// sps
                                sync = (mBufferInfo.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;
                                if (!sync) {
                                    byte[] temp = new byte[mBufferInfo.size];
                                    outputBuffer.get(temp);
                                    mPpsSps = temp;
                                    mMediaCodec.releaseOutputBuffer(encoderStatus, false);
                                    continue;
                                } else {
                                    mPpsSps = new byte[0];
                                }
                            }
                            sync |= (mBufferInfo.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;
                            int len = mPpsSps.length + mBufferInfo.size;
                            if (len > h264.length) {
                                h264 = new byte[len];
                            }
                            if (sync) {
                                System.arraycopy(mPpsSps, 0, h264, 0, mPpsSps.length);
                                outputBuffer.get(h264, mPpsSps.length, mBufferInfo.size);
                                mPusher.push(h264, 0, mPpsSps.length + mBufferInfo.size, mBufferInfo.presentationTimeUs / 1000, 1);
                                if (DEBUG) {
                                    KLog.w(TAG, String.format("push i video stamp:%d", mBufferInfo.presentationTimeUs / 1000));
                                }
                            } else {
                                outputBuffer.get(h264, 0, mBufferInfo.size);
                                mPusher.push(h264, 0, mBufferInfo.size, mBufferInfo.presentationTimeUs / 1000, 1);
                                if (DEBUG) {
                                    KLog.w(TAG, String.format("push video stamp:%d", mBufferInfo.presentationTimeUs / 1000));
                                }
                            }*/

                            ByteBuffer outputBuffer_;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                outputBuffer_ = mediaCodec.getOutputBuffer(encoderStatus);
                            } else {
//                                if (encoderOutputBuffers == null) {
//                                    continue;
//                                }
                                outputBuffer_ = encoderOutputBuffers[encoderStatus];
                            }

                            outputBuffer_.position(bufferInfo.offset);
                            outputBuffer_.limit(bufferInfo.offset + bufferInfo.size);

                            boolean sync = false;
                            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {// sps
                                sync = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;
                                if (!sync) {
                                    byte[] temp = new byte[bufferInfo.size];
                                    outputBuffer_.get(temp);
                                    mPpsSps = temp;
                                    mediaCodec.releaseOutputBuffer(encoderStatus, false);
                                    continue;
                                } else {
                                    mPpsSps = new byte[0];
                                }
                            }
                            sync |= (bufferInfo.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;
                            int len = mPpsSps.length + bufferInfo.size;
                            if (len > h264.length) {
                                h264 = new byte[len];
                            }
                            if (sync) {
                                System.arraycopy(mPpsSps, 0, h264, 0, mPpsSps.length);
                                outputBuffer_.get(h264, mPpsSps.length, bufferInfo.size);
                                mPusher.push(h264, 0, mPpsSps.length + bufferInfo.size,
                                        bufferInfo.presentationTimeUs / 1000, 1);
                                KLog.w(TAG, String.format("push i video stamp:%d", bufferInfo.presentationTimeUs / 1000));
                            } else {
                                outputBuffer_.get(h264, 0, bufferInfo.size);
                                mPusher.push(h264, 0, bufferInfo.size,
                                        bufferInfo.presentationTimeUs / 1000, 1);
                                KLog.w(TAG, String.format("push video stamp:%d", bufferInfo.presentationTimeUs / 1000));
                            }

                        } /*else {
					if (mPusher != null) {
						mPusher.stop();
					}
				}*/
                    }



                    //3. release
					mediaCodec.releaseOutputBuffer(encoderStatus, false);

					if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						break;
					}
				}
			}
		} catch (Exception ex) {
		}
		return trackIndex;
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
}
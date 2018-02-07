package com.shenyaocn.android.Encoder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import com.socks.library.KLog;

import org.easydarwin.push.Pusher;

import java.io.IOException;
import java.nio.ByteBuffer;

import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;

/**
 * Created by ShenYao on 2014/6/21.
 */

public class AvcEncoder implements Runnable {
    private static final String TAG = "AvcEncoder";
    private static final String MIME_TYPE = "video/avc";

    // 设定超时，一般情况下用不到，可以设置成-1让编码器无限等待，不过要是遇上一些奇葩的设备可能app就卡死了
    private final static int TIMEOUT_USEC = 11000;


    private MediaCodec mEncoderVideo;
    private MediaMuxer mMuxer;
    private int mVideoTrackIndex = -1;

    private long startWhenVideo;
    private long lastEncodedVideoTimeStamp = 0;

    private Pusher mPusher;

    private int mWidth;
    private int mHeight;

    public AvcEncoder(Pusher pusher) {
        mPusher = pusher;
    }

	public synchronized boolean open(String fileName, int width, int height) {
		mWidth = width;
		mHeight = height;

        try {
            prepareVideoEncoder();
            mMuxer = new MediaMuxer(fileName, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            return true;
        } catch (Exception ex) {
        }
        mMuxer = null;
        mEncoderVideo = null;

		return false;
	}

    private void prepareVideoEncoder() throws IOException {
        startWhenVideo = 0;
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight);

        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mWidth * mHeight * 6);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

		try {
			mEncoderVideo = MediaCodec.createEncoderByType(MIME_TYPE);
		} catch (IOException e) {
			e.printStackTrace();
			throw new RuntimeException("MediaCodec.createEncoderByType failed");
		}

		boolean bConf = false;
		try {
			mEncoderVideo.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); // 有些设备可能不支持，会引发异常
			bConf = true;
		} catch (Exception ex) {
			KLog.e("AvcEncoder", ex.getMessage());
		}

        if (!bConf) {
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FormatYUV420SemiPlanar);
            mEncoderVideo.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        }

        mEncoderVideo.start();

        mVideoStarted = true;

        //线程开始跑, 运行run
        new Thread(this).start();
    }

	public synchronized void close() {
		//结束录制
		videoEncode(new byte[0], System.currentTimeMillis(), true);

		releaseEncoder();
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

    private byte[] yuvTempBuffer;


	// 编码视频帧，里面包含yuv格式转换
	public synchronized void putFrame(byte[] pixelNv21) {
		if (mEncoderVideo == null) {
			return;
		}

        if (yuvTempBuffer == null || yuvTempBuffer.length != pixelNv21.length) {
            yuvTempBuffer = new byte[pixelNv21.length];
        }

        //要有这个转换, 否则录制的视频为黑白
        NV21toI420(pixelNv21, yuvTempBuffer, mWidth, mHeight);

        videoEncode(yuvTempBuffer, System.currentTimeMillis(), false);
	}

	public boolean isOpened() {
		return !(mMuxer == null);
	}

	private volatile boolean mVideoStarted;

	private void releaseEncoder() {
		if (mEncoderVideo != null) {
			mEncoderVideo.stop();
			mEncoderVideo.release();
			mEncoderVideo = null;
		}

		mVideoStarted = false;

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

    /**
     * 开始编码
     * 1. 推流
     * 2. 保存文件
     */
	public void run() {
		KLog.w(TAG, "consumer running");
		try {
			MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

			ByteBuffer[] encoderOutputBuffers = mEncoderVideo.getOutputBuffers();
			byte[] mPpsSps = new byte[0];
			byte[] h264 = new byte[mWidth * mHeight];

			while (mVideoStarted) {
                KLog.w(TAG, "consumer running ispusher; " + (mPusher != null));

                int encoderStatus = mEncoderVideo.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
				if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {

				} else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {

					encoderOutputBuffers = mEncoderVideo.getOutputBuffers();
				} else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
					MediaFormat newFormat = mEncoderVideo.getOutputFormat();
					mVideoTrackIndex = mMuxer.addTrack(newFormat);
					mMuxer.start();
					lastEncodedVideoTimeStamp = 0;
				} else {
					if (encoderStatus < 0) {

					} else {
						//easypusher 推流
						if (mPusher != null) {
							ByteBuffer outputBuffer = encoderOutputBuffers[encoderStatus];
							outputBuffer.clear();
							outputBuffer.position(bufferInfo.offset);
							outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

							boolean sync = false;
							if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {// sps
								sync = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;
								if (!sync) {
									byte[] temp = new byte[bufferInfo.size];
									outputBuffer.get(temp);
									mPpsSps = temp;
									mEncoderVideo.releaseOutputBuffer(encoderStatus, false);
									continue;
								} else {
									mPpsSps = new byte[0];
								}
							}
							sync = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;
							int len = mPpsSps.length + bufferInfo.size;
							if (len > h264.length) {
								h264 = new byte[len];
							}
							if (sync) {
								System.arraycopy(mPpsSps, 0, h264, 0, mPpsSps.length);
								outputBuffer.get(h264, mPpsSps.length, bufferInfo.size);

								bufferInfo.offset = 0;
								bufferInfo.size = mPpsSps.length + bufferInfo.size;
							} else {
								outputBuffer.get(h264, 0, bufferInfo.size);
							}

                            mPusher.push(h264, 0, bufferInfo.size, bufferInfo.presentationTimeUs / 1000, 1);
                            KLog.w(TAG, String.format("push i video stamp :%d", bufferInfo.presentationTimeUs / 1000));

                        }


						//写入文件
						if (true) {
							ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];

							if (encodedData == null) {
								throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
										" was null");
							}

							if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
								bufferInfo.size = 0;
							}

							if (bufferInfo.size != 0) {

								encodedData.clear();
								encodedData.position(bufferInfo.offset);
								encodedData.limit(bufferInfo.offset + bufferInfo.size);

								if (bufferInfo.presentationTimeUs < lastEncodedVideoTimeStamp) {
									bufferInfo.presentationTimeUs = lastEncodedVideoTimeStamp += 100000;
								}

								lastEncodedVideoTimeStamp = bufferInfo.presentationTimeUs;

								if (bufferInfo.presentationTimeUs < 0) {
									bufferInfo.presentationTimeUs = 0;
								}
								mMuxer.writeSampleData(mVideoTrackIndex, encodedData, bufferInfo);
							}
						}

						//3. release
						mEncoderVideo.releaseOutputBuffer(encoderStatus, false);

						if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
							KLog.w(TAG, "drain stream break");
							break;
						}
					}
				}
			}
		} catch (Exception ex) {
		}
	}

	/**
	 * 把视频流放入容器
	 *
	 * @param pixelsNv21
	 * @param presentationTimeMs
	 * @param endOfStream
	 */
	private void videoEncode(final byte[] pixelsNv21, long presentationTimeMs, boolean endOfStream) {

		if (mEncoderVideo == null) {
			return;
		}

		try {
			final ByteBuffer[] inputBuffers = mEncoderVideo.getInputBuffers();
			final int inputBufferIndex = mEncoderVideo.dequeueInputBuffer(TIMEOUT_USEC);
			if (inputBufferIndex >= 0) {
				final ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
				inputBuffer.clear();
				inputBuffer.put(pixelsNv21);

				long duration = 0;

				if (startWhenVideo == 0) {
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
		} catch (Exception ex) {

		}
	}

	public void setPusher(Pusher easyPusher) {
		mPusher = easyPusher;
	}
}
package com.shenyaocn.android.Adts;

/**
 * Created by ShenYao on 2017/1/19.
 */

public class Encoder {
	private long id;

	public synchronized void open(int sampleRate, int channelCount) {
		if(id != 0)
			nativeDestroyEncoder(id);

		id = nativeCreateEncoder(sampleRate, channelCount);
	}

	public synchronized boolean isOpened() {
		if(id != 0)
			return nativeIsEncoderOpened(id);

		return false;
	}

	public synchronized int getInputSampleSize() {
		if(id != 0)
			return nativeGetEncoderInputSampleSize(id);
		return 0;
	}

	public synchronized void close() {
		if(id != 0)
			nativeDestroyEncoder(id);
		id = 0;
	}

	public synchronized void submit(byte[] pcm, int len) {
		if(id != 0)
			nativeSubmitPcm(id, pcm, len);
	}

	public synchronized byte[] getEncodedData() {
		if(id != 0)
			return nativeGetEncodedData(id);
		return null;
	}

	private static boolean isLoaded;
	static {
		if (!isLoaded) {
			System.loadLibrary("faac");
			System.loadLibrary("aac");
			isLoaded = true;
		}
	}

	private final native long nativeCreateEncoder(final int sampleRate, final int channelCount);
	private final native void nativeDestroyEncoder(final long id);
	private final native boolean nativeIsEncoderOpened(final long id);
	private final native int nativeGetEncoderInputSampleSize(final long id);
	private final native void nativeSubmitPcm(final long id, final byte[] pcm, final int len);
	private final native byte[] nativeGetEncodedData(final long id);
}

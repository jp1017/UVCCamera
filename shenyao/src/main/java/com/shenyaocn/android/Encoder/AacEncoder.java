package com.shenyaocn.android.Encoder;

import com.shenyaocn.android.Adts.Encoder;

import java.io.FileOutputStream;

public class AacEncoder {

	public String getRecordFileName() {
		return recordFileName;
	}

	public boolean open(String fileName, int sampleRate, int channelCount) {
		recordFileName = fileName;
		try {
			fileOutputStream = new FileOutputStream(fileName);
			adtsEncoder.open(sampleRate, channelCount);
			return true;
		} catch (Exception e) {
		}
		return false;
	}

	public synchronized void putAudio(byte[] buffer, int length) {
		try {
			if (adtsEncoder.isOpened()) {
				adtsEncoder.submit(buffer, length);
				byte[] adts = adtsEncoder.getEncodedData();
				while (adts != null && adts.length > 0) {
					fileOutputStream.write(adts, 0, adts.length);

					adts = adtsEncoder.getEncodedData();
				}
			}
		} catch (Exception e){

		}
	}

	public synchronized boolean isOpened() {
		return adtsEncoder.isOpened();
	}

	public synchronized void close() {
		adtsEncoder.close();
		try {
			fileOutputStream.flush();
			fileOutputStream.close();
		} catch (Exception e){}
	}
	private String recordFileName;

	private Encoder adtsEncoder = new Encoder();
	private FileOutputStream fileOutputStream;
}
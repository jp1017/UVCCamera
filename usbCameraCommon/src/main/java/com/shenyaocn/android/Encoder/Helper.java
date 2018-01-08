package com.shenyaocn.android.Encoder;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;

import com.socks.library.KLog;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Created by ShenYao on 2016/12/28.
 */

public class Helper {

	public static String getVideoFileName() {
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd.HH.mm.ss");
		String recordFileName = format.format(new Date());

		String fileName = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DualCamera_test4";

		File path = new File(fileName);
		if (!path.exists())
			path.mkdirs();

		fileName += "/IPS_";
		fileName += recordFileName;
		fileName += ".mp4";
		return fileName;
	}

	public static void fileSavedProcess(Context c, String fileName) {
		c.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(new File(fileName))));
		MediaScannerConnection.scanFile(c, new String[]{fileName}, null, null);
	}

	public static AudioRecord findAudioRecord(boolean prefStereo, int[] bufferSizes) {
		final int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;
		final short[] channelConf = prefStereo? new short[] {  AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_CONFIGURATION_STEREO, AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_CONFIGURATION_MONO } : new short[] { AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_CONFIGURATION_MONO, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_CONFIGURATION_STEREO };

		for (int rate : new int[] { 48000, 24000, 12000, 16000, 8000, 44100, 22050 }) {
			for (short channelConfig : channelConf) {
				try {
					bufferSizes[0] = AudioRecord.getMinBufferSize(rate, channelConfig, audioEncoding);
					if (bufferSizes[0] != AudioRecord.ERROR_BAD_VALUE) {
						// check if we can instantiate and have a success
						bufferSizes[0] *= 2;
						AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, rate, channelConfig, audioEncoding, bufferSizes[0]);

						if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
							return recorder;
						}
					}
				} catch (Exception e) {
				}
			}
		}
		KLog.e("findAudioRecord", "Not found");
		return null;
	}
}

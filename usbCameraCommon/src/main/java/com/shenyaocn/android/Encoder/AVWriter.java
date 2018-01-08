package com.shenyaocn.android.Encoder;



import com.coremedia.iso.boxes.Container;
import com.googlecode.mp4parser.FileDataSourceImpl;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder;
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator;
import com.googlecode.mp4parser.authoring.tracks.AACTrackImpl;
import com.socks.library.KLog;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;

/**
 * Created by ShenYao on 2015/10/10.
 */
// 这部分实现音视频文件混流，因为采用音视频分别编码的方式，编码完成后是一个视频文件和一个音频文件，再将两个文件混合成一个mp4。
// 如果你有更好的编码方案比如直接将音频视频混合成完整mp4更好，因为我这边实现时候要么音频卡死要么视频卡死，所以采用这种方案，
// 不过这个方案有个优点，就是保证最后生成的mp4是网络优化过的，也就是在线播放的时候可以边缓冲边播放

public class AVWriter {
	public interface ClosedCallback {
		void closedCallback();
	}

	public AVWriter(int mode) {
		AvWriterUse=mode;
	}

	private AvcEncoder avcEncoder = new AvcEncoder(); // H.264视频编码器
	private AacEncoder aacEncoder = new AacEncoder(); // AAC音频编码器
	private String recordFileName;

	public boolean isSaved() {
		return isSaved;
	}

	public void setSaved(boolean saved) {
		isSaved = saved;
	}

	private boolean isSaved = false;

	private boolean bOpened = false;
	private static boolean bWriteEnd = true;
	private static final String TAG = "AVWriter";
	public int AvWriterUse=0;

	public boolean open(String fileName, int width, int height, int sampleRate, int channelCount) {
		recordFileName = fileName;

		if(sampleRate > 0 && channelCount > 0) {
			bOpened = (avcEncoder.open(fileName + ".h264", width, height) && aacEncoder.open(fileName + ".aac", sampleRate, channelCount));
			//有音频的话视频文件名以.h264作为后缀名，和音频混流后将得到新的mp4文件，.h264这个文件会被删除
		} else {
			bOpened = avcEncoder.open(fileName, width, height);
			//没有音频的话只要初始化视频编码器即可，因为没有音频文件，所以不必要混流，视频文件名直接以.mp4作后缀名即可
		}
		//bWriteEnd=false;
		return bOpened;
	}

	public String getRecordFileName() {
		return recordFileName;
	}

	//下面大部分函数都映射到各自的编码器了

	// 实现UV分量对调，建议通过 设置 给用户提供是否开启的选项，貌似有些机器编码后颜色会出错，所以提供这个选项可以让用户在颜色出错时调整过来
	public void setHwUvReversed(boolean bReversed) {
		if(avcEncoder != null)
			avcEncoder.setHwUvReversed(bReversed);
	}

	public boolean isOpened() {
		return bOpened;
	}

	public boolean isWriteEnd() {
		return bWriteEnd;
	}

	public boolean isVideoEncoderOpened() {
		return avcEncoder.isOpened();
	}

	public boolean isAudioEncoderOpened() {
		return  aacEncoder.isOpened();
	}

	public void close() {
		close(null);
	}

	public void close(ClosedCallback callback) {
		KLog.w(TAG,"**********AVWriter close");
		if(avcEncoder.isOpened()) {
			KLog.w(TAG,"**********avcEncoder will close");
			avcEncoder.close();
		}

		if(aacEncoder.isOpened()) {
			KLog.w(TAG,"**********aacEncoder will close");
			aacEncoder.close();

			muxProcess(recordFileName, avcEncoder.getRecordFileName(), aacEncoder.getRecordFileName(), callback);
		} else {
			//muxProcess(recordFileName, avcEncoder.getRecordFileName(), null, callback);
			//bWriteEnd=true;
			//KLog.w(TAG,"**********avWriterR close End");
		}

		bOpened = false;
	}

	public void putFrame(byte[] pixelNv21, int width, int height) {
		avcEncoder.putFrame(pixelNv21, width, height);
	}

	public void putAudio(byte[] buffer, int length) {
		aacEncoder.putAudio(buffer, length);
	}

	private static void muxProcess(final String outPutFile, final String videoFile, final String audioFile, final ClosedCallback callback) {
		KLog.w(TAG,"**********muxProcess");
		new Thread(new Runnable() {
			@Override
			public void run() {
				muxAudioVideo(outPutFile, videoFile, audioFile, callback);
			}
		}).start(); // 开启一个线程用于音视频混流，完成后 callback回调，实现异步操作
	}

	private static void muxAudioVideo(String outPutFile, String videoFile, String audioFile, ClosedCallback callback) {

		try {
			KLog.w(TAG,"**********muxAudioVideo,bWriteEnd="+bWriteEnd);
			Movie movie = MovieCreator.build(videoFile);
			File file=new File(audioFile);
			if(file.renameTo(file)){
			}else{
				KLog.w(TAG,"**********aacEncoder not opened");
				Thread.sleep(2000);
			}
			AACTrackImpl aacTrack = new AACTrackImpl(new FileDataSourceImpl(audioFile));
			movie.addTrack(aacTrack);

			Container mp4file = new DefaultMp4Builder().build(movie);
			FileChannel fc = new FileOutputStream(new File(outPutFile)).getChannel();
			mp4file.writeContainer(fc);
			fc.close();
			bWriteEnd= true;

			new File(videoFile).delete();
			new File(audioFile).delete();//混流后音视频文件就没用了，删除！！！
			KLog.w(TAG,"**********88888888muxAudioVideo write end,bWriteEnd="+bWriteEnd);

			// 注意后期混淆代码时避免混淆com.googlecode.mp4parser这个包导致app录像时闪退！！！

		} catch (Exception ex) {
			//fc.close();
			bWriteEnd = true;
			new File(videoFile).renameTo(new File(outPutFile));
			KLog.w(TAG,"**********videoFile rebuild");
			ex.printStackTrace();	// 如果audioFile == null，则引发此异常，直接输出视频文件，故audioFile == null时直接输出视频
		}
		if(callback != null) {
			callback.closedCallback(); // 通知编码完成
		}
	}
}

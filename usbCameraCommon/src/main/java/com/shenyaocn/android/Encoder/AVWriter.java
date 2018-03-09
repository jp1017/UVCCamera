/*
******************* Copyright (c) ***********************\
**
**         (c) Copyright 2018 蒋朋 china sxkj. sd
**                  All Rights Reserved
**
**                 By(青岛世新科技有限公司)
**                    www.qdsxkj.com
**
**                       _oo0oo_
**                      o8888888o
**                      88" . "88
**                      (| -_- |)
**                      0\  =  /0
**                    ___/`---'\___
**                  .' \\|     |// '.
**                 / \\|||  :  |||// \
**                / _||||| -:- |||||- \
**               |   | \\\  -  /// |   |
**               | \_|  ''\---/''  |_/ |
**               \  .-\__  '-'  ___/-. /
**             ___'. .'  /--.--\  `. .'___
**          ."" '<  `.___\_<|>_/___.' >' "".
**         | | :  `- \`.;`\ _ /`;.`/ - ` : | |
**         \  \ `_.   \_ __\ /__ _/   .-` /  /
**     =====`-.____`.___ \_____/___.-`___.-'=====
**                       `=---='
**
**
**     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
**
**               佛祖保佑         永无BUG
**
**
**                   南无本师释迦牟尼佛
**

**----------------------版本信息------------------------
** 版    本: V0.1
**
******************* End of Head **********************\
*/

package com.shenyaocn.android.Encoder;

import com.socks.library.KLog;

import org.easydarwin.push.Pusher;

/**
 * 文 件 名: AVWriter
 * 说   明: 这部分实现音视频文件混流，因为采用音视频分别编码的方式，编码完成后是一个视频文件和一个音频文件，再将两个文件混合成一个mp4。
 如果你有更好的编码方案比如直接将音频视频混合成完整mp4更好，因为我这边实现时候要么音频卡死要么视频卡死，所以采用这种方案，
 不过这个方案有个优点，就是保证最后生成的mp4是网络优化过的，也就是在线播放的时候可以边缓冲边播放
 * 创 建 人: 蒋朋
 * 创建日期: 16-5-24 11:26
 * 邮   箱: jp19891017@gmail.com
 * 博   客: http://jp1017.github.io
 * 修改时间：
 * 修改备注：
 */

public class AVWriter {
	private static final String TAG = "AVWriter";

	private AvcEncoder avcEncoder; // H.264视频编码器
//	private AacEncoder aacEncoder; // AAC音频编码器

	private String recordFileName;

	private boolean bOpened = false;
	public boolean isDvr;

	public interface ClosedCallback {
		void closedCallback();
	}

	/**
	 * 设置easypusher
	 * @param easyPusher
	 */
	public void setPusher(Pusher easyPusher) {
        if (avcEncoder == null) {
            avcEncoder = new AvcEncoder();
        }

		avcEncoder.setPusher(easyPusher);
	}

	public boolean open(String fileName, int width, int height, int i, int j) {
		if (avcEncoder == null) {
			avcEncoder = new AvcEncoder();
		}

		recordFileName = fileName;

		bOpened = avcEncoder.open(fileName, width, height);
		return bOpened;
	}

	public String getRecordFileName() {
		return recordFileName;
	}

	public boolean isOpened() {
		return bOpened;
	}

	public boolean isVideoEncoderOpened() {
		return avcEncoder.isOpened();
	}

	public boolean isAudioEncoderOpened() {
//		return aacEncoder != null && aacEncoder.isOpened();
		return false;
	}

	public void close(ClosedCallback callback) {
		KLog.w(TAG, "AVWriter close");
		if (isVideoEncoderOpened()) {
			KLog.w(TAG, "avcEncoder will close");
			avcEncoder.close();
		}

        if (isAudioEncoderOpened()) {
            KLog.w(TAG, "aacEncoder will close");
//            aacEncoder.close();

//            muxProcess(recordFileName, avcEncoder.getRecordFileName(), aacEncoder.getRecordFileName(), callback);
        } else {
            // 通知编码完成, 单独视频
            if (callback != null) {
                callback.closedCallback();
            }
        }

		bOpened = false;
	}

	public void putFrame(byte[] pixelNv21) {
		avcEncoder.putFrame(pixelNv21);
	}
}

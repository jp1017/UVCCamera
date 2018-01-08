package com.shenyaocn.android.dualcamera;

import android.app.Activity;
import android.app.ActivityManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.usb.UsbDevice;
import android.media.AudioRecord;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.DeviceFilter;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.widget.UVCCameraTextureView;
import com.shenyaocn.android.Encoder.AVWriter;

import org.easydarwin.sw.TxtOverlay;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;


public final class MainActivity extends Activity implements CameraDialog.CameraDialogParent {
    private static final boolean DEBUG = true;    // 用于显示调试信息
    private static final String TAG = "MainActivity";

    private AVWriter avWriterL = new AVWriter(1);    // 用于左边摄像头录像
    private AVWriter avWriterR = new AVWriter(2);    // 用于右边摄像头录像

    private AudioThread audioThread;                // 录音线程
    private AudioRecord audioRecord;
    private int bufferSize;

    private USBMonitor mUSBMonitor;                    // 用于监视USB设备接入
    private UVCCamera mUVCCameraL;                    // 表示左边摄像头设备
    private UVCCamera mUVCCameraR;                    // 表示右边摄像头设备

    private OutputStream snapshotOutStreamL;        // 用于左边摄像头拍照
    private String snapshotFileNameL;

    private OutputStream snapshotOutStreamR;        // 用于右边摄像头拍照
    private String snapshotFileNameR;

    private static final float BANDWIDTH_FACTORS = 0.5f;

    private int currentWidth = UVCCamera.DEFAULT_PREVIEW_WIDTH / 2;
    private int currentHeight = UVCCamera.DEFAULT_PREVIEW_HEIGHT / 2;

    private UVCCameraTextureView mUVCCameraViewR;    // 用于右边摄像头预览
    private Surface mRightPreviewSurface;

    private UVCCameraTextureView mUVCCameraViewL;    // 用于左边摄像头预览
    private Surface mLeftPreviewSurface;

    private ImageButton mRecordButton;
    private ImageButton mCaptureButton;
    private boolean isRecord = false;
    private long startTakeVideoTime = System.currentTimeMillis();
    private Timer mTimer;
    private TextView tvRecordTime;
    TimerTask timerTask;
    Handler handler;
    private Button btn_back;
    private LinearLayout layout_camera;
    List<String> list;
    private int position = -1;
    MediaAdapter arrayAdapter;
    private ToggleButton mToggleBtn;
    Thread threadTestCamera = null;
    boolean threadRunning;
    private static final int TEST_MSEC_PER_MIN = 30000;

    private static final int FORMAT_CALLBACK = UVCCamera.PIXEL_FORMAT_NV21;

    //水印
    private TxtOverlay mTxtOverlay;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // 避免屏幕关闭
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_camera_show);

        //        refreshControls();
        mUSBMonitor = new USBMonitor(this, mOnDeviceConnectListener);
        final List<DeviceFilter> filters = DeviceFilter.getDeviceFilters(this, R.xml.device_filter);
        mUSBMonitor.setDeviceFilter(filters);

//        com.rscja.deviceapi.OTG.getInstance().on(); //打开OTG
        mUVCCameraViewL = (UVCCameraTextureView) findViewById(R.id.camera_view_L);
        mUVCCameraViewL.setAspectRatio(UVCCamera.DEFAULT_PREVIEW_WIDTH / (float) UVCCamera.DEFAULT_PREVIEW_HEIGHT);

        mUVCCameraViewR = (UVCCameraTextureView) findViewById(R.id.camera_view_R);
        mUVCCameraViewR.setAspectRatio(UVCCamera.DEFAULT_PREVIEW_WIDTH / (float) UVCCamera.DEFAULT_PREVIEW_HEIGHT);

        mRecordButton = (ImageButton) findViewById(R.id.record_button);
        mRecordButton.setOnClickListener(mOnClickListener);

        mCaptureButton = (ImageButton) findViewById(R.id.capture_button);
        mCaptureButton.setOnClickListener(mOnClickListener);

        mUVCCameraViewL.setOnClickListener(mOnClickListener);
        mUVCCameraViewR.setOnClickListener(mOnClickListener);
        tvRecordTime = (TextView) findViewById(R.id.tvRecordTime);

        mTxtOverlay = new TxtOverlay(this);
        mTxtOverlay.init(currentWidth, currentHeight, Environment.getExternalStorageDirectory()
                + "/SIMYOU.ttf");

        initView();

        initOnclick();

        handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                switch (msg.what) {
                    case 0x100:
                        if (isRecord) {
                            long durantionTime = System.currentTimeMillis() - startTakeVideoTime;
                            durantionTime = durantionTime / 1000;
                            String sRunTime;
                            if (durantionTime >= 3600) {
                                sRunTime = String.format("%d:%02d:%02d", durantionTime / 3600, durantionTime % 3600 / 60, durantionTime % 60);
                            } else {
                                sRunTime = String.format("%d:%02d", durantionTime / 60, durantionTime % 60);
                            }
                            tvRecordTime.setText(sRunTime);
                        } else {
                            tvRecordTime.setText("");
                        }
                        break;
                }
            }
        };

    }

    @Override
    protected void onStart() {
        super.onStart();
        mUSBMonitor.register();
        //refreshControls();
        mTimer = new Timer();
        timerTask = new TimerTask() {
            @Override
            public void run() {
                handler.sendEmptyMessage(0x100);
            }
        };
        mTimer.schedule(timerTask, 1000, 1000);

//        CameraDialog.showDialog(MainActivity.this);
    }

    @Override
    protected void onStop() {
        mUSBMonitor.unregister();

        super.onStop();
    }

    @Override
    protected void onPause() {
        Log.w(TAG, "***********onPause");

        synchronized (this) {
            if (mUVCCameraL != null) {
                mUVCCameraL.stopPreview();
            }
            if (mUVCCameraR != null) {
                mUVCCameraR.stopPreview();
            }
            stopRecord(avWriterL);
            stopRecord(avWriterR);
            //releaseCameraL();
            //releaseCameraR();
        }
        // Activity暂停时要释放左右摄像头并停止录像
        super.onPause();
    }

    //递归删除文件夹
    public static void deleteFile(File file) {
        if (file.exists()) {//判断文件是否存在
            if (file.isFile()) {//判断是否是文件
                file.delete();//删除文件
            } else if (file.isDirectory()) {//否则如果它是一个目录
                File[] files = file.listFiles();//声明目录下所有的文件 files[];
                for (int i = 0; i < files.length; i++) {//遍历目录下所有的文件
                    deleteFile(files[i]);//把每个文件用这个方法进行迭代
                }
                file.delete();//删除文件夹
            }
        } else {
            Log.e(TAG, "所删除的文件不存在");
        }
    }

    long startTestTime;

    private void initOnclick() {
        //进入文件查看页面
        /*btn_takelook.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                list.clear();
                layout_camera.setVisibility(View.GONE);
                showListvideo();
                if (arrayAdapter != null) {
                    arrayAdapter.notifyDataSetChanged();
                }
            }
        });*/

        startTestTime = System.currentTimeMillis();
        mToggleBtn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
                if (mUVCCameraL == null && mUVCCameraR == null) {
                    ToastUtil.show(MainActivity.this, "摄像头未连接!");
                    return;
                }

                if (isChecked) {
                    if (System.currentTimeMillis() - startTestTime > 3000) {
                        ToastUtil.show(MainActivity.this, "开启摄像头测试");
                        threadRunning = true;
                        threadTestCamera = new AutoTestCamera();
                        threadTestCamera.start();
                        startTestTime = System.currentTimeMillis();
                    } else {
                        ToastUtil.show(MainActivity.this, "请稍后打开...");
                    }
                } else {
                    if (System.currentTimeMillis() - startTestTime > 3000) {
                        threadRunning = false;
                        startTestTime = System.currentTimeMillis();
                        ToastUtil.show(MainActivity.this, "摄像头测试关闭");
                    } else {
                        ToastUtil.show(MainActivity.this, "请稍后关闭...");
                    }
                }
            }
        });
    }

    private void initView() {
        btn_back = (Button) findViewById(R.id.btn_back);
        mToggleBtn = (ToggleButton) findViewById(R.id.toggle_btn);
        btn_back.setOnClickListener(mOnClickListener);
        layout_camera = (LinearLayout) findViewById(R.id.layout_camera);
        list = new LinkedList<>();
    }

    private void showListvideo() {
        String path = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DualCamera";
        File file = new File(path);
        if (!file.exists()) {
            file.mkdirs();
            return;
        }
        File[] filelist = file.listFiles();
        String name = "";
        String[] s = new String[]{};
        for (int i = 0; i < filelist.length; i++) {
            s = filelist[i].getAbsolutePath().split("/");
            name = s[s.length - 1];
            list.add(name);
        }
    }

    private synchronized void releaseCameraL() {
        synchronized (this) {
            if (avWriterL.isOpened()) {
                stopRecord(avWriterL);
            }
            if (mUVCCameraL != null) {
                try {
                    //mUVCCameraL.stopPreview();
                    mUVCCameraL.setStatusCallback(null);
                    mUVCCameraL.setButtonCallback(null);
                    mUVCCameraL.close();
                    mUVCCameraL.destroy();
                } catch (final Exception e) {
                    Log.e(TAG, e.getMessage());
                } finally {
                    Log.w(TAG, "*******releaseCameraL mUVCCameraL=null");
                    mUVCCameraL = null;
                }
            }

            if (mLeftPreviewSurface != null) {
                mLeftPreviewSurface.release();
                mLeftPreviewSurface = null;
            }
        }
    }

    private synchronized void releaseCameraR() {
        synchronized (this) {
            if (avWriterR.isOpened()) {
                stopRecord(avWriterR);
            }
            if (mUVCCameraR != null) {
                try {
                    //mUVCCameraR.stopPreview();
                    mUVCCameraR.setStatusCallback(null);
                    mUVCCameraR.setButtonCallback(null);
                    mUVCCameraR.close();
                    mUVCCameraR.destroy();
                } catch (final Exception e) {
                    Log.e(TAG, e.getMessage());
                } finally {
                    Log.w(TAG, "*******mUVCCameraR mUVCCameraR=null");
                    mUVCCameraR = null;
                }
            }

            if (mRightPreviewSurface != null) {
                mRightPreviewSurface.release();
                mRightPreviewSurface = null;
            }
            //findViewById(R.id.textViewUVCPromptR).setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        releaseCameraL();
        releaseCameraR();
        stopAudio();

        if (mUSBMonitor != null) {
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }
        threadRunning = false;
        super.onDestroy();
    }

    private void processAudio(byte[] buffer, int length) {
        if (avWriterL.isAudioEncoderOpened()) {
            avWriterL.putAudio(buffer, length);
        }

        if (avWriterR.isAudioEncoderOpened()) {
            avWriterR.putAudio(buffer, length);
        }
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
                Log.e("AudioThread", e.getMessage());
            }
        }

        public void cancel() {
            bRun = false;
        }
    }

    // 开始麦克风捕获
    private synchronized boolean startAudio() {

        if (audioRecord != null || audioThread != null) // 如果已经打开了就直接返回
            return true;

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

    // 实现快照抓取
    private synchronized void captureSnapshot() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd.HH.mm.ss");
        Date currentTime = new Date();

        if (mUVCCameraL != null) {
            snapshotFileNameL = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DualCamera";
            snapshotFileNameL += "/IPC_";
            snapshotFileNameL += format.format(currentTime);
            snapshotFileNameL += ".L.jpg";
            File recordFile = new File(snapshotFileNameL);    // 左边摄像头快照的文件名
            if (recordFile.exists()) {
                recordFile.delete();
            }
            try {
                recordFile.createNewFile();
                snapshotOutStreamL = new FileOutputStream(recordFile);
            } catch (Exception e) {
            }
        }

        if (mUVCCameraR != null) {
            snapshotFileNameR = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DualCamera";

            snapshotFileNameR += "/IPC_";
            snapshotFileNameR += format.format(currentTime);
            snapshotFileNameR += ".R.jpg";
            File recordFile = new File(snapshotFileNameR);        // 右边摄像头快照的文件名
            if (recordFile.exists()) {
                recordFile.delete();
            }
            try {
                recordFile.createNewFile();
                snapshotOutStreamR = new FileOutputStream(recordFile);
            } catch (Exception e) {
            }
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

    private void startRecord(AVWriter avWriter, UVCCamera uvcCamera) {
        if (avWriter.isOpened()) {
            return;
        }

        if (uvcCamera == null) {
            return;
        }

        String extra = "L";

        if (uvcCamera == mUVCCameraR) // 通过判断是左边还是右边的摄像头来调整文件名
            extra = "R";

        String fileName = Helper.getVideoFileName(extra); // 可以参考函数实现

        openAVWriter(fileName, avWriter, uvcCamera);

        if (avWriter.isOpened()) {
            startRecTimer(); // 如果编码器打开成功，则开始录像计时
        }
    }

    // 录像计时器的实现部分
    public static String sec2time(int second) {
        int h = 0;
        int d = 0;
        int s = 0;
        int temp = second % 3600;
        if (second > 3600) {
            h = second / 3600;
            if (temp != 0) {
                if (temp > 60) {
                    d = temp / 60;
                    if (temp % 60 != 0) {
                        s = temp % 60;
                    }
                } else {
                    s = temp;
                }
            }
        } else {
            d = second / 60;
            if (second % 60 != 0) {
                s = second % 60;
            }
        }
        return String.format("%02d:%02d:%02d", h, d, s);
    }

    private int timer_count = 0;
    private Timer timer;

    public String getRecordTimeCount() {
        return sec2time(timer_count) + (timer_count % 2 == 0 ? " REC" : "");
    }

    private void startRecTimer() {
        stopRecTimer();
        timer_count = 0;

        timer = new Timer(true);
        timer.schedule(new TimerTask() {
            public void run() {
                timer_count++;
            }
        }, 1000, 1000);
    }

    private void stopRecTimer() {
        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null;
        }
        timer_count = 0;
    }

    private void stopRecord(AVWriter avWriter) {
        stopRecTimer();

        if (!avWriter.isOpened()) {
            return;
        }

        final String fileName = avWriter.getRecordFileName();
        avWriter.close(new AVWriter.ClosedCallback() {
            @Override
            public void closedCallback() {    // 录像文件编码完成时的异步回调
                Helper.fileSavedProcess(MainActivity.this, fileName); // 通知媒体扫描器扫描媒体文件以便在图库等app可见，可以参见函数实现
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "\"" + fileName + "\"", Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private final OnClickListener mOnClickListener = new OnClickListener() {
        @Override
        public void onClick(final View view) {
            switch (view.getId()) {
                case R.id.capture_button:
                    captureSnapshot();
                    //playSound(R.raw.take_photo_play);
                    break;
                case R.id.record_button:
                    if (mUVCCameraL != null) {
                        if (avWriterL.isOpened()) {
                            stopRecord(avWriterL);
                            //playSound("停止录像");
                            isRecord = false;
                        } else {
                            startRecord(avWriterL, mUVCCameraL);
                            //playSound("开始录像");
                            isRecord = true;
                            startTakeVideoTime = System.currentTimeMillis();
                        }
                    }

                    if (mUVCCameraR != null) {
                        if (avWriterR.isOpened()) {
                            stopRecord(avWriterR);
                            //playSound("停止录像");
                            isRecord = false;
                        } else {
                            startRecord(avWriterR, mUVCCameraR);
                            //playSound("开始录像");
                            isRecord = true;
                            startTakeVideoTime = System.currentTimeMillis();
                        }
                    }
                    //开启麦克风捕获
//                    if (mUVCCameraL != null || mUVCCameraR != null)
//                        startAudio();
                    // 实现录像功能，分别开始左右摄像头的录像
//                    refreshControls();
                    break;
                case R.id.btn_back:
                    finish();
                    break;
            }
        }
    };

    private final OnDeviceConnectListener mOnDeviceConnectListener = new OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            if (DEBUG) Log.w(TAG, "onAttach:" + device);
            final List<UsbDevice> list = mUSBMonitor.getDeviceList();
            mUSBMonitor.requestPermission(list.get(0));

            if (list.size() > 1) {
                handler.postDelayed(new Runnable() {
                    public void run() {
                        mUSBMonitor.requestPermission(list.get(1));
                    }
                }, 500);
            }
        }


        @Override
        public void onConnect(final UsbDevice device, final UsbControlBlock ctrlBlock, final boolean createNew) {
            if (DEBUG) Log.w(TAG, "onConnect:" + ctrlBlock.getVenderId());
            synchronized (this) {
                if (mUVCCameraL != null && mUVCCameraR != null) { // 如果左右摄像头都打开了就不能再接入设备了
                    return;
                }
                if (ctrlBlock.getVenderId() != 2) {
                    if (mUVCCameraL != null && mUVCCameraL.getDevice().equals(device)) {
                        return;
                    }
                } else if (ctrlBlock.getVenderId() != 3) {
                    if ((mUVCCameraR != null && mUVCCameraR.getDevice().equals(device))) {
                        return;
                    }
                } /*else {
                    return;
                }*/
                final UVCCamera camera = new UVCCamera();
                final int open_camera_nums = (mUVCCameraL != null ? 1 : 0) + (mUVCCameraR != null ? 1 : 0);
                if (ctrlBlock != null)
                    camera.open(ctrlBlock);
                else {
                    return;
                }
                try {
                    camera.setPreviewSize(currentWidth, currentHeight, UVCCamera.FRAME_FORMAT_MJPEG, BANDWIDTH_FACTORS); // 0.5f是一个重要参数，表示带宽可以平均分配给两个摄像头，如果是一个摄像头则是1.0f，可以参考驱动实现
                } catch (final IllegalArgumentException e1) {
                    Log.e("FRAME_FORMAT", "MJPEG Failed");
                    try {
                        camera.setPreviewSize(currentWidth, currentHeight, UVCCamera.DEFAULT_PREVIEW_MODE, BANDWIDTH_FACTORS);
                    } catch (final IllegalArgumentException e2) {
                        try {
                            currentWidth = UVCCamera.DEFAULT_PREVIEW_WIDTH;
                            currentHeight = UVCCamera.DEFAULT_PREVIEW_HEIGHT;
                            camera.setPreviewSize(currentWidth, currentHeight, UVCCamera.DEFAULT_PREVIEW_MODE, BANDWIDTH_FACTORS);
                        } catch (final IllegalArgumentException e3) {
                            camera.destroy();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, "UVC设备错误", Toast.LENGTH_LONG).show();
                                }
                            });

                            return;
                        }
                    }
                }

                // 将摄像头进行分配
                if (ctrlBlock.getVenderId() != 2 && mUVCCameraL == null) {
                    mUVCCameraL = camera;
                    try {
                        if (mLeftPreviewSurface != null) {
                            mLeftPreviewSurface.release();
                            mLeftPreviewSurface = null;
                        }

                        final SurfaceTexture st = mUVCCameraViewL.getSurfaceTexture();
                        if (st != null) {
                            Log.w(TAG, "*******mUVCCameraViewL.getSurfaceTexture ok");
                            mLeftPreviewSurface = new Surface(st);
                            if (mLeftPreviewSurface != null) {
                                Log.w(TAG, "*******mLeftPreviewSurface create ok");
                            }
                        }
                        mUVCCameraL.setPreviewDisplay(mLeftPreviewSurface);

                        mUVCCameraL.setFrameCallback(mUVCFrameCallbackL, FORMAT_CALLBACK);
                        mUVCCameraL.startPreview();
                    } catch (final Exception e) {
                        Log.e(TAG, e.getMessage());
                    }
                } else if (ctrlBlock.getVenderId() != 3 && mUVCCameraR == null) {
                    mUVCCameraR = camera;
                    if (mRightPreviewSurface != null) {
                        mRightPreviewSurface.release();
                        mRightPreviewSurface = null;
                    }

                    final SurfaceTexture st = mUVCCameraViewR.getSurfaceTexture();
                    if (st != null) {
                        Log.w(TAG, "*******mRightPreviewSurface create ok");
                        mRightPreviewSurface = new Surface(st);
                        if (mRightPreviewSurface != null)
                            Log.w(TAG, "*******mRightPreviewSurface create ok");
                    }
                    mUVCCameraR.setPreviewDisplay(mRightPreviewSurface);

                    mUVCCameraR.setFrameCallback(mUVCFrameCallbackR, FORMAT_CALLBACK);
                    mUVCCameraR.startPreview();
                }

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        //refreshControls();
                        if (mUVCCameraL != null || mUVCCameraR != null)
                            startAudio();
                    }
                });
            }
        }

        @Override
        public void onDisconnect(final UsbDevice device, final UsbControlBlock ctrlBlock) {
            if (DEBUG) Log.w(TAG, "onDisconnect:" + device);
//            if ((mUVCCameraL != null) && mUVCCameraL.getDevice().equals(device)) {
//                runOnUiThread(new Runnable() {
//                    @Override
//                    public void run() {
//                        releaseCameraL();
//                    }
//                });
//
//            } else if ((mUVCCameraR != null) && mUVCCameraR.getDevice().equals(device)) {
//                runOnUiThread(new Runnable() {
//                    @Override
//                    public void run() {
//                        releaseCameraR();
//                    }
//                });
//            }

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    //refreshControls();
                    if (mUVCCameraL == null && mUVCCameraR == null)
                        stopAudio();
                }
            });
        }

        @Override
        public void onDettach(final UsbDevice device) {
//            if (DEBUG) Log.w(TAG, "onDettach:" + device);
            //Toast.makeText(MainActivity.this, "USB设备断开", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onCancel(final UsbDevice device) {
            if (DEBUG) Log.w(TAG, "onCancel:");
        }
    };

    private String getCurrentTime() {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return simpleDateFormat.format(new Date());
    }

    // 左边摄像头的NV21视频帧回调
    private final IFrameCallback mUVCFrameCallbackL = new IFrameCallback() {
        @Override
        public void onFrame(final ByteBuffer frame) {
            if (mUVCCameraL == null)
                return;

            final Size size = mUVCCameraL.getPreviewSize();
            byte[] buffer = null;

            int FrameSize = frame.remaining();
            if (buffer == null) {
                buffer = new byte[FrameSize];
                frame.get(buffer);
            }

            //添加水印
            mTxtOverlay.overlay(buffer, "L: " + getCurrentTime());

            if (avWriterL.isVideoEncoderOpened()) { // 将视频帧发送到编码器
                avWriterL.putFrame(buffer, size.width, size.height);
            }

            if (snapshotOutStreamL != null) { // 将视频帧压缩成jpeg图片，实现快照捕获
                if (!(FrameSize < size.width * size.height * 3 / 2) && (buffer != null)) {
                    try {
                        new YuvImage(buffer, ImageFormat.NV21, size.width, size.height, null).compressToJpeg(new Rect(0, 0, size.width, size.height), 90, snapshotOutStreamL);
                        snapshotOutStreamL.flush();
                        snapshotOutStreamL.close();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                            Toast.makeText(MainActivity.this, "\"" + snapshotFileNameL + "\"", Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (Exception ex) {
                    } finally {
                        buffer = null;
                        snapshotOutStreamL = null;
                    }
                }
            }
        }
    };

    // 参考上面的注释
    private final IFrameCallback mUVCFrameCallbackR = new IFrameCallback() {
        @Override
        public void onFrame(final ByteBuffer frame) {

            if (mUVCCameraR == null)
                return;

            final Size size = mUVCCameraR.getPreviewSize();
            byte[] buffer = null;

            int FrameSize = frame.remaining();
            if (buffer == null) {
                buffer = new byte[FrameSize];
                frame.get(buffer);
            }

            //添加水印
            mTxtOverlay.overlay(buffer, "R: " + getCurrentTime());

            if (avWriterR.isVideoEncoderOpened()) {
                avWriterR.putFrame(buffer, size.width, size.height);
            }

            if (snapshotOutStreamR != null) {

                if (!(FrameSize < size.width * size.height * 3 / 2) && (buffer != null)) {
                    try {
                        new YuvImage(buffer, ImageFormat.NV21, size.width, size.height, null).compressToJpeg(new Rect(0, 0, size.width, size.height), 90, snapshotOutStreamR);
                        snapshotOutStreamR.flush();
                        snapshotOutStreamR.close();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                            Toast.makeText(MainActivity.this, "\"" + snapshotFileNameR + "\"", Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (Exception ex) {
                    } finally {
                        buffer = null;
                        snapshotOutStreamR = null;
                    }
                }
            }
        }
    };

    /**
     * to access from CameraDialog
     *
     * @return
     */
    @Override
    public USBMonitor getUSBMonitor() {
        return mUSBMonitor;
    }

    @Override
    public void onDialogResult(boolean canceled) {

    }

    private boolean IsCameraAvail() {
        return (mUVCCameraL != null || mUVCCameraR != null);
    }

    private void displayBriefMemory() {
        final ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(info);
        String memoryInfo = String.format("Memory-> availMem:%dMB totalMem:%dMB", info.availMem / 1000000, info.totalMem / 1000000);
        if (DEBUG) Log.w(TAG, memoryInfo);
//        Log.w(tag,"系统剩余内存:"+(info.availMem >> 10)+"k");
//        Log.w(tag,"系统是否处于低内存运行："+info.lowMemory);
//        Log.w(tag,"当系统剩余内存低于"+info.threshold+"时就看成低内存运行");
    }

    void restartUseCamera() {
        if (!mUSBMonitor.isRegistered()) {
            mUSBMonitor.register();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private boolean IsAVWriterFinish() {
        return (avWriterL.isWriteEnd());
    }

    private final class AutoTestCamera extends Thread {
        @Override
        public void run() {
            super.run();
            long takePhotoTime = System.currentTimeMillis();
            long recordingInterval = System.currentTimeMillis();
            long recordingDuration = System.currentTimeMillis();
            long saveTrainVideoTimeout = System.currentTimeMillis();
            boolean isToBeUse = false;
            boolean isFinishUsed = false;
            try {
                while (threadRunning) {
                    if ((System.currentTimeMillis() - takePhotoTime >= 1 * TEST_MSEC_PER_MIN)) {
                        if (DEBUG) Log.w(TAG, "start for take photo");
                        isToBeUse = true;
                        isFinishUsed = false;
                        if (IsCameraAvail()) {
                            Thread.sleep(1000);
                            takePhotoTime = System.currentTimeMillis();
                            captureSnapshot();
                            displayBriefMemory();
                            //计算距离下次使用摄像头时间，小于15秒并且摄像头未启动就马上启动摄像头
                            if (!isRecord && (System.currentTimeMillis() - recordingInterval + 5000 < 2 * TEST_MSEC_PER_MIN)
                                    && (System.currentTimeMillis() - takePhotoTime + 5000 < 1 * TEST_MSEC_PER_MIN)) {
                                isFinishUsed = true;
                                isToBeUse = false;
                            }
                        } else {
                            if (DEBUG) {
                                Log.e(TAG, "camera is not prepared with isFinishPreview is false");
                            }
                        }
                    } else if (!isRecord && (System.currentTimeMillis() - recordingInterval >= 2 * TEST_MSEC_PER_MIN)) { //培训大于20秒后开始录像
                        if (DEBUG) Log.w(TAG, "start for take video");
                        isToBeUse = true;
                        isFinishUsed = false;
                        if (mUVCCameraL != null) {
                            if (avWriterL.isOpened()) {
                                stopRecord(avWriterL);
                                ////playSound("停止录像");
                                isRecord = false;
                            } else {
                                startRecord(avWriterL, mUVCCameraL);
                                ////playSound("开始录像");
                                isRecord = true;
                                startTakeVideoTime = System.currentTimeMillis();
                            }
                        }

                        if (mUVCCameraR != null) {
                            if (avWriterR.isOpened()) {
                                stopRecord(avWriterR);
                                ////playSound("停止录像");
                                isRecord = false;
                            } else {
                                startRecord(avWriterR, mUVCCameraR);
                                ////playSound("开始录像");
                                isRecord = true;
                                startTakeVideoTime = System.currentTimeMillis();
                            }
                        }
                        isRecord = true;
                        recordingInterval = System.currentTimeMillis();
                        recordingDuration = System.currentTimeMillis();
                    } else if (isRecord && (System.currentTimeMillis() - recordingDuration >= 1 * TEST_MSEC_PER_MIN)) {
                        if (DEBUG) Log.w(TAG, "TrainViewActivity stop recording");
                        if (avWriterL != null && avWriterL.isOpened()) {
                            stopRecord(avWriterL);
                        }
                        Thread.sleep(2000);//停止录像后2，延时两秒等待数据保存完毕
                        if (avWriterR != null && avWriterR.isOpened()) {
                            stopRecord(avWriterR);
                        }
                        displayBriefMemory();
                        Thread.sleep(2000);//停止录像后2，延时两秒等待数据保存完毕

                        if (IsAVWriterFinish() || (System.currentTimeMillis() - saveTrainVideoTimeout > 20000)) {    //10s timeout
                            isRecord = false;
                            //计算距离下次使用摄像头时间，小于15秒并且摄像头未启动就马上启动摄像头
                            if (System.currentTimeMillis() - recordingInterval + 5000 < 2 * TEST_MSEC_PER_MIN
                                    && System.currentTimeMillis() - takePhotoTime + 5000 < 1 * TEST_MSEC_PER_MIN) {
                                isFinishUsed = true;
                                isToBeUse = false;
                            }
                        }
                    }
                    if (isToBeUse) {
                        restartUseCamera();
                        isToBeUse = false;
                        if (DEBUG) {
                            Log.w(TAG, "TrainViewActivity start to use camera");
                        }

                    } else if (isFinishUsed) {
                        stopUseCamera();
                        isFinishUsed = false;
                        Log.w(TAG, "TrainViewActivity Finish use camera");
                    }
                    Thread.sleep(200);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    void stopUseCamera() {
        try {
            releaseCameraL();
            Thread.sleep(2000);
            releaseCameraR();
        } catch (final Exception e) {
            Log.e(TAG, e.getMessage());
        } finally {
            mUSBMonitor.unregister();
            if (DEBUG) Log.w(TAG, "TrainViewActivity releaseCamera finish");
        }
    }
}

package com.projecttango.droneMaze;

//-------------------------------------
// DJI Backend
// "Here there be dragons"
// Requires no modification by Unity program developer.
// Do so at your own peril!
//-------------------------------------

import android.os.AsyncTask;
import android.os.Handler;
import android.os.ResultReceiver;
import dji.sdk.camera.Camera;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.TextureView;
import android.widget.Toast;

import android.os.AsyncTask;

import dji.common.error.DJIError;
import dji.common.error.DJISDKError;
import dji.common.product.Model;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.camera.view.CameraLiveView;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.products.Aircraft;
import dji.sdk.products.HandHeld;
import dji.sdk.sdkmanager.DJISDKManager;
import dji.thirdparty.org.java_websocket.framing.Framedata;

import com.secneo.sdk.Helper;
import com.unity3d.player.UnityPlayer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;


public class djiBackend extends Application{ //implements TextureView.SurfaceTextureListener{
    public static final String FLAG_CONNECTION_CHANGE = "activationDemo_connection_change";

    private DJISDKManager.SDKManagerCallback mDJISDKManagerCallback;
    private BaseProduct.BaseProductListener mDJIBaseProductListener;
    private BaseComponent.ComponentListener mDJIComponentListener;
    private static BaseProduct mProduct;
    public Handler mHandler;

    private Application instance;
    private UnityPlayer mUnityPlayer;

    private String TAG = "BACKEND_DRONE";
    protected VideoFeeder.VideoDataCallback mReceivedVideoDataCallBack = null;
    protected DJICodecManager mCodecManager = null;
    private ResultReceiver rec;
    private Boolean ready;
    private Boolean video_enabled;
    private byte[] jdata;

    private int vid = 0;
    public void setContext(Application application) {
        instance = application;
    }
    public void setResultReceiver(ResultReceiver res){rec = res;}

    @Override
    public Context getApplicationContext() {
        return instance;
    }

    public djiBackend() {

    }

    /**
     * This function is used to get the instance of DJIBaseProduct.
     * If no product is connected, it returns null.
     */
    public static synchronized BaseProduct getProductInstance() {
        if (null == mProduct) {
            mProduct = DJISDKManager.getInstance().getProduct();
        }
        return mProduct;
    }

    public static boolean isAircraftConnected() {
        return getProductInstance() != null && getProductInstance() instanceof Aircraft;
    }

    public static boolean isHandHeldConnected() {
        return getProductInstance() != null && getProductInstance() instanceof HandHeld;
    }

    public static synchronized Camera getCameraInstance() {

        if (getProductInstance() == null) return null;

        Camera camera = null;

        if (getProductInstance() instanceof Aircraft){
            camera = ((Aircraft) getProductInstance()).getCamera();

        } else if (getProductInstance() instanceof HandHeld) {
            camera = ((HandHeld) getProductInstance()).getCamera();
        }

        return camera;
    }

    @Override
    public void onCreate() {

        Log.d(TAG, "onCreate: DJIBACKEND LOG TESTING");
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper());
        video_enabled = false;
        mDJIComponentListener = new BaseComponent.ComponentListener() {

            @Override
            public void onConnectivityChange(boolean isConnected) {
                notifyStatusChange();
            }

        };
        mDJIBaseProductListener = new BaseProduct.BaseProductListener() {

            @Override
            public void onComponentChange(BaseProduct.ComponentKey key, BaseComponent oldComponent, BaseComponent newComponent) {

                if (newComponent != null) {
                    newComponent.setComponentListener(mDJIComponentListener);
                }
                notifyStatusChange();
            }

            @Override
            public void onConnectivityChange(boolean isConnected) {

                notifyStatusChange();
            }

        };

        //onSurfaceTextureAvailable(null,16*40,9*40); //for YUV data
        mCodecManager.enabledYuvData(true);

        mCodecManager.setYuvDataCallback(new DJICodecManager.YuvDataCallback() {
            @Override
            public void onYuvDataReceived(ByteBuffer yuvFrame, int dataSize, int width, int height) {
                if(!ready || !video_enabled){
                    Log.d(TAG, "onYuvDataReceived: VIDEO READY:"+ready+" VIDEO ENABLED:"+video_enabled);
                    return;
                }
                ready = false;
                boolean async = true;
                Log.d(TAG, "onYuvDataReceived: DATA SIZE: "+ dataSize + " Width: "+width+ " Height " + height);
                byte[] dat;
                try{
                    dat = new byte[dataSize];
                }catch (Exception e){
                    Log.e(TAG, "onYuvDataReceived: " + "BYTE failed to allocate.");
                    return;
                }
                yuvFrame.get(dat);


                // async code
                if(async) {
                    Log.d( TAG, "ASYNC VERSION!");
                    Video_Frame_Data[] b = new Video_Frame_Data[1];
                    b[0] = new Video_Frame_Data(dat, width, height);
                    new processFrame().execute(b);
                } else {
                    //---------
                    Log.d( TAG, "NON-ASYNC VERSION!");
                    byte[] y = new byte[width * height];
                    byte[] u = new byte[width * height / 4];
                    byte[] v = new byte[width * height / 4];
                    byte[] nu = new byte[width * height / 4]; //
                    byte[] nv = new byte[width * height / 4];
                    System.arraycopy(dat, 0, y, 0, y.length);
                    for (int i = 0; i < u.length; i++) {
                        v[i] = dat[y.length + 2 * i];
                        u[i] = dat[y.length + 2 * i + 1];
                    }
                    int uvWidth = width / 2;
                    int uvHeight = height / 2;
                    for (int j = 0; j < uvWidth / 2; j++) {
                        for (int i = 0; i < uvHeight / 2; i++) {
                            byte uSample1 = u[i * uvWidth + j];
                            byte uSample2 = u[i * uvWidth + j + uvWidth / 2];
                            byte vSample1 = v[(i + uvHeight / 2) * uvWidth + j];
                            byte vSample2 = v[(i + uvHeight / 2) * uvWidth + j + uvWidth / 2];
                            nu[2 * (i * uvWidth + j)] = uSample1;
                            nu[2 * (i * uvWidth + j) + 1] = uSample1;
                            nu[2 * (i * uvWidth + j) + uvWidth] = uSample2;
                            nu[2 * (i * uvWidth + j) + 1 + uvWidth] = uSample2;
                            nv[2 * (i * uvWidth + j)] = vSample1;
                            nv[2 * (i * uvWidth + j) + 1] = vSample1;
                            nv[2 * (i * uvWidth + j) + uvWidth] = vSample2;
                            nv[2 * (i * uvWidth + j) + 1 + uvWidth] = vSample2;
                        }
                    }
                    byte[] bytes = new byte[dat.length];
                    System.arraycopy(y, 0, bytes, 0, y.length);
                    for (int i = 0; i < u.length; i++) {
                        bytes[y.length + (i * 2)] = nv[i];
                        bytes[y.length + (i * 2) + 1] = nu[i];
                    }
                    //----------

                    Log.d(TAG, "onYuvDataReceived: BYTE GET");
                    YuvImage yuvimage = new YuvImage(bytes, ImageFormat.NV21, width, height, null);
                    Log.d(TAG, "onYuvDataReceived: YUVIMAGE created");
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    yuvimage.compressToJpeg(new Rect(0, 0, width, height), 80, baos);
                    Log.d(TAG, "onYuvDataReceived: compress");
                    synchronized (this){
                        jdata = baos.toByteArray();
                    }
                    mUnityPlayer.UnitySendMessage("Canvas","set_frame_ready","true");
                    ready = true;
                }

            }
        });

        mReceivedVideoDataCallBack = new VideoFeeder.VideoDataCallback() {
            @Override
            public void onReceive(byte[] videoBuffer, int size) {
                //if (vid > 0 && vid % 10 == 0 ) {
                //    vid = 0;
                if(ready == false){
                    Log.d(TAG, "onReceive: VIDEO BUFF DROP.  ready == false");
                    return;
                }

                if (mCodecManager != null) {
                    mCodecManager.sendDataToDecoder(videoBuffer, size);
                    Log.d(TAG, "onReceive: VIDEO SENT: " + size);
                } else {
                    Log.d(TAG, "onReceive: CODEC MANAGER NULL");
                }

            }
        };


        ready = true;
    }

    public void setUnityObject(UnityPlayer player){
        mUnityPlayer = player;
    }

    public byte[] getJdata() {
        synchronized (this){
            return jdata;
        }
    }

    public void enableVideo(){
        video_enabled = true;
        ready = true;
    }

    public void disableVideo(){
        video_enabled = false;
        ready = false;
    }

     /* Video functions */

    /*
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.d(TAG, "onSurfaceTextureAvailable");
        if (mCodecManager == null) {
            Log.d(TAG, "onSurfaceTextureAvailable: init codecmanager");
            mCodecManager = new DJICodecManager(getApplicationContext(), surface, width, height);
            Log.d(TAG, "onSurfaceTextureAvailable: "+ mCodecManager.toString());
        }
    }
    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        Log.d(TAG, "onSurfaceTextureSizeChanged");
    }
    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.d(TAG,"onSurfaceTextureDestroyed");
        if (mCodecManager != null) {
            mCodecManager.cleanSurface();
            mCodecManager = null;
        }
        return false;
    }
    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        Log.d(TAG, "onSurfaceTextureUpdated: TEXTURE UPDATED");
    }

    public void initPreviewer() {
        Log.d(TAG, "initPreviewer: PREVIEW INIT");
        BaseProduct product = getProductInstance();
        if (product == null || !product.isConnected()) {
            Log.d(TAG, "initPreviewer: DISCONNECTED");
        } else {
            if (null != baseTV) {
                Log.d(TAG, "initPreviewer: basetvlistener");
                baseTV.setSurfaceTextureListener(this);
            }
            if (!product.getModel().equals(Model.UNKNOWN_AIRCRAFT)) {
                Log.d(TAG, "initPreviewer: videocallback");
                VideoFeeder.getInstance().getPrimaryVideoFeed().setCallback(mReceivedVideoDataCallBack);
            }
        }
    }
    public void uninitPreviewer() {
        Log.d(TAG, "uninitPreviewer: RUN");
        Camera camera = getCameraInstance();
        if (camera != null){
            // Reset the callback
            mCodecManager.destroyCodec();
            VideoFeeder.getInstance().getPrimaryVideoFeed().setCallback(null);
        }
    }

    */

    private void notifyStatusChange() {
        mHandler.removeCallbacks(updateRunnable);
        mHandler.postDelayed(updateRunnable, 500);
    }

    private Runnable updateRunnable = new Runnable() {

        @Override
        public void run() {
            Intent intent = new Intent(FLAG_CONNECTION_CHANGE);
            getApplicationContext().sendBroadcast(intent);
        }
    };

    class SavePhotoTask extends AsyncTask<byte[], String, String> {
        @Override
        protected String doInBackground(byte[]... jpeg) {
            File photo=
                    new File(Environment.getExternalStorageDirectory(),
                            "photo.jpg");

            if (photo.exists()) {
                photo.delete();
            }

            try {
                FileOutputStream fos=new FileOutputStream(photo.getPath());

                fos.write(jpeg[0]);
                fos.close();
            }
            catch (java.io.IOException e) {
                Log.e("PictureDemo", "Exception in photoCallback", e);
            }

            return(null);
        }
    }

    class Video_Frame_Data{
        public byte[] frame;
        int frame_width;
        int frame_height;
        public  Video_Frame_Data(byte[] vid_frame, int width, int height){
            frame = vid_frame;
            frame_width = width;
            frame_height = height;
        }
    }

    class processFrame extends AsyncTask<Video_Frame_Data,Void, byte[]> {
        @Override
        protected byte[] doInBackground(Video_Frame_Data... data) {

            byte[] dat = data[0].frame;
            int height = data[0].frame_height;
            int width = data[0].frame_width;
            byte[] y = new byte[width * height];
            byte[] u = new byte[width * height / 4];
            byte[] v = new byte[width * height / 4];
            byte[] nu = new byte[width * height / 4]; //
            byte[] nv = new byte[width * height / 4];
            System.arraycopy(dat, 0, y, 0, y.length);
            for (int i = 0; i < u.length; i++) {
                v[i] = dat[y.length + 2 * i];
                u[i] = dat[y.length + 2 * i + 1];
            }
            int uvWidth = width / 2;
            int uvHeight = height / 2;
            for (int j = 0; j < uvWidth / 2; j++) {
                for (int i = 0; i < uvHeight / 2; i++) {
                    byte uSample1 = u[i * uvWidth + j];
                    byte uSample2 = u[i * uvWidth + j + uvWidth / 2];
                    byte vSample1 = v[(i + uvHeight / 2) * uvWidth + j];
                    byte vSample2 = v[(i + uvHeight / 2) * uvWidth + j + uvWidth / 2];
                    nu[2 * (i * uvWidth + j)] = uSample1;
                    nu[2 * (i * uvWidth + j) + 1] = uSample1;
                    nu[2 * (i * uvWidth + j) + uvWidth] = uSample2;
                    nu[2 * (i * uvWidth + j) + 1 + uvWidth] = uSample2;
                    nv[2 * (i * uvWidth + j)] = vSample1;
                    nv[2 * (i * uvWidth + j) + 1] = vSample1;
                    nv[2 * (i * uvWidth + j) + uvWidth] = vSample2;
                    nv[2 * (i * uvWidth + j) + 1 + uvWidth] = vSample2;
                }
            }
            byte[] bytes = new byte[dat.length];
            System.arraycopy(y, 0, bytes, 0, y.length);
            for (int i = 0; i < u.length; i++) {
                bytes[y.length + (i * 2)] = nv[i];
                bytes[y.length + (i * 2) + 1] = nu[i];
            }
            //----------

            Log.d(TAG, "onYuvDataReceived: BYTE GET");
            YuvImage yuvimage = new YuvImage(bytes, ImageFormat.NV21, width, height, null);
            Log.d(TAG, "onYuvDataReceived: YUVIMAGE created");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            yuvimage.compressToJpeg(new Rect(0, 0, width, height), 80, baos);
            Log.d(TAG, "onYuvDataReceived: compress");
            jdata = baos.toByteArray();
            mUnityPlayer.UnitySendMessage("Canvas","set_frame_ready","true");
            ready = true;
            return null;
        }

        @Override
        protected void onPostExecute(byte[] bytes) {
            super.onPostExecute(bytes);
        }
    }
}



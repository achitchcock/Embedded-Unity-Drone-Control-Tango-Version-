package com.projecttango.droneMaze;


// Android Imports

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.support.annotation.*;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;
import android.os.AsyncTask;

// Unity Imports
import com.unity3d.player.UnityPlayer;
import com.google.unity.*;
import com.google.atap.*;
import com.google.gson.*;
import com.projecttango.unity.*;

// Java Imports
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// DJI Imports
import dji.common.error.DJIError;
import dji.common.error.DJISDKError;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.GPSSignalLevel;
import dji.common.flightcontroller.LocationCoordinate3D;
import dji.common.flightcontroller.virtualstick.FlightControlData;
import dji.common.gimbal.Rotation;
import dji.common.gimbal.RotationMode;
import dji.common.handheldcontroller.ControllerMode;
import dji.common.mission.followme.FollowMeMission;
import dji.common.model.LocationCoordinate2D;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.gimbal.Gimbal;
import dji.sdk.mission.followme.FollowMeMissionOperator;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;
import dji.common.flightcontroller.virtualstick.*;
import dji.common.mission.followme.*;
import dji.thirdparty.rx.Observable;
import dji.thirdparty.rx.Subscription;
import dji.thirdparty.rx.functions.Action1;
import dji.thirdparty.rx.schedulers.Schedulers;


public class DJIfrontEnd extends GoogleUnityActivity {//implements View.OnClickListener {

    // DJI Required -----------------------
    private static final String TAG = DJIfrontEnd.class.getName();
    public static final String FLAG_CONNECTION_CHANGE = "dji_sdk_connection_change";
    private static BaseProduct mProduct;
    private Handler mHandler;
    private static final String[] REQUIRED_PERMISSION_LIST = new String[]{
            Manifest.permission.VIBRATE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_PHONE_STATE,
            //Manifest.permission.WAKE_LOCK,

    };
    private List<String> missingPermission = new ArrayList<>();
    private AtomicBoolean isRegistrationInProgress = new AtomicBoolean(false);
    private static final int REQUEST_PERMISSION_CODE = 12345;

    //------------------------------------
    private djiBackend djiBack;
    private FlightController flightController;
    private FollowMeMissionOperator followOp;
    private Timer mSendVirtualStickDataTimer;
    private SendVirtualStickDataTask mSendVirtualStickDataTask;
    private float mPitch, mRoll, mYaw, mThrottle;
    private Gimbal mProductGimbal;
    //--------------------------------------
    private float initHeight = 2f;
    private LocationCoordinate2D movingObjectLocation;
    private float movingObjectBearing;
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private Subscription timerSubcription;
    private Observable<Long> timer = Observable.timer(100, TimeUnit.MILLISECONDS).observeOn(Schedulers.computation()).repeat();
    private LocationManager locationManager;
    private LocationListener listener;

    //-------------------------------------
    // Flight Controller State Data
    //-------------------------------------
    private String productText;
    private String connectionStatus;
    private String state;
    private String imuState;
    private LocationCoordinate3D droneLocation;
    private Boolean motorsOn;
    private boolean isFlying;
    private GPSSignalLevel GPSlevel;
    private int satelliteCount;




    // ------------------------------------

    //protected UnityPlayer mUnityPlayer;
    private Button start;
    //protected GoogleUnityActivity.AndroidLifecycleListener mAndroidLifecycleListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkAndRequestPermissions();
        }
        connectionStatus = "Unknown";
        productText = "Unknown";
        state = "Unknown";
        droneLocation = null;
        motorsOn = false;
        isFlying = false;
        GPSlevel = null;
        satelliteCount = 0;
        mProductGimbal = null;
        imuState = "Unknown";
        //mUnityPlayer = new UnityPlayer(this);
        //setContentView(mUnityPlayer);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mHandler = new Handler(Looper.getMainLooper());
        //mUnityPlayer.requestFocus();
    }

    // Joystick code to handle events and pass them on to Unity3D

    /*@Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        mUnityPlayer.injectEvent(event);
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        mUnityPlayer.injectEvent(event);
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        mUnityPlayer.injectEvent(event);
        return true;
    }
    */
    //  ---------
    public String getProductText() {
        return productText;
    }

    public String getConnectionStatus() {
        return connectionStatus;
    }

    public String getIMUstate(){
        return imuState;
    }

    public byte[] getVideoFrame() {
        try {
            return djiBack.getJdata();
        } catch (Exception e) {
            Log.d(TAG, "getVid: " + e.toString());
            return null;
        }
    }

    public String getState() {
        return state;
    }

    // Flight Controller Functions

    private void refreshFlightControllerStatus() {
        try {
            initFlightController();
        } catch (Exception e) {
            Log.d(TAG, "failed to init flight controller");
        }
        state = "";
        if (flightController != null) {

            FlightControllerState st = flightController.getState();
            droneLocation = st.getAircraftLocation();
            motorsOn = st.areMotorsOn();
            isFlying = st.isFlying();
            GPSlevel = st.getGPSSignalLevel();

            // State summary string:
            if (st.isIMUPreheating() == true) {
                imuState = "Preheating" ;
                state += "| IMU: Preheating. ";

            } else {
                imuState =  "Ready";
                state += "| IMU: ready";
            }

            state += "|  Flight Mode: " + st.getFlightModeString();
            state += "\nGPS Signal Level: " + st.getGPSSignalLevel();
            state += "| GPS Satelite count:" + st.getSatelliteCount();
            state += "| Motors on: " + st.areMotorsOn();
            state += "| Location " + st.getAircraftLocation().toString();
            state += "| Attitude: " + st.getAttitude().toString();
        } else {
            Log.d(TAG, "flightControllerStatus: NULL");
        }
    }

    private double[] getDroneLocation(){
        if(null != droneLocation){
            Log.d(TAG,"Location" + droneLocation.getLatitude()+" "+ droneLocation.getLongitude()+" "+ droneLocation.getAltitude());
            return new double[] {droneLocation.getLatitude(), droneLocation.getLongitude(), (double) droneLocation.getAltitude()};
        }else{
            return new double[] {0,0,0};
        }

    }

    private void setupDroneConnection() {
        if (djiBack == null) {
            djiBack = new djiBackend();
            djiBack.setContext(getApplication());
            djiBack.setUnityObject(mUnityPlayer);
            //djiBack.setResultReceiver(rec);
            djiBack.onCreate();

            Log.d(TAG, "djiBackend created");
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(djiBack.FLAG_CONNECTION_CHANGE);
        registerReceiver(mReceiver, filter);
        Log.d(TAG, "IntentFilter created");
    }

    protected BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "broadcast receiver hit...");
            refreshConnectionStatus();
        }
    };

    private void refreshConnectionStatus() {
        BaseProduct mProduct = djiBack.getProductInstance();


        if (null != mProduct && mProduct.isConnected()) {
            Log.v(TAG, "refreshConnectionStatus: product connected");
            String str = mProduct instanceof Aircraft ? "DJIAircraft" : "DJIHandHeld";
            //mTextConnectionStatus.setText("Status: " + str + " connected");

            if (null != mProduct.getModel()) {
                productText = ("" + mProduct.getModel().getDisplayName());
                connectionStatus = str + " connected";
            } else {
                productText = ("Product Information");
            }
        } else {
            Log.v(TAG, "refreshConnectionStatus: product not connected");
            //mBtnOpen.setEnabled(false);

            productText = "Product Information";
            connectionStatus = "No Product Connected";
        }
        Log.d(TAG, "refreshConnectionStatus: " + connectionStatus);
    }

    private void initFlightController() {
        BaseProduct base = djiBack.getProductInstance();
        if (base instanceof Aircraft) {
            Aircraft myCraft = (Aircraft) base;
            flightController = myCraft.getFlightController();
            if (flightController == null) {
                return;
            }
        } else {
            showToast("Aircraft not connected.");
            return;
        }
    }

    private void takeOff() {
        if (flightController == null) {
            showToast("Flightcontroller is Null");
            return;
        }
        flightController.startTakeoff(genericCallback("Takeoff started", true));
    }

    private void land() {
        if (flightController == null) {
            showToast("Flightcontroller Null");
            return;
        }
        flightController.startLanding(genericCallback("Landing started", true));
    }

    public void enableVideo(){
        if(null != djiBack){
            djiBack.enableVideo();
        }else{
            showToast("Drone connection not setup");
        }
    }

    public void disableVideo(){
        if(null != djiBack){
            djiBack.disableVideo();
        }else {
            showToast("Drone connection not setup");
        }
    }




    void startLocationService(){
        if(null == locationManager) {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        }
        if(null == listener) {
            listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    movingObjectLocation = new LocationCoordinate2D(location.getLatitude(), location.getLongitude());
                    movingObjectBearing = location.getBearing();
                    mUnityPlayer.UnitySendMessage("Canvas", "locationUpdate","");
                }

                @Override
                public void onStatusChanged(String s, int i, Bundle bundle) {
                }

                @Override
                public void onProviderEnabled(String s) {
                }

                @Override
                public void onProviderDisabled(String s) {
                    Intent i = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    startActivity(i);
                }
            };

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                showToast("Location Permission missing");
                return;
            }
            locationManager.requestLocationUpdates("gps", 1000, 0, listener);
        }
    }

    private double[] getPhoneLocation(){
        if(null != movingObjectLocation){
            return new double[] {movingObjectLocation.getLatitude(), movingObjectLocation.getLongitude(), (double)movingObjectBearing};
        }else{
            return new double[] {0,0,0};
        }
    }

    private void followMeStart() {
        if(null == locationManager) {
            startLocationService();
        }
 /*       if(null == listener) {
            listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    movingObjectLocation = new LocationCoordinate2D(location.getLatitude(), location.getLongitude());
                    mUnityPlayer.UnitySendMessage("Canvas", "setLocationText", "New Location: " + movingObjectLocation.toString());
                }

                @Override
                public void onStatusChanged(String s, int i, Bundle bundle) {
                }

                @Override
                public void onProviderEnabled(String s) {
                }

                @Override
                public void onProviderDisabled(String s) {
                    Intent i = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    startActivity(i);
                }
            };

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                showToast("Location Permission missing");
                return;
            }
            locationManager.requestLocationUpdates("gps", 1000, 0, listener);
        }*/
        if(null == followOp) {
            followOp = DJISDKManager.getInstance().getMissionControl().getFollowMeMissionOperator();
        }
        showToast("Follow State:"+followOp.getCurrentState().toString());
        if (followOp.getCurrentState().toString().equals(FollowMeMissionState.READY_TO_EXECUTE.toString())){
            //ToDo: You need init or get the location of your moving object which will be followed by the aircraft.


            followOp.startMission(FollowMeMission.getInstance().initUserData(movingObjectLocation.getLatitude() , movingObjectLocation.getLongitude(), initHeight), new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError djiError) {
                    showToast("Mission Start: " + (djiError == null ? "Successfully" : djiError.getDescription()));
                }});

            if (!isRunning.get()) {
                isRunning.set(true);
                timerSubcription = timer.subscribe(new Action1<Long>() {
                    @Override
                    public void call(Long aLong) {
                        followOp.updateFollowingTarget(new LocationCoordinate2D(movingObjectLocation.getLatitude(),
                                        movingObjectLocation.getLongitude()),
                                new CommonCallbacks.CompletionCallback() {

                                    @Override
                                    public void onResult(DJIError error) {
                                        showToast("Follow Me target updated...");
                                        isRunning.set(false);
                                    }
                                });
                    }
                });
            }
        } else{
            Toast.makeText(getApplicationContext(), followOp.getCurrentState().toString(), Toast.LENGTH_SHORT).show();
        }
    }


    void followMeStop(){
        showToast("Follow State:"+ followOp.getCurrentState().toString());
        followOp.stopMission( genericCallback("Stop mission",true) );
        locationManager.removeUpdates(listener);
        timerSubcription.unsubscribe();
        timerSubcription = null;
        timer = null;
    }

    void initGimbal() {
        Log.d(TAG, "init Gimbal!");
        BaseProduct mProduct = djiBack.getProductInstance();
        if (null != mProduct && mProduct.isConnected()) {
            //Log.v(TAG, "refreshSDK: True");
            mProductGimbal = mProduct.getGimbal();
            mProductGimbal.setControllerMode(ControllerMode.TWO_AXIS, genericCallback("Gimbal Controller mode: two axis", true));
            //mProductGimbal.fineTunePitchInDegrees(0, genericCallback("Gimbal ran...", true));
        }
    }

    void setGimbalRotation(float pitchValue, float rollValue){
        Log.d(TAG, "GIMBAL ROTATION: " + pitchValue + " " + rollValue);
        if(null == mProductGimbal){
            initGimbal();
        }
        mProductGimbal.rotate(new Rotation.Builder().pitch(pitchValue)
                .mode(RotationMode.ABSOLUTE_ANGLE)
                .yaw(Rotation.NO_ROTATION)
                .roll(Rotation.NO_ROTATION)
                .time(0)
                .build(), genericCallback("Rotation", false));

    }

    //#############################################################################################
    // Unity required functions
    //#############################################################################################
    @Override
    protected void onDestroy() {
        if(null != mUnityPlayer){mUnityPlayer.quit();}
        try {
            unregisterReceiver(mReceiver);
        }catch (Exception exc){
            Log.d(TAG, "Receiver not regestered. No Problem.");
        }
        try{
            djiBack.uninitPreviewer();
            djiBack.onTerminate();
        }catch (Exception e){
            Log.d(TAG, "Previewer not created.  No Problem.");
        }
        super.onDestroy();
    }


    @Override
    protected void onPause() {
        super.onPause();
        if(null != mUnityPlayer){ mUnityPlayer.pause();}
    }


    @Override
    protected void onResume() {
        super.onResume();
        if(null != mUnityPlayer){mUnityPlayer.resume();}
    }


    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if(null != mUnityPlayer){ mUnityPlayer.windowFocusChanged(hasFocus);}
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if(null != mUnityPlayer){mUnityPlayer.configurationChanged(newConfig);}
    }

    //#############################################################################################
    // FLight Control Functions
    //#############################################################################################

    class SendVirtualStickDataTask extends TimerTask {

        @Override
        public void run() {
            if (flightController != null) {
                flightController.sendVirtualStickFlightControlData(
                        new FlightControlData(mPitch, mRoll, mYaw, mThrottle), new CommonCallbacks.CompletionCallback(){

                            @Override
                            public void onResult(DJIError djiError) {
                                if( null == djiError){
                                    Log.d(TAG, "VIRTUAL STICK SENT: "+ mPitch + " "+mRoll+ " "+ mYaw+ " "+ mThrottle);
                                }else{
                                    Log.d(TAG, "VIRTUAL STICK: " + djiError.toString());
                                }
                            }
                        });
                //genericCallback("", false));
            }
        }
    }

    public void setVirtualControlActive(boolean setting){
        if(setting){
            flightController.setVirtualStickModeEnabled(true, genericCallback("Virtual Sticks Enabled", true));
            flightController.setVirtualStickAdvancedModeEnabled(true);
            //showToast("Here:" + 1);
            flightController.setVerticalControlMode(VerticalControlMode.VELOCITY); //genericCallback("Position", true));
            /*try{
                showToast(flightController.getVerticalControlMode().toString());
            }catch (Exception e){
                showToast(e.toString());
            }*/

            //showToast("Here:" + 2);
            setThrottle(0);
            //showToast("Here:" + 3);
            flightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
            //showToast("Here:" + 4);
            flightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
            flightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
            //showToast("Here:" + 5);
            startSendingControl();
            /*flightController.setFlightOrientationMode(FlightOrientationMode.AIRCRAFT_HEADING,
                    genericCallback("Flight Orientation Mode set to: AIRCRAFT HEADING", true));
            //showToast("Here:" + 6);
            try {
                startSendingControl();
            }catch (Exception e){
                showToast(e.toString());
            }*/
            //showToast("Here:" + 7);
        }else{
            showToast("Stopping...");
            stopSendingControl();
            flightController.setVirtualStickModeEnabled(false,
                    genericCallback("Virtual Sticks Disabled", true));

        }
    }

    public void startSendingControl(){
        if (null == mSendVirtualStickDataTimer) {
            mSendVirtualStickDataTask = new SendVirtualStickDataTask();
            mSendVirtualStickDataTimer = new Timer();
            mSendVirtualStickDataTimer.schedule(mSendVirtualStickDataTask, 100, 200);
            //showToast("Here:" + 8);
        }
    }

    public void stopSendingControl(){
        mSendVirtualStickDataTimer.cancel();
        mSendVirtualStickDataTimer.purge();
        mSendVirtualStickDataTimer = null;
        //setVirtualControlActive(false); // circular call bad!
    }

    public void setYaw(float val){
        mYaw = val;
    }

    public void setRoll(float val){
        mRoll = val;
    }

    public void setPitch(float val){
        mPitch = val;
    }

    public void setThrottle(float val){
        mThrottle = val;
    }


    // Reusable generic callback to reduce code length
    CommonCallbacks.CompletionCallback genericCallback(final String msg, final boolean show)  {
        return new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError djiError) {
                if (null == djiError && show) {
                    showToast(msg);
                } else if(null != djiError ){
                    showToast(djiError.getDescription());
                }
            }
        };
    }


    //#############################################################################################
    // DJI created functions
    //#############################################################################################

    /**
     * Checks if there is any missing permissions, and
     * requests runtime permission if needed.
     */
    private void checkAndRequestPermissions() {
        // Check for permissions
        for (String eachPermission : REQUIRED_PERMISSION_LIST) {
            if (ContextCompat.checkSelfPermission(this, eachPermission) != PackageManager.PERMISSION_GRANTED) {
                missingPermission.add(eachPermission);
            }
        }
        // Request for missing permissions
        if (missingPermission.isEmpty()) {
            startSDKRegistration();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            showToast("Need to grant the permissions!");
            ActivityCompat.requestPermissions(this,
                    missingPermission.toArray(new String[missingPermission.size()]),
                    REQUEST_PERMISSION_CODE);
        }
    }
    /**
     * Result of runtime permission request
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Check for granted permission and remove from missing list
        if (requestCode == REQUEST_PERMISSION_CODE) {
            for (int i = grantResults.length - 1; i >= 0; i--) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    missingPermission.remove(permissions[i]);
                }
            }
        }
        // If there is enough permission, we will start the registration
        if (missingPermission.isEmpty()) {
            startSDKRegistration();
        } else {
            showToast("Missing permissions!!!");
        }
    }

    private void startSDKRegistration() {
        if (isRegistrationInProgress.compareAndSet(false, true)) {
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    showToast("registering, pls wait...");
                    DJISDKManager.getInstance().registerApp(DJIfrontEnd.this.getApplicationContext(), new DJISDKManager.SDKManagerCallback() {
                        @Override
                        public void onRegister(DJIError djiError) {
                            if (djiError == DJISDKError.REGISTRATION_SUCCESS) {
                                showToast("Register Success");
                                DJISDKManager.getInstance().startConnectionToProduct();
                            } else {
                                showToast("Register sdk fails, please check the bundle id and network connection!");
                            }
                            Log.v(TAG, djiError.getDescription());
                        }
                        @Override
                        public void onProductChange(BaseProduct oldProduct, BaseProduct newProduct) {
                            mProduct = newProduct;
                            if(mProduct != null) {
                                mProduct.setBaseProductListener(mDJIBaseProductListener);
                            }
                            notifyStatusChange();
                        }
                    });
                }
            });
        }
    }

    private BaseProduct.BaseProductListener mDJIBaseProductListener = new BaseProduct.BaseProductListener() {
        @Override
        public void onComponentChange(BaseProduct.ComponentKey key, BaseComponent oldComponent, BaseComponent newComponent) {
            if(newComponent != null) {
                newComponent.setComponentListener(mDJIComponentListener);
            }
            notifyStatusChange();
        }
        @Override
        public void onConnectivityChange(boolean isConnected) {
            notifyStatusChange();
        }
    };
    private BaseComponent.ComponentListener mDJIComponentListener = new BaseComponent.ComponentListener() {
        @Override
        public void onConnectivityChange(boolean isConnected) {
            notifyStatusChange();
        }
    };
    private void notifyStatusChange() {
        mHandler.removeCallbacks(updateRunnable);
        mHandler.postDelayed(updateRunnable, 500);
    }
    private Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            Intent intent = new Intent(FLAG_CONNECTION_CHANGE);
            sendBroadcast(intent);
        }
    };
    private void showToast(final String toastMsg) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), toastMsg, Toast.LENGTH_SHORT).show();
            }
        });
    }
}

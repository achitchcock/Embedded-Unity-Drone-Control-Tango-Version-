package com.projecttango.droneMaze;

// Android Imports
import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.support.annotation.*;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import android.widget.Toast;
import android.os.AsyncTask;

// Unity Imports
import com.google.unity.*;


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


public class DJIfrontEnd extends GoogleUnityActivity {

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
    // Connection State Data
    //-------------------------------------
    private String productText;
    private String connectionStatus;

    //-------------------------------------
    // Flight Controller State Data
    //-------------------------------------

    private String state;
    private String imuState;
    private String flightMode;
    private LocationCoordinate3D droneLocation;
    private boolean motorsOn;
    private boolean isFlying;
    private double[] droneAttitude;
    private GPSSignalLevel GPSlevel;
    private int satelliteCount;
    // ------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkAndRequestPermissions();
        }
        // connection based info
        connectionStatus = "Unknown";
        productText = "Unknown";
        // Flight controller based info
        state = "Unknown";
        imuState = "Unknown";
        flightMode = "Unknown";
        droneLocation = null;
        motorsOn = false;
        isFlying = false;
        GPSlevel = null;
        satelliteCount = 0;
        mProductGimbal = null;
        droneAttitude = new double[] {0,0,0};

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mHandler = new Handler(Looper.getMainLooper());
    }

    //----------------------------------------------------------------------------------------------
    // Flight Controller Functions
    //----------------------------------------------------------------------------------------------

    //-------------------------------------
    // refreshFlightControllerStatus
    // Updates the flight controller status  variables and generates a displayable state string.
    // Should be regularly polled
    //-------------------------------------
    private void refreshFlightControllerStatus() {
        state = "";
        if(null == flightController){
            try {
                initFlightController();
            } catch (Exception e) {
                Log.d(TAG, "failed to init flight controller");
                state = "failed to init flight controller";
            }
        }

        if (flightController != null) {

            FlightControllerState st = flightController.getState();

            if (st.isIMUPreheating() == true) {
                imuState = "Preheating" ;
                state += "| IMU: Preheating. ";

            } else {
                imuState =  "Ready";
                state += "| IMU: ready";
            }
            flightMode = st.getFlightModeString();
            state += "|  Flight Mode: " + flightMode;

            GPSlevel = st.getGPSSignalLevel();
            state += "\nGPS Signal Level: " + st.getGPSSignalLevel();

            state += "| GPS Satelite count:" + st.getSatelliteCount();

            motorsOn = st.areMotorsOn();
            state += "| Motors on: " + motorsOn;

            isFlying = st.isFlying();
            state += "| Flying: " + isFlying;

            droneLocation = st.getAircraftLocation();
            state += "| Location " + droneLocation.toString();

            droneAttitude = new double[] {st.getAttitude().pitch, st.getAttitude().roll, st.getAttitude().yaw};
            state += "| Attitude: " + droneAttitude.toString();
        } else {
            Log.d(TAG, "flightControllerStatus: NULL");
        }
    }

    //-------------------------------------
    // getIMUstate
    // returns ready when the IMU is preheated and flight is now possible.
    //-------------------------------------
    public String getIMUstate(){
        return imuState;
    }

    //-------------------------------------
    // getState
    // returns a large string containing the flight controller sate information.
    // An easy way to display all state information in Unity
    //-------------------------------------
    public String getState() {
        return state;
    }

    //-------------------------------------
    // getIsFlying
    // return true if the drone is flying.
    //-------------------------------------
    public boolean getIsFlying(){
        return isFlying;
    }

    //-------------------------------------
    // getMotorsOn
    // return true if the drone's motors are on.
    //-------------------------------------
    public boolean getMotorsOn(){ return motorsOn;}

    //-------------------------------------
    // getVideoFrame
    // returns a byte arra of RGB formatted image data ready for display in Unity as a Texture2D
    //-------------------------------------
    public byte[] getVideoFrame() {
        try {
            return djiBack.getJdata();
        } catch (Exception e) {
            Log.d(TAG, "getVid: " + e.toString());
            return null;
        }
    }

    //-------------------------------------
    // getDroneAttitude
    // returns the current pitch roll and yaw of the drone.
    //-------------------------------------
    public double[] getDroneAttitude(){
        return droneAttitude;
    }

    //-------------------------------------
    // getFlightMode
    // returns the current flight mode.
    // ATTI -- is Attitude mode. No GPS stabilization is available and aircraft will wander severely
    // P-GPS -- gps singal is storng enough for GPS stabilization. Aircraft will automatically try
    // and maintain current position but will still wander a small amount.
    //-------------------------------------
    public String getFlightMode(){
        return flightMode;
    }

    //-------------------------------------
    // getDroneLocation
    // return the GPS coordinates of the drone's current location
    // Double array is formatted as [latitude, longitude, altitude]
    // If you are consistently receiving [0,0,0] it may mean the drone is not connected or you are
    // not polling for flight controller status updates.
    //-------------------------------------
    private double[] getDroneLocation(){
        if(null != droneLocation){
            Log.d(TAG,"Location" + droneLocation.getLatitude()+" "+ droneLocation.getLongitude()+" "+ droneLocation.getAltitude());
            return new double[] {droneLocation.getLatitude(), droneLocation.getLongitude(), (double) droneLocation.getAltitude()};
        }else{
            return new double[] {0,0,0};
        }
    }



    //----------------------------------------------------------------------------------------------
    // Connection Status Functions
    //----------------------------------------------------------------------------------------------


    //-------------------------------------
    // refreshConnectionStatus
    //  Updates the connection status of the Drone.
    // Should be regularly polled during the setup portion of the application to ensure connection
    // to the drone is active.
    //-------------------------------------
    private void refreshConnectionStatus() {
        BaseProduct mProduct = djiBack.getProductInstance();


        if (null != mProduct && mProduct.isConnected()) {
            Log.v(TAG, "refreshConnectionStatus: product connected");
            String str = mProduct instanceof Aircraft ? "DJIAircraft" : "DJIHandHeld";
            if (null != mProduct.getModel()) {
                productText = ("" + mProduct.getModel().getDisplayName());
                connectionStatus = str + " connected";
            } else {
                productText = ("Product Information: unknown");
            }
        } else {
            Log.v(TAG, "refreshConnectionStatus: product not connected");
            productText = "Product Information: unknown";
            connectionStatus = "No Product Connected";
        }
        Log.d(TAG, "refreshConnectionStatus: " + connectionStatus);
    }

    //-------------------------------------
    // getProductText
    // Returns a string descriptor of the connected product.
    // If no product is connected it will return a default "unknown" string.
    //-------------------------------------
    public String getProductText() {
        return productText;
    }

    //-------------------------------------
    // getConnectionStatus
    // returns the connection status of the drone
    //-------------------------------------
    public String getConnectionStatus() {
        return connectionStatus;
    }



    //-------------------------------------
    // setupDroneConnection
    // sets up the DJI Backend for drone connection and video processing.
    // Must be manually called at the beginning of the application after registration is completed.
    // Calling this before registration is completed will cause a hard stop.  For this reason it
    // should only be called after the "register success" toast is displayed.
    // Registration is usually completed by the time the Unity splash screen is finished though
    // the time is variable.
    // May be implemented as part of a start screen button.
    //-------------------------------------

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


    //-------------------------------------
    // initFlightController
    // initializes the flight controller allowing for status polling and control of the drone.
    // must be called before any control operations.  Status polling will init if not already
    // completed.
    //-------------------------------------
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


    //-------------------------------------
    // takeOff
    // starts the take off routine
    // The drone will start it's motors and accelerate upwards until it is approximately 1 meter
    // above the ground.  The drone's altitude is calcualted based on barometric pressure ans so
    // this hover height will vary.
    //-------------------------------------
    private void takeOff() {
        if (flightController == null) {
            showToast("Flightcontroller is Null");
            return;
        }
        flightController.startTakeoff(genericCallback("Takeoff started", true));
    }

    //-------------------------------------
    // land
    // starts the landing routine.
    // when called, the drone will stop current operations and begin to descend to the ground and
    // will automatically land
    //-------------------------------------
    private void land() {
        if (flightController == null) {
            showToast("Flightcontroller Null");
            return;
        }
        flightController.startLanding(genericCallback("Landing started", true));
    }

    //-------------------------------------
    // enableVideo
    // enables the video processing pipeline
    // may affect system load when enabled.
    //-------------------------------------
    public void enableVideo(){
        if(null != djiBack){
            djiBack.enableVideo();
        }else{
            showToast("Drone connection not setup");
        }
    }

    //-------------------------------------
    // disableVideo
    // diables the video processing pipeline.
    // can be utilized to reduce system load when not streaming video from the drone
    //-------------------------------------
    public void disableVideo(){
        if(null != djiBack){
            djiBack.disableVideo();
        }else {
            showToast("Drone connection not setup");
        }
    }



    //-------------------------------------
    // startLocationService
    // starts the Android system location service and provides the application with location updates
    // "getPhoneLocation" may be used to retrieve the current location data.
    //-------------------------------------
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

    //-------------------------------------
    // getPhoneLocation
    // returns the gps coordinates of the phone.
    // location services must be started before use with "startLocationService" otherwise location
    // will always be [0,0,0]
    //-------------------------------------
    private double[] getPhoneLocation(){
        if(null != movingObjectLocation){
            return new double[] {movingObjectLocation.getLatitude(), movingObjectLocation.getLongitude(), (double)movingObjectBearing};
        }else{
            return new double[] {0,0,0};
        }
    }

    //-------------------------------------
    // followMeStart
    // setup and begin follow me mission.
    // Currently errors with "Mission does not exist" error.
    // Commented out but retained for future work
    //-------------------------------------
    /*private void followMeStart() {
        if(null == locationManager) {
            startLocationService();
        }
        if(null == listener) {
            listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    movingObjectLocation = new LocationCoordinate2D(location.getLatitude(), location.getLongitude());
                    //mUnityPlayer.UnitySendMessage("Canvas", "setLocationText", "New Location: " + movingObjectLocation.toString());
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
        if(null == followOp) {
            followOp = DJISDKManager.getInstance().getMissionControl().getFollowMeMissionOperator();
        }
        showToast("Follow State:"+followOp.getCurrentState().toString());
        if (followOp.getCurrentState().toString().equals(FollowMeMissionState.READY_TO_EXECUTE.toString())){
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
    }*/

    //-------------------------------------
    // followMeStop
    // Teardown and end follow me mission.
    //  Setup errors with "Mission does not exist" error.
    // Commented out but retained for future work
    //-------------------------------------
    /*void followMeStop(){
        showToast("Follow State:"+ followOp.getCurrentState().toString());
        followOp.stopMission( genericCallback("Stop mission",true) );
        locationManager.removeUpdates(listener);
        timerSubcription.unsubscribe();
        timerSubcription = null;
        timer = null;
    }*/

    //-------------------------------------
    // initGimbal
    // Initializes the drone gimbal to enable rotations to be set.
    // not necessary to me called manually, but my be if desired.
    // calls to setGimbalRotation will init the gimbal if it has not been done.
    //-------------------------------------
    void initGimbal() {
        Log.d(TAG, "init Gimbal!");
        BaseProduct mProduct = djiBack.getProductInstance();
        if (null != mProduct && mProduct.isConnected()) {
            mProductGimbal = mProduct.getGimbal();
            mProductGimbal.setControllerMode(ControllerMode.TWO_AXIS, genericCallback("Gimbal Controller mode: two axis", true));
        }
    }

    //-------------------------------------
    // setGimbalRotation
    // set the angles of rotation for the camera gimbal.
    // pitch: 0 degrees - default position pointing forwards.
    // pitch -90 degrees - pointing straight down
    // roll: default upright view is 0 degrees
    // roll range: -180 to 180 degrees
    // In a top down view the drone should be rotated rather than the camera to retain consistency
    // in control.  If the pitch is set to -90 and the roll is changed controlling the drone becomes
    // unintuitive as the apparent forward direction does not match the visual forward direction.
    //-------------------------------------
    void setGimbalRotation(float pitchValue, float rollValue){
        Log.d(TAG, "GIMBAL ROTATION: " + pitchValue + " " + rollValue);
        if(null == mProductGimbal){
            initGimbal();
        }
        mProductGimbal.rotate(new Rotation.Builder().pitch(pitchValue)
                .mode(RotationMode.ABSOLUTE_ANGLE)
                .yaw(Rotation.NO_ROTATION)
                .roll(rollValue)
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

    //-------------------------------------
    // timer task for sending virtual stick control data to the drone
    //-------------------------------------
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

    //-------------------------------------
    // setVirtualControlActive
    // Required to allow for control of the drone from the device.
    // Should be called after drone has taken off.
    // setting parameter: true to enable control, false to disable control.
    //-------------------------------------
    public void setVirtualControlActive(boolean setting){
        if(setting){
            flightController.setVirtualStickModeEnabled(true, genericCallback("Virtual Sticks Enabled", true));
            flightController.setVirtualStickAdvancedModeEnabled(true);
            flightController.setVerticalControlMode(VerticalControlMode.VELOCITY); //genericCallback("Position", true));
            setThrottle(0);
            flightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
            flightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
            flightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
            startSendingControl();
        }else{
            showToast("Stopping...");
            stopSendingControl();
            flightController.setVirtualStickModeEnabled(false,
                    genericCallback("Virtual Sticks Disabled", true));
        }
    }
    //-------------------------------------
    // startSendingControl
    // Begins a timer task that relays the roll, pitch, yaw and throttle values to the drone.
    //-------------------------------------
    public void startSendingControl(){
        if (null == mSendVirtualStickDataTimer) {
            mSendVirtualStickDataTask = new SendVirtualStickDataTask();
            mSendVirtualStickDataTimer = new Timer();
            mSendVirtualStickDataTimer.schedule(mSendVirtualStickDataTask, 100, 200);
            //showToast("Here:" + 8);
        }
    }
    //-------------------------------------
    // stopSendingControl
    // stops the timer task created with startSendingControl
    // Any further changes to the roll, pitch, yaw or throttle will not change the drone's state.
    //-------------------------------------
    public void stopSendingControl(){
        mSendVirtualStickDataTimer.cancel();
        mSendVirtualStickDataTimer.purge();
        mSendVirtualStickDataTimer = null;
        //setVirtualControlActive(false); // circular call bad!
    }
    //-------------------------------------
    // setYaw
    // set's the speed at which the drone will rotate.
    // Max value +/-90
    // for slower rotation use a maximum of +/-45
    //-------------------------------------
    public void setYaw(float val){
        mYaw = val;
    }
    //-------------------------------------
    // setRoll
    // controls the left/right motion of the drone.
    // max value +/-4.  Recommended max value +/-2
    // values from 2 to 4 are extremely fast and will deplete the battery very rapidly.
    //-------------------------------------
    public void setRoll(float val){
        mRoll = val;
    }
    //-------------------------------------
    // setPitch
    // controls the forwards and backward motion of the drone
    // max value +/-4.  Recommended max value +/-2
    // values from 2 to 4 are extremely fast and will deplete the battery very rapidly.
    //-------------------------------------
    public void setPitch(float val){
        mPitch = val;
    }
    //-------------------------------------
    //set Throttle
    // controls the vertical motion of the drone.
    // max value +/-4.  Recommended max value +/-2
    // values from 2 to 4 are extremely fast and will deplete the battery very rapidly.
    //-------------------------------------
    public void setThrottle(float val){
        mThrottle = val;
    }

    //-------------------------------------
    // genericCallback
    // Reusable generic callback to reduce code length
    // not for use from Unity.
    //-------------------------------------
    //
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


    //-------------------------------------
    // showToast
    // Convenience function for displaying a toast from Unity.
    //-------------------------------------
    private void showToast(final String toastMsg) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), toastMsg, Toast.LENGTH_SHORT).show();
            }
        });
    }


    //#############################################################################################
    // DJI created functions required for Donre connection and interaction
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
    //-------------------------------------
    //
    //-------------------------------------
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
}

package com.corvettecole.pixelwatchface;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import static com.corvettecole.pixelwatchface.Constants.WEATHER_BACKOFF_DELAY;
import static com.corvettecole.pixelwatchface.Constants.WEATHER_UPDATE_INTERVAL;
import static com.corvettecole.pixelwatchface.Constants.WEATHER_UPDATE_WORKER;
import static com.corvettecole.pixelwatchface.Utils.drawableToBitmap;
import static com.corvettecole.pixelwatchface.Utils.getHour;

/**
 * Important Note: Because watch face apps do not have a default Activity in
 * their project, you will need to set your Configurations to
 * "Do not launch Activity" for both the Wear and/or Application modules. If you
 * are unsure how to do this, please review the "Run Starter project" section
 * in the Google Watch Face Code Lab:
 * https://codelabs.developers.google.com/codelabs/watchface/index.html#0
 */
public class PixelWatchFace extends CanvasWatchFaceService {
    /**
     * Update rate in milliseconds for interactive mode. Defaults to one minute
     * because the watch face needs to update minutes in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<PixelWatchFace.Engine> mWeakReference;

        public EngineHandler(PixelWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            PixelWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataClient.OnDataChangedListener {

        private final Handler mUpdateTimeHandler = new EngineHandler(this);
        private Calendar mCalendar;
        private final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mBatteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            }
        };

        private FusedLocationProviderClient mFusedLocationClient;
        private final Bitmap mWearOSBitmap = drawableToBitmap(getDrawable(R.drawable.ic_wear_os_logo));
        private final Bitmap mWearOSBitmapAmbient = drawableToBitmap(getDrawable(R.drawable.ic_wear_os_logo_ambient));
        private boolean mRegisteredTimeZoneReceiver = false;
        private boolean mRegisteredBatteryReceiver = false;
        private Paint mBackgroundPaint;
        private Paint mTimePaint;
        private Paint mInfoPaint;
        private int mBatteryLevel;
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        private boolean mLowBitAmbient;
        private boolean mBurnInProtection;
        private boolean mAmbient;

        private long mPermissionRequestedTime = 0;

        private Typeface mProductSans;
        private Typeface mProductSansThin;


        private CurrentWeather mCurrentWeather = CurrentWeather.getInstance(getApplicationContext());
        private Settings mSettings = Settings.getInstance(getApplicationContext());

        private final long ONE_MIN = 60000;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(PixelWatchFace.this)
                    .build());

            mCalendar = Calendar.getInstance();
            //Resources resources = PixelWatchFace.this.getResources();

            // Initializes syncing with companion app
            Wearable.getDataClient(getApplicationContext()).addListener(this);

            // Initializes background.
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(
                    ContextCompat.getColor(getApplicationContext(), R.color.background));
            mProductSans = ResourcesCompat.getFont(getApplicationContext(), R.font.product_sans_regular);
            mProductSansThin = ResourcesCompat.getFont(getApplicationContext(), R.font.product_sans_thin);

            // Initializes Watch Face.
            mTimePaint = new Paint();
            mTimePaint.setTypeface(mProductSans);
            mTimePaint.setAntiAlias(true);
            mTimePaint.setColor(
                    ContextCompat.getColor(getApplicationContext(), R.color.digital_text));
            mTimePaint.setStrokeWidth(3f);

            mInfoPaint = new Paint();
            mInfoPaint.setTypeface(mProductSans);
            mInfoPaint.setAntiAlias(true);
            mInfoPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.digital_text));
            mInfoPaint.setStrokeWidth(2f);


        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            Wearable.getDataClient(getApplicationContext()).removeListener(this);
            WorkManager.getInstance(getApplicationContext()).cancelAllWorkByTag(WEATHER_UPDATE_WORKER);
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceivers();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceivers();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceivers() {
            if (mRegisteredBatteryReceiver && mRegisteredTimeZoneReceiver) {
                return;
            }
            if (!mRegisteredBatteryReceiver) {
                mRegisteredBatteryReceiver = true;
                IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                PixelWatchFace.this.registerReceiver(mBatteryReceiver, filter);
            }
            if (!mRegisteredTimeZoneReceiver) {
                mRegisteredTimeZoneReceiver = true;
                IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
                PixelWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
            }
        }

        private void unregisterReceivers() {
            if (!mRegisteredBatteryReceiver && !mRegisteredTimeZoneReceiver) {
                return;
            }
            if (mRegisteredTimeZoneReceiver) {
                mRegisteredTimeZoneReceiver = false;
                PixelWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
            }
            if (mRegisteredBatteryReceiver) {
                mRegisteredBatteryReceiver = false;
                PixelWatchFace.this.unregisterReceiver(mBatteryReceiver);
            }
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = PixelWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_time_text_size_round : R.dimen.digital_time_text_size);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_date_text_size_round : R.dimen.digital_date_text_size);

            mTimePaint.setTextSize(timeTextSize);
            mInfoPaint.setTextSize(dateTextSize);


        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate(); // forces redraw (calls onDraw)
            String TAG = "onTimeTick";
            Log.d(TAG, "onTimeTick called");
            //if (!mWeatherUpdaterInitialized) {
                initWeatherUpdater(false);
            //}
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            mAmbient = inAmbientMode;
            if (mLowBitAmbient) {
                mTimePaint.setAntiAlias(!inAmbientMode);
                mInfoPaint.setAntiAlias(!inAmbientMode);
            }

            if (inAmbientMode) {
                mTimePaint.setStyle(Paint.Style.STROKE);
                if (mSettings.isUseThinAmbient()){
                    mTimePaint.setStyle(Paint.Style.FILL);
                    mTimePaint.setTypeface(mProductSansThin);
                }
                if (mSettings.isShowInfoBarAmbient()) {
                    //TODO: change date between the pixel ambient gray and white instead of making it stroked
                    mInfoPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.digital_text_ambient));
                }
            } else {
                mTimePaint.setStyle(Paint.Style.FILL);
                mInfoPaint.setStyle(Paint.Style.FILL);
                mInfoPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.digital_text));

            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }


        @SuppressLint("DefaultLocale")
        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            final String TAG = "onDraw";

            // Draw the background.
            //canvas.drawColor(Color.BLACK);  // test not drawing background every render pass
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);


            // Draw H:MM
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            // pad hour with 0 or not depending on if 24 hour time is being used
            String mTimeText = "";
            if (mSettings.isUse24HourTime()) {
                mTimeText = String.format("%02d:%02d", getHour(mCalendar, true), mCalendar.get(Calendar.MINUTE));
            } else {
                mTimeText = String.format("%d:%02d", getHour(mCalendar, false), mCalendar.get(Calendar.MINUTE));
            }

            float mTimeXOffset = computeXOffset(mTimeText, mTimePaint, bounds);
            float timeYOffset = computeTimeYOffset(mTimeText, mTimePaint, bounds);
            canvas.drawText(mTimeText, mTimeXOffset, timeYOffset, mTimePaint);
            String dateText;
            if (mSettings.isUseEuropeanDateFormat()) {
                dateText = String.format("%.3s, %d %.3s", android.text.format.DateFormat.format("EEEE", mCalendar), mCalendar.get(Calendar.DAY_OF_MONTH),
                        android.text.format.DateFormat.format("MMMM", mCalendar));
            } else {
                dateText = String.format("%.3s, %.3s %d", android.text.format.DateFormat.format("EEEE", mCalendar),
                        android.text.format.DateFormat.format("MMMM", mCalendar), mCalendar.get(Calendar.DAY_OF_MONTH));
            }

            String temperatureText = "";
            float totalLength;
            float centerX = bounds.exactCenterX();
            float dateTextLength = mInfoPaint.measureText(dateText);

            float bitmapMargin = 20.0f;
            if (mSettings.isShowTemperature()) {
                temperatureText = mCurrentWeather.getFormattedTemperature();
                if (mSettings.isShowWeatherIcon()) {
                    totalLength = dateTextLength + bitmapMargin + mCurrentWeather.getIconBitmap(getApplicationContext()).getWidth() + mInfoPaint.measureText(temperatureText);
                } else {
                    totalLength = dateTextLength + bitmapMargin + mInfoPaint.measureText(temperatureText);
                }
            } else if (mSettings.isShowWeatherIcon()) {
                totalLength = dateTextLength + bitmapMargin / 2 + mCurrentWeather.getIconBitmap(getApplicationContext()).getWidth();
            } else {
                totalLength = dateTextLength;
            }

            float infoBarXOffset = centerX - (totalLength / 2.0f);
            float infoBarYOffset = computeInfoBarYOffset(dateText, mInfoPaint);

            // draw infobar
            if (mSettings.isShowInfoBarAmbient() || !mAmbient) {


                canvas.drawText(dateText, infoBarXOffset, timeYOffset + infoBarYOffset, mInfoPaint);
                if (mSettings.isShowWeatherIcon() && mCurrentWeather != null) {
                    canvas.drawBitmap(mCurrentWeather.getIconBitmap(getApplicationContext()), infoBarXOffset + (dateTextLength + bitmapMargin / 2),
                            timeYOffset + infoBarYOffset - mCurrentWeather.getIconBitmap(getApplicationContext()).getHeight() + 6.0f, null);
                    canvas.drawText(temperatureText, infoBarXOffset + (dateTextLength + bitmapMargin + mCurrentWeather.getIconBitmap(getApplicationContext()).getWidth()), timeYOffset + infoBarYOffset, mInfoPaint);
                } else if (!mSettings.isShowWeatherIcon() && mSettings.isShowTemperature() && mCurrentWeather != null) {
                    canvas.drawText(temperatureText, infoBarXOffset + (dateTextLength + bitmapMargin), timeYOffset + infoBarYOffset, mInfoPaint);
                }
            }

            // draw battery percentage
            if (mSettings.isShowBattery()) {
                String battery = String.format("%d%%", mBatteryLevel);
                float batteryXOffset = computeXOffset(battery, mInfoPaint, bounds);
                float batteryYOffset = computerBatteryYOffset(battery, mInfoPaint, bounds);

                canvas.drawText(battery, batteryXOffset, batteryYOffset, mInfoPaint);
            }

            // draw wearOS icon
            if (mAmbient) {
                float mIconXOffset = bounds.exactCenterX() - (mWearOSBitmapAmbient.getWidth() / 2.0f);
                float mIconYOffset = timeYOffset - timeYOffset / 2 - mWearOSBitmapAmbient.getHeight() - 16.0f;
                canvas.drawBitmap(mWearOSBitmapAmbient, mIconXOffset, mIconYOffset, null);
            } else {
                float mIconXOffset = bounds.exactCenterX() - (mWearOSBitmap.getWidth() / 2.0f);
                float mIconYOffset = timeYOffset - timeYOffset / 2 - mWearOSBitmap.getHeight() - 16.0f;
                canvas.drawBitmap(mWearOSBitmap, mIconXOffset, mIconYOffset, null);
            }
        }

        private void initWeatherUpdater(boolean forceUpdate){
            String TAG = "initWeatherUpdater";
            if (mSettings.isShowTemperature() || mSettings.isShowWeatherIcon()) {
                if (ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        Log.d(TAG, "requesting permission");
                    requestPermissions();
                } else {
                    if (forceUpdate) {
                        Constraints constraints = new Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build();
                        OneTimeWorkRequest forceWeatherUpdate =
                                new OneTimeWorkRequest.Builder(WeatherUpdateWorker.class)
                                        .setConstraints(constraints)
                                        .setBackoffCriteria(BackoffPolicy.LINEAR, WEATHER_BACKOFF_DELAY, TimeUnit.MINUTES)
                                        .build();
                        WorkManager.getInstance(getApplicationContext()).enqueue(forceWeatherUpdate);
                    } else {
                        Log.d(TAG, "setting up weather periodic request");
                        Constraints constraints = new Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build();
                        PeriodicWorkRequest weatherUpdater =
                                new PeriodicWorkRequest.Builder(WeatherUpdateWorker.class, WEATHER_UPDATE_INTERVAL, TimeUnit.MINUTES)
                                        .setConstraints(constraints)
                                        .addTag(WEATHER_UPDATE_WORKER)
                                        .setBackoffCriteria(BackoffPolicy.LINEAR, WEATHER_BACKOFF_DELAY, TimeUnit.MINUTES)
                                        .build();
                        WorkManager.getInstance(getApplicationContext())
                                .enqueueUniquePeriodicWork(WEATHER_UPDATE_WORKER, ExistingPeriodicWorkPolicy.KEEP, weatherUpdater);
                        //mWeatherUpdaterInitialized = true;
                    }
                }
            }
        }


        private float computeXOffset(String text, Paint paint, Rect watchBounds) {
            float centerX = watchBounds.exactCenterX();
            float textLength = paint.measureText(text);
            return centerX - (textLength / 2.0f);
        }

        private float computeTimeYOffset(String timeText, Paint timePaint, Rect watchBounds) {
            float centerY = watchBounds.exactCenterY();
            Rect textBounds = new Rect();
            timePaint.getTextBounds(timeText, 0, timeText.length(), textBounds);
            int textHeight = textBounds.height();
            return centerY + (textHeight / 2.0f) - 25.0f; //-XX.Xf is the offset up from the center
        }

        private float computeInfoBarYOffset(String dateText, Paint datePaint) {
            Rect textBounds = new Rect();
            datePaint.getTextBounds(dateText, 0, dateText.length(), textBounds);
            return textBounds.height() + 27.0f;
        }

        private float computerBatteryYOffset(String batteryText, Paint batteryPaint, Rect watchBounds) {
            Rect textBounds = new Rect();
            batteryPaint.getTextBounds(batteryText, 0, batteryText.length(), textBounds);
            return watchBounds.bottom - textBounds.height() * 1.5f/* / 2.0f*/;
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            String TAG = "onDataChanged";
            Log.d(TAG, "Data changed");
            DataMap dataMap = new DataMap();
            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    Log.d(TAG, "DataItem uri: " + item.getUri());
                    if (item.getUri().getPath().compareTo("/settings") == 0) {
                        Log.d(TAG, "Companion app changed a setting!");
                        dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        Log.d(TAG, dataMap.toString());
                        dataMap = dataMap.getDataMap("com.corvettecole.pixelwatchface");
                        Log.d(TAG, dataMap.toString());
                        if (mSettings.updateSettings(dataMap)){
                            initWeatherUpdater(true);
                        }
                        invalidate();
                        //syncToPhone();
                    }
                } else if (event.getType() == DataEvent.TYPE_DELETED) {
                    // DataItem deleted
                }
            }
        }

        private void requestPermissions() {
            if (mPermissionRequestedTime == 0 || mPermissionRequestedTime - System.currentTimeMillis() > ONE_MIN) {
                Log.d("requestPermission", "Actually requesting permission, more than one minute has passed");
                mPermissionRequestedTime = System.currentTimeMillis();
                if (ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    Intent mPermissionRequestIntent = new Intent(getBaseContext(), PermissionRequestActivity.class);
                    mPermissionRequestIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mPermissionRequestIntent.putExtra("KEY_PERMISSIONS", Manifest.permission.ACCESS_FINE_LOCATION);
                    //mPermissionRequestIntent.putExtra("KEY_PERMISSIONS", new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION});
                    startActivity(mPermissionRequestIntent);
                }
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        // Class for debugging
        /*
        private void syncToPhone(){
            String TAG = "syncToPhone";
            DataClient mDataClient = Wearable.getDataClient(getApplicationContext());
            PutDataMapRequest putDataMapReq = PutDataMapRequest.create("/watch_status");

            DataMap dataMap = new DataMap();
            dataMap.putLong("wear_timestamp", System.currentTimeMillis());
            dataMap.putBoolean("use_24_hour_time", mUse24HourTime);
            dataMap.putBoolean("show_temperature", mShowTemperature);
            dataMap.putBoolean("use_celsius", mUseCelsius);
            dataMap.putBoolean("show_weather", mShowWeather);

            putDataMapReq.getDataMap().putDataMap("com.corvettecole.pixelwatchface", dataMap);
            PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
            putDataReq.setUrgent();
            Task<DataItem> putDataTask = mDataClient.putDataItem(putDataReq);
            if (putDataTask.isSuccessful()){
                Log.d(TAG, "Current stats synced to phone");
            }
        }
        */

    }
}

package com.bose.wearable.sample;

//
//  SensorDataFragment.java
//  BoseWearable
//
//  Created by Tambet Ingo on 10/17/2018.
//  Copyright © 2018 Bose Corporation. All rights reserved.
//

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.ArrayMap;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.lifecycle.ViewModelProviders;

import com.bose.wearable.sample.dialogs.SamplePeriodDialog;
import com.bose.wearable.sample.viewmodels.SessionViewModel;
import com.bose.wearable.sample.views.SensorDataQuaternionView;
import com.bose.wearable.sample.views.SensorDataVectorBiasView;
import com.bose.wearable.sample.views.SensorDataVectorView;
import com.bose.wearable.sensordata.SensorValue;
import com.bose.wearable.services.wearablesensor.SamplePeriod;
import com.bose.wearable.services.wearablesensor.SensorConfiguration;
import com.bose.wearable.services.wearablesensor.SensorInformation;
import com.bose.wearable.services.wearablesensor.SensorType;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import static androidx.lifecycle.Lifecycle.Event.ON_PAUSE;
import static androidx.lifecycle.Lifecycle.Event.ON_RESUME;

@SuppressWarnings("PMD.UseConcurrentHashMap") // All Maps are used from UI thread only
public class SensorDataFragment extends Fragment {
    private static final int REQUEST_CODE_SAMPLE_PERIOD = 1;

    private SessionViewModel mViewModel;
    @Nullable
    private SamplePeriod mSamplePeriod;
    private ViewGroup mContainer;
    private TextView mSamplePeriodText;
    private Button mSamplePeriodButton;
    private final Map<SensorType, SensorDataVectorView> mSensorViews = new HashMap<>();
    private final Map<SensorType, ObservedRateUpdater> mRateUpdaters = new ArrayMap<>();

    //Data Writer
    private DataWriterTask mWriter;
    private Timer mTimer;

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sensor_data_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mContainer = view.findViewById(R.id.container);
        mSamplePeriodText = view.findViewById(R.id.sample_period_text);

        mSamplePeriodButton = view.findViewById(R.id.sample_period_button);
        mSamplePeriodButton.setOnClickListener(v -> onSamplePeriodButtonClicked());
    }

    @Override
    public void onActivityCreated(@Nullable final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mViewModel = ViewModelProviders.of(requireActivity()).get(SessionViewModel.class);

        mViewModel.wearableSensorInfo()
                .observe(this, this::onSensorInfoRead);

        mViewModel.wearableSensorConfiguration()
                .observe(this, this::onSensorConfRead);

        mViewModel.accelerometerData()
                .observe(this, this::onSensorData);

        mViewModel.gyroscopeData()
                .observe(this, this::onSensorData);

        mViewModel.rotationVectorData()
                .observe(this, this::onSensorData);

        mViewModel.gameRotationData()
                .observe(this, this::onSensorData);

        mViewModel.orientationData()
                .observe(this, this::onSensorData);

        mViewModel.magnetometerData()
                .observe(this, this::onSensorData);

        mViewModel.uncalibratedMagnetometerData()
                .observe(this, this::onSensorData);

        updateSamplePeriod(mViewModel.sensorSamplePeriod());

        final LifecycleObserver lifecycleObserver = new LifecycleObserver() {
            @OnLifecycleEvent(ON_RESUME)
            void onResume() {
                resumeRateUpdaters();
            }

            @OnLifecycleEvent(ON_PAUSE)
            void onPausee() {
                stopRateUpdaters();
            }
        };

        getLifecycle().addObserver(lifecycleObserver);
        mTimer = new Timer("IMUDataWriter");
        mWriter = new DataWriterTask();
        mTimer.scheduleAtFixedRate(mWriter, 0, 50);

        // No explanation needed; request the permission
        ActivityCompat.requestPermissions(this.getActivity(),
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                1);
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final @Nullable Intent data) {
        switch (requestCode) {
            case REQUEST_CODE_SAMPLE_PERIOD:
                if (resultCode == Activity.RESULT_OK && data != null) {
                    final SamplePeriod samplePeriod = (SamplePeriod) data.getSerializableExtra(SamplePeriodDialog.EXTRA_PERIOD);
                    final SensorType sensorType = (SensorType) data.getSerializableExtra(SamplePeriodDialog.EXTRA_SENSOR_TYPE);
                    if (sensorType != null) {
                        mSamplePeriod = samplePeriod;
                        onSensorEnabled(sensorType, true);
                    } else {
                        mViewModel.sensorSamplePeriod(samplePeriod);
                    }
                }
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
                break;
        }
    }

    private void onSensorInfoRead(@NonNull final SensorInformation sensorInformation) {
        stopRateUpdaters();
        mRateUpdaters.clear();

        mSensorViews.clear();
        mContainer.removeAllViews();

        for (final SensorType sensorType : sensorInformation.availableSensors()) {
            final SensorDataVectorView vectorView;
            switch (sensorType) {
                case ACCELEROMETER:
                case GYROSCOPE:
                case ORIENTATION:
                case MAGNETOMETER:
                    vectorView = new SensorDataVectorView(getContext());
                    break;
                case ROTATION_VECTOR:
                case GAME_ROTATION_VECTOR:
                    vectorView = new SensorDataQuaternionView(getContext());
                    break;
                case UNCALIBRATED_MAGNETOMETER:
                    vectorView = new SensorDataVectorBiasView(getContext());
                    break;
                default:
                    vectorView = null;
                    break;
            }

            if (vectorView != null) {
                vectorView.sensorType(sensorType);

                final ObservedRateUpdater rateUpdater = new ObservedRateUpdater(vectorView::frequency);
                mRateUpdaters.put(sensorType, rateUpdater);

                final boolean sensorEnabled = mViewModel.sensorEnabled(sensorType);

                vectorView.enabled(sensorEnabled);
                vectorView.enabledListener(isEnabled -> onSensorEnabled(sensorType, isEnabled));
                mSensorViews.put(sensorType, vectorView);
                mContainer.addView(vectorView);

                if (sensorEnabled) {
                    rateUpdater.start();
                }
            }
        }
    }

    private void onSensorConfRead(@NonNull final SensorConfiguration sensorConfiguration) {
        mSamplePeriodButton.setEnabled(!sensorConfiguration.enabledSensors().isEmpty());

        mSamplePeriod = sensorConfiguration.enabledSensorsSamplePeriod();
        updateSamplePeriod(mSamplePeriod);

        for (final Map.Entry<SensorType, SensorDataVectorView> entry : mSensorViews.entrySet()) {
            entry.getValue().enabled(mViewModel.sensorEnabled(entry.getKey()));
        }
    }

    private void showSamplePeriodDialog(@Nullable final SensorType forSenor) {
        final List<SamplePeriod> available = mViewModel.availableSamplePeriods();
        final DialogFragment dialog = SamplePeriodDialog.newInstance(available, forSenor);
        dialog.setTargetFragment(this, REQUEST_CODE_SAMPLE_PERIOD);
        dialog.show(getFragmentManager(), "sample-period-dialog");
    }

    private void onSamplePeriodButtonClicked() {
        showSamplePeriodDialog(null);
    }

    private boolean onSensorEnabled(@NonNull final SensorType sensorType, final boolean isEnabled) {
        if (isEnabled && mSamplePeriod == null) {
            showSamplePeriodDialog(sensorType);
            return false;
        }

        final ObservedRateUpdater rateUpdater = mRateUpdaters.get(sensorType);
        if (isEnabled) {
            rateUpdater.start();
        } else {
            rateUpdater.stop();
        }

        mViewModel.enableSensor(sensorType, isEnabled ? mSamplePeriod.milliseconds() : 0);

        return true;
    }

    private void updateSamplePeriod(@Nullable final SamplePeriod samplePeriod) {
        final short millis = samplePeriod != null ? samplePeriod.milliseconds() : 0;
        final double hz = millis != 0 ? 1000.0f / millis : 0;
        final String txt = requireContext()
                .getString(R.string.sample_period_ms_hz, millis, String.valueOf(hz));
        mSamplePeriodText.setText(txt);
    }

    private void onSensorData(@NonNull final SensorValue sensorValue) {
        final SensorType sensorType = sensorValue.sensorType();
        final SensorDataVectorView view = mSensorViews.get(sensorType);
        if (view != null) {
            view.sensorValue(sensorValue);
        }

        final ObservedRateUpdater rateUpdater = mRateUpdaters.get(sensorType);
        if (rateUpdater != null) {
            rateUpdater.updateReceived();
        }

        switch (sensorType) {
            case ACCELEROMETER:
                sensorValue.vector(mWriter.getmLinearAccVector(), 0);
            case GYROSCOPE:
                sensorValue.vector(mWriter.getmGyroVector(), 0);
            case ORIENTATION:
                sensorValue.vector(mWriter.getmRotationVector(), 0);
            default:
                break;
        }
    }

    private void resumeRateUpdaters() {
        for (final Map.Entry<SensorType, ObservedRateUpdater> entry : mRateUpdaters.entrySet()) {
            if (mViewModel.sensorEnabled(entry.getKey())) {
                entry.getValue().start();
            }
        }
    }

    private void stopRateUpdaters() {
        for (final ObservedRateUpdater rateUpdater : mRateUpdaters.values()) {
            rateUpdater.stop();
        }
    }

    class DataWriterTask extends TimerTask {
        private long mStartTime;
        private float[] mRotationVector = new float[3];
        private float[] mLinearAccVector = new float[3];
        private float[] mGyroVector = new float [3];

        private File mFile;
        private FileWriter fw;

        public DataWriterTask() {
            // Get the directory for the user's public pictures directory.
            mFile = new File("/sdcard/", "output.csv");
            Log.i("Data File:", mFile.getAbsolutePath());
            try {
                fw = new FileWriter(mFile);
                fw.write("Time,RoatX,RoatY,RoatZ," +
                        "GyroX,GyroY,GyroZ," +
                        "AccX,AccY,AccZ,\n");
                fw.flush();
            } catch (IOException e) {
                System.out.println(e.toString());
            }
            mStartTime = System.currentTimeMillis();
        }

        public void setmGyroVector(float[] mGyroVector) {
            this.mGyroVector = mGyroVector;
        }

        public void setmRotationVector(float[] mRotationVector) {
            this.mRotationVector = mRotationVector;
        }

        public void setmLinearAccVector(float[] mLinearAccVector) {
            this.mLinearAccVector = mLinearAccVector;
        }

        public float[] getmGyroVector() {
            return mGyroVector;
        }

        public float[] getmRotationVector() {
            return mRotationVector;
        }

        public float[] getmLinearAccVector() {
            return mLinearAccVector;
        }

        @Override
        public void run() {
            try {
                long time = System.currentTimeMillis() - mStartTime;
                fw.write(Long.toString(time) + ",");
                for (int i = 0; i < mRotationVector.length; i++)
                    fw.write(mRotationVector[i] + ",");
                for (int i = 0; i < mGyroVector.length; i++)
                    fw.write(mGyroVector[i] + ",");
                for (int i = 0; i < mLinearAccVector.length; i++)
                    fw.write(mLinearAccVector[i] + ",");
                fw.write("\n");
                fw.flush();
            }
            catch (IOException e) {
                System.out.println(e.toString());
            }
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case 1: {

                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.
                } else {

                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                    Toast.makeText(getActivity(), "Permission denied to read your External storage", Toast.LENGTH_SHORT).show();
                }
                return;
            }

            // other 'case' lines to check for other
            // permissions this app might request
        }
    }
}

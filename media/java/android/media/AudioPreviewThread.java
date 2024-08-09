/*
*
* Copyright 2024 Rockchip Electronics S.LSI Co. LTD
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package android.media;

import android.annotation.NonNull;
import android.media.AudioTrack;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.AudioAttributes;
import android.util.Log;
import android.content.Context;
import android.app.Service;
import android.os.SystemClock;

public class AudioPreviewThread implements Runnable {
    private static final String TAG = "AudioPreviewThread";

    private final int mSampleRate;
    private final int mChannelCount;
    private final int mAudioEncoding;
    private Thread mThread;
    private boolean mGo;
    private AudioRecord mRecorder;
    private AudioTrack mTracker;
    private Context mContext;
    private final AudioManager mAudioManager;

    public AudioPreviewThread(@NonNull Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Illegal null Context argument");
        }
        mContext = context;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mSampleRate = 44100;
        mChannelCount = AudioFormat.CHANNEL_IN_STEREO;
        mAudioEncoding = AudioFormat.ENCODING_PCM_16BIT;
    }

    private @NonNull short[] toShortArray(@NonNull byte[] src) {
        int count = src.length >> 1;
        short[] dest = new short[count];
        for (int i = 0; i < count; i++) {
            dest[i] = (short) (src[i * 2 + 1] << 8 | src[2 * i + 0] & 0xff);
        }
        return dest;
    }

    private @NonNull byte[] toByteArray(@NonNull short[] src) {
        int count = src.length;
        byte[] dest = new byte[count << 1];
        for (int i = 0; i < count; i++) {
            dest[i * 2 + 0] = (byte) (src[i] >> 0);
            dest[i * 2 + 1] = (byte) (src[i] >> 8);
        }
        return dest;
    }

    private @NonNull void toByteArray(@NonNull byte[] dest, @NonNull short[] src) {
        int count = src.length;
        if (dest.length / 2 < count)
            count = dest.length / 2;
        for (int i = 0; i < count; i++) {
            dest[i * 2 + 0] = (byte) (src[i] >> 0);
            dest[i * 2 + 1] = (byte) (src[i] >> 8);
        }
    }

    private void rampVolume(@NonNull byte[] inBytes, boolean up)
    {
        short[] inShorts = toShortArray(inBytes);
        int frameCount = inShorts.length / 2;
        float vl = up ? 0.0f : 1.0f;
        float vlInc = (up ? 1.0f : -1.0f) / frameCount;
        for (int i = 0; i < frameCount; i++) {
            float a = vl * (float)inShorts[i * 2];
            inShorts[i * 2] = (short)a;
            inShorts[i * 2 + 1] = (short)a;
            vl += vlInc;
        }
        toByteArray(inBytes, inShorts);
    }

    private void createRecorder() {
        int bufSize = AudioRecord.getMinBufferSize(mSampleRate, mChannelCount, mAudioEncoding);
        AudioDeviceInfo[] deviceList = mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        AudioDeviceInfo preferDevice = null;

        //retry to find hdmiin, timeout: 1s
        int retry = 0;
        do {
            SystemClock.sleep(50);
            for (AudioDeviceInfo device : deviceList) {
                if (device.getInternalType() == AudioManager.DEVICE_IN_HDMI) {
                    Log.d(TAG, "find hdmiin, retry: " + retry);
                    preferDevice = device;
                    break;
                }
            }
            retry++;
            deviceList = mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        } while (preferDevice == null && retry < 20);

        mRecorder = new AudioRecord.Builder()
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(mSampleRate)
                                .setChannelMask(mChannelCount)
                                .setEncoding(mAudioEncoding)
                                .build())
                        .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                        .setBufferSizeInBytes(bufSize)
                        .build();
        if (preferDevice != null) {
            mRecorder.setPreferredDevice(preferDevice);
        } else {
            Log.d(TAG, "Did not find hdmiin, use default!, retry: " + retry);
        }

        if (mRecorder.getState() == AudioRecord.STATE_UNINITIALIZED) {
            throw new RuntimeException("Could not make the AudioRecord - UNINITIALIZED");
        }
    }

    private void createTracker() {
        AudioFormat audioFormat = (new AudioFormat.Builder())
                .setChannelMask(mChannelCount)
                .setEncoding(mAudioEncoding)
                .setSampleRate(mSampleRate).build();
        int bufSize = AudioTrack.getMinBufferSize(mSampleRate,
                mChannelCount, mAudioEncoding);
        if (bufSize < 8192) {
            Log.w(TAG, "AudioTrack bufSize = " + bufSize + ", set to 8192");
            bufSize = 8192;
        }
        mTracker = new AudioTrack(
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                    .build(),
                    audioFormat, bufSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);

        if (mTracker.getState() == AudioTrack.STATE_UNINITIALIZED) {
            throw new RuntimeException("Could not make the AudioTrack - UNINITIALIZED");
        }
    }

    public void run() {
        startAudioRecording();
        startAudioTracking();

        int readBytes = 0;
        int bufSize = AudioRecord.getMinBufferSize(mSampleRate, mChannelCount, mAudioEncoding);
        byte[] inBytes = new byte[bufSize];

        // discard 500ms audio data
        int preReadCount = 1 + (mSampleRate * 2 * 2) / 2 / bufSize;
        while (mGo && preReadCount-- >= 0) {
            readBytes = mRecorder.read(inBytes, 0, bufSize);
            if (readBytes < 0) {
                Log.e(TAG, "before ramp read err: " + readBytes);
                mGo = false;
            }
        }
        if (!mGo) {
            Log.d(TAG, "exit audiopreview thread before ramp.");
            stopAudioRecording();
            stopAudioTracking();
            return;
        }

        // ramp volume at the beginning to avoid pop sound
        rampVolume(inBytes, true);

        while (mGo) {
            mTracker.write(inBytes, 0, readBytes);
            readBytes = mRecorder.read(inBytes, 0, bufSize);
            if (readBytes < 0) {
                Log.e(TAG, "after ramp read err: " + readBytes);
                mGo = false;
            }
        }
        stopAudioRecording();
        stopAudioTracking();
    }

    public void startAudioPreview() {
        Log.d(TAG, "startAudioPreview in");
        if (mThread == null) {
            mGo = true;
            Log.d(TAG, "start new thread");
            mThread = new Thread(this);
            mThread.start();
        }
        Log.d(TAG, "startAudioPreview out");
    }

    public void stopAudioPreview() {
        Log.d(TAG, "stopAudioPreview in");
        mGo = false;
        if (mThread != null) {
            try {
                mThread.join(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            mThread = null;
            Log.d(TAG, "old thread end");
        }
        Log.d(TAG, "stopAudioPreview out");
    }

    public void setVolume(float vol) {
        if (mTracker != null)
            mTracker.setVolume(vol);
    }

    private void startAudioRecording() {
        stopAudioRecording();
        createRecorder();
        mRecorder.startRecording();
    }

    private void stopAudioRecording() {
        if (mRecorder != null) {
            Log.d(TAG, "stopAudioRecording in");
            mRecorder.stop();
            mRecorder.release();
            mRecorder = null;
            Log.d(TAG, "stopAudioRecording out");
        }
    }

    private void startAudioTracking() {
        stopAudioTracking();
        createTracker();
        mTracker.play();
    }

    private void stopAudioTracking() {
        if (mTracker != null) {
            Log.d(TAG, "stopAudioTracking in");
            mTracker.setVolume(0.0f);
            mTracker.pause();
            SystemClock.sleep(50);
            mTracker.stop();
            mTracker.release();
            mTracker = null;
            Log.d(TAG, "stopAudioTracking out");
        }
    }
}

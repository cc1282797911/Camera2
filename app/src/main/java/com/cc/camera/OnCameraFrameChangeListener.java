package com.cc.camera;

import android.media.Image;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

public interface OnCameraFrameChangeListener {

    //结束后image记得调用close
    @WorkerThread
    void onCameraFrameChange(@NonNull Image image, int viewWidth, int viewHeight);

}

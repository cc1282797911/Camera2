package com.cc.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class CameraView extends TextureView {

    private boolean isInitComplete = false;

    private Size previewSize;

    private OnCameraFrameChangeListener onCameraFrameChangeListener;

    private OnCameraInitCompleteListener onCameraInitCompleteListener;

    private OnCameraStateChangeListener onCameraStateChangeListener;

    private CameraManager cameraManager;

    private CameraDevice cameraDevice;

    private CameraCaptureSession cameraCaptureSession;

    private ImageReader imageReader;

    private Handler workHandler;

    private Handler imageReaderWorkHandler;

    private Handler sessionHandler;

    private String cameraId;

    private boolean isCreatePreview;

    private Surface surface;

    private CaptureRequest.Builder mPreviewRequestBuilder;

    private CoordinateTransformer coordinateTransformer;

    public CameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        HandlerThread handlerThread = new HandlerThread("CameraView");
        handlerThread.start();
        workHandler = new Handler(handlerThread.getLooper());
        handlerThread = new HandlerThread("imageReader");
        handlerThread.start();
        imageReaderWorkHandler = new Handler(handlerThread.getLooper());
        handlerThread = new HandlerThread("sessionHandler");
        handlerThread.start();
        sessionHandler = new Handler(handlerThread.getLooper());
        cameraManager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
        setSurfaceTextureListener(new SurfaceTextureListener());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        workHandler.getLooper().quit();
        imageReaderWorkHandler.getLooper().quit();
        setSurfaceTextureListener(null);
        destroyCameraPreviewSession();
        sessionHandler.getLooper().quitSafely();
    }

    public void createCameraPreviewSession() {
        if(!isInitComplete){
            throw new RuntimeException("...init?");
        }
        Size[] choicesSizes = null;
        try {
            String[] cameraArray = cameraManager.getCameraIdList();
            if(cameraArray.length < 2){
                return;
            }
            cameraId = cameraArray[1];
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            coordinateTransformer = new CoordinateTransformer(characteristics, new RectF(0, 0, getWidth(), getHeight()));
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if(map != null){
                choicesSizes = map.getOutputSizes(SurfaceTexture.class);
            }
        } catch (Throwable throwable){
            throwable.printStackTrace();
        }
        if(choicesSizes == null){
            return;
        }
        previewSize = chooseOptimalSize(choicesSizes, getWidth(), getHeight());
        configureTransform(CameraView.this, previewSize, getWidth(), getHeight());
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if(isCreatePreview){
                   return;
                }
                isCreatePreview = true;
                if(!checkPermission()){
                    return;
                }
                initImageReader();
                try {
                    final SurfaceTexture surfaceTexture = getSurfaceTexture();
                    surface = new Surface(surfaceTexture);
                    cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(@NonNull CameraDevice camera) {
                            cameraDevice = camera;
                            try {
                                surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                                mPreviewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                mPreviewRequestBuilder.addTarget(surface);
                                mPreviewRequestBuilder.addTarget(imageReader.getSurface());
                                List<Surface> surfaceList = new ArrayList<>(2);
                                surfaceList.add(surface);
                                surfaceList.add(imageReader.getSurface());
                                camera.createCaptureSession(surfaceList, new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                                        CameraView.this.cameraCaptureSession = cameraCaptureSession;
                                        try {
                                            // 自动对焦应
                                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                            CaptureRequest mPreviewRequest = mPreviewRequestBuilder.build();
                                            cameraCaptureSession.setRepeatingRequest(mPreviewRequest, null, workHandler);
                                        } catch (Throwable throwable) {
                                            throwable.printStackTrace();
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {}

                                }, workHandler);
                            } catch (Throwable throwable){
                                throwable.printStackTrace();
                            }
                        }

                        @Override
                        public void onDisconnected(@NonNull CameraDevice camera) {
                            cameraDevice = camera;
                        }

                        @Override
                        public void onError(@NonNull CameraDevice camera, int error) {
                            cameraDevice = camera;
                        }
                    }, workHandler);
                } catch (Throwable throwable){
                    throwable.printStackTrace();
                }
            }
        };
        try {
            sessionHandler.post(runnable);
        } catch (Throwable throwable){
            throwable.printStackTrace();
        }
    }

    private void initImageReader(){
        int width = previewSize.getHeight();
        int height = previewSize.getWidth();
        imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 30);
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                } catch (Throwable throwable){
                    throwable.printStackTrace();
                }
                if(image == null){
                    return;
                }
                OnCameraFrameChangeListener listener = onCameraFrameChangeListener;
                if(listener == null){
                    try {
                        image.close();
                    } catch (Throwable throwable){
                        throwable.printStackTrace();
                    }
                }else{
                    listener.onCameraFrameChange(image, getWidth(), getHeight());
                }
            }
        }, imageReaderWorkHandler);
    }

    public void destroyCameraPreviewSession(){
        sessionHandler.post(new Runnable() {
            @Override
            public void run() {
                if(!isCreatePreview){
                    return;
                }
                isCreatePreview = false;
                if(imageReader != null){
                    try {
                        imageReader.close();
                    } catch (Throwable throwable){
                        throwable.printStackTrace();
                    }
                    imageReader = null;
                }
                if(cameraCaptureSession != null){
                    try {
                        cameraCaptureSession.close();
                    } catch (Throwable throwable){
                        throwable.printStackTrace();
                    }
                    cameraCaptureSession = null;
                }
                if(cameraDevice != null){
                    try {
                        cameraDevice.close();
                    } catch (Throwable throwable){
                        throwable.printStackTrace();
                    }
                    cameraDevice = null;
                }
                if(surface != null){
                    try {
                        Canvas canvas = surface.lockCanvas(new Rect(0, 0, getWidth(), getHeight()));
                        surface.unlockCanvasAndPost(canvas);
                        surface.release();
                    } catch (Throwable throwable){
                        throwable.printStackTrace();
                    }
                }
                post(new Runnable() {
                    @Override
                    public void run() {
                        if(onCameraStateChangeListener != null){
                            onCameraStateChangeListener.onPreviewStop();
                        }
                    }
                });
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if(event.getAction() == MotionEvent.ACTION_UP){
            //手动对焦
            //focus(event.getX(), event.getY());
            return true;
        }
        return super.onTouchEvent(event);
    }

    private Rect previewRect;
    private Rect focusRect;

    private void focus(float cx, float cy){
        previewRect = new Rect(0, 0, previewSize.getWidth(), previewSize.getHeight());
        focusRect = new Rect(0, 0, 0, 0);
        MeteringRectangle focusRect = getFocusArea(cx, cy, true);
        MeteringRectangle meterRect = getFocusArea(cx, cx, false);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{focusRect});
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{meterRect});
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        try {
            cameraCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber);
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                    super.onCaptureFailed(session, request, failure);
                }
            }, workHandler);
        } catch (Throwable throwable){
            throwable.printStackTrace();
        }
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
        try {
            try {
                cameraCaptureSession.capture(mPreviewRequestBuilder.build(), null, workHandler);
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        } catch (Throwable throwable){
            throwable.printStackTrace();
        }
    }

    public MeteringRectangle getFocusArea(float cx, float cy, boolean isFocusArea) {
        if (isFocusArea) {
            return calcTapAreaForCamera2(cx, cy, previewRect.width() / 5, 1000);
        } else {
            return calcTapAreaForCamera2(cx, cy, previewRect.height() / 4, 1000);
        }
    }

    private MeteringRectangle calcTapAreaForCamera2(float currentX, float currentY, int areaSize, int weight) {
        int left = clamp((int) currentX - areaSize / 2,
                previewRect.left, previewRect.right - areaSize);
        int top = clamp((int) currentY - areaSize / 2,
                previewRect.top, previewRect.bottom - areaSize);
        RectF rectF = new RectF(left, top, left + areaSize, top + areaSize);
        toFocusRect(coordinateTransformer.toCameraSpace(rectF));
        return new MeteringRectangle(focusRect, weight);
    }

    private int clamp(int x, int min, int max) {
        if (x > max) {
            return max;
        }
        if (x < min) {
            return min;
        }
        return x;
    }

    private void toFocusRect(RectF rectF) {
        focusRect.left = Math.round(rectF.left);
        focusRect.top = Math.round(rectF.top);
        focusRect.right = Math.round(rectF.right);
        focusRect.bottom = Math.round(rectF.bottom);
    }

    private boolean checkPermission(){
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    public void setOnCameraFrameChangeListener(OnCameraFrameChangeListener onCameraFrameChangeListener) {
        this.onCameraFrameChangeListener = onCameraFrameChangeListener;
    }

    public void setOnCameraInitCompleteListener(OnCameraInitCompleteListener onCameraInitCompleteListener) {
        this.onCameraInitCompleteListener = onCameraInitCompleteListener;
    }

    public void setOnCameraStateChangeListener(OnCameraStateChangeListener onCameraStateChangeListener) {
        this.onCameraStateChangeListener = onCameraStateChangeListener;
    }

    public boolean isInitComplete() {
        return isInitComplete;
    }

    /**
     * 返回view高宽和支持高宽最相近的尺寸，如果无可用尺寸，则会返回viewWidth、viewHeight组成的尺寸（应该不会存在这种情况）
     * **/
    private Size chooseOptimalSize(Size[] choices, int viewWidth, int viewHeight) {
        Size size = null;
        if(choices != null){
            for(Size s : choices){
                int c = s.getWidth() - viewWidth + s.getHeight() - viewHeight;
                c = Math.abs(c);
                if(size == null){
                    size = s;
                }else{
                    int oldC = size.getWidth() - viewWidth + size.getHeight() - viewHeight;
                    oldC = Math.abs(oldC);
                    if(c < oldC){
                        size = s;
                    }
                }
            }
        }
        if(size == null){
            size = new Size(viewWidth, viewHeight);
        }
        return size;
    }

    /**
     * 成像矩阵转换
     * **/
    private void configureTransform(TextureView textureView, Size optimumSize, float viewWidth, float viewHeight) {
        Matrix matrix = new Matrix();
        float optimumSizeWidth = optimumSize.getHeight();
        float optimumSizeHeight = optimumSize.getWidth();
        //取高宽缩放比例最大的
        float scale = Math.max(
                viewHeight / optimumSizeHeight,
                viewWidth / optimumSizeWidth
        );
        float finalViewWidth = optimumSizeWidth * scale;
        float finalViewHeight = optimumSizeHeight * scale;
        matrix.setScale(
                //相同的比例保证图像不会被拉伸
                scale,
                scale,
                //从图像中心缩放
                optimumSizeWidth / 2f,
                optimumSizeHeight / 2f
        );
        textureView.setTransform(matrix);
        //图像默认会拉伸至view的高宽，所以这里要设置view的高宽为转换后的高宽
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        if(layoutParams == null){
            throw new RuntimeException("getLayoutParams()不能为null");
        }
        layoutParams.width = (int) finalViewWidth;
        layoutParams.height = (int) finalViewHeight;
        setLayoutParams(layoutParams);
    }

    private class SurfaceTextureListener implements TextureView.SurfaceTextureListener {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            if(!isInitComplete){
                isInitComplete = true;
                if(onCameraInitCompleteListener != null){
                    onCameraInitCompleteListener.onInitComplete();
                }
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            destroyCameraPreviewSession();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    }

}

package com.cc.camera;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Window;
import android.widget.Toast;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        new Thread(){
            @Override
            public void run() {
                super.run();
                requestPermissions(new String[]{
                        Manifest.permission.CAMERA
                }, 1);
            }
        }.start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for(int i : grantResults){
            if(i != PackageManager.PERMISSION_GRANTED){
                Toast.makeText(this, "无摄像头使用权限", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }
        setContentView(R.layout.activity_main);
        final CameraView cameraView = findViewById(R.id.cv);
        cameraView.setOnCameraInitCompleteListener(new OnCameraInitCompleteListener() {
            @Override
            public void onInitComplete() {
                cameraView.createCameraPreviewSession();
            }
        });
    }
}

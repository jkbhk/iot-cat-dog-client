package com.example.myapplication;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;


public class MainActivity extends AppCompatActivity {

    Button captureButton;
    PreviewView previewView;
    ImageView overlay;
    ImageCapture imageCapture;
    TextView textViewMessage;
    ProgressBar spinner;

    String REQUIRED_PERMISSIONS[] = {Manifest.permission.CAMERA};

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        overlay = findViewById(R.id.blackOverlay);
        overlay.setVisibility(View.GONE);

        spinner = findViewById(R.id.progressBar_spinner);
        spinner.setVisibility(View.GONE);

        textViewMessage = findViewById(R.id.textView_message);

        captureButton = findViewById(R.id.button_capture);
        captureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                //disable button temporarily
                captureButton.setEnabled(false);
                captureButton.setVisibility(View.INVISIBLE);

                // show spinner
                spinner.setVisibility(View.VISIBLE);
                spinner.setIndeterminate(true);

                // show overlay
                overlay.setVisibility(View.VISIBLE);

                textViewMessage.setText("Sending snapshot to deep learning server...");
                capturePhoto();

            }
        });

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this,REQUIRED_PERMISSIONS,10);
        }

        // check server status
        tryConnectingToServer();

        // cameraX setup
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(()->{
            try{
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                startCameraX(cameraProvider);
            }catch(Exception e){
                Log.e("Main Activity","cameraX setup error");
            }
        },getExecutor());

    }

    private void resetSession(){
        captureButton.setEnabled(true);
        captureButton.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.GONE);
        overlay.setVisibility(View.GONE);

    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }


    private Executor getExecutor(){
        return ContextCompat.getMainExecutor(this);
    }

    private void startCameraX(ProcessCameraProvider cameraProvider){
        cameraProvider.unbindAll();
        CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build();

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        imageCapture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build();
        cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector,preview,imageCapture);
    }

    private void capturePhoto(){
        imageCapture.takePicture(
                getExecutor(),
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(ImageProxy image) {

                        // convert image data to jpg format
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        File data = convertBitmapToJPEGFile(bitmap);

                        // send jpg data to server
                        sendFileToServer(data);

                        // mandatory call
                        image.close();


                    }
                    @Override
                    public void onError(ImageCaptureException exception) {
                        Log.e("Main Activity","error capturing image with cameraX");
                    }
                }
        );
    }

    private File convertBitmapToJPEGFile(Bitmap bitmap) {
        File file = null;
        try {
            file = File.createTempFile("image", ".jpg", getCacheDir());
            FileOutputStream outputStream = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
            outputStream.flush();
            outputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return file;
    }

    private void tryConnectingToServer(){

        textViewMessage.setText("Connecting to deep learning server...");
        captureButton.setEnabled(false);
        captureButton.setVisibility(View.INVISIBLE);
        overlay.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.VISIBLE);


        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url("http://192.168.50.144:3000/")
                .build();


        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                textViewMessage.setText("Server is unavailable, try again later.");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        textViewMessage.setText("Start by pressing capture");
                        resetSession();
                    }
                });
            }
        });

    }

    private void sendFileToServer(File file) {

        OkHttpClient client = new OkHttpClient();

        RequestBody imageBody = RequestBody.create(MediaType.parse("image/jpeg"), file);
        RequestBody mbody =  new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "image.jpg", imageBody)
                .build();

        Request request = new Request.Builder()
                .url("http://192.168.50.144:3000/classify")
                .post(mbody)
                .build();


        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("Main Activity", "post request failure");
                resetSession();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String message = response.body().string();
                textViewMessage.setText(message);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        resetSession();
                    }
                });
            }
        });

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clearCache(this);
    }

    private void clearCache(Context context) {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(ACTIVITY_SERVICE);
        activityManager.clearApplicationUserData();
    }
}
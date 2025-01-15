package com.jujie.audiosdk;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.CaptureManager;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

public class PaipaiCaptureActivity extends AppCompatActivity {
    private DecoratedBarcodeView barcodeView; // 引用扫码视图

    // 可以添加自定义功能或界面修改
    private static final int GALLERY_REQUEST_CODE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(com.jujie.paipai.R.layout.paipai_capture);

        // 设置按钮点击事件
        Button galleryButton = findViewById(com.jujie.paipai.R.id.gallery_button);
        galleryButton.setOnClickListener(v -> openGallery());

//        try {
//            CameraManager cameraManager = null;
//            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
//                cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
//            }
//            if (cameraManager.getCameraIdList().length == 0) {
//                Toast.makeText(this, "没有检测到摄像头", Toast.LENGTH_SHORT).show();
//                return;
//            }
//        } catch (Exception e) {
//            Toast.makeText(this, "摄像头初始化失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
//        }
//
//        // 指定支持的条码格式
        List<BarcodeFormat> formats = Arrays.asList(
                BarcodeFormat.QR_CODE,
                BarcodeFormat.CODE_39,
                BarcodeFormat.CODE_93,
                BarcodeFormat.CODE_128
        );
//
//
        // 绑定扫码视图
        barcodeView = findViewById(com.jujie.paipai.R.id.zxing_barcode_scanner);

        Log.d("CaptureActivity", "---------------------------------------------------------");
        Log.d("CaptureActivity", "onCreate: " + barcodeView);

        barcodeView.setDecoderFactory(new DefaultDecoderFactory(formats));
        barcodeView.initializeFromIntent(getIntent());
        barcodeView.getBarcodeView().decodeContinuous(result -> {
            // 实时扫描结果
            Toast.makeText(this, "扫描结果：" + result.getText(), Toast.LENGTH_SHORT).show();
        });

        CaptureManager captureManager = new CaptureManager(this, barcodeView);
        captureManager.initializeFromIntent(getIntent(), savedInstanceState);
        captureManager.decode();
        ScanOptions options = new ScanOptions();
        options.setPrompt("请扫描条形码");

        options.setDesiredBarcodeFormats(ScanOptions.ONE_D_CODE_TYPES);

        ActivityResultLauncher<ScanOptions> scannerLauncher = registerForActivityResult(new ScanContract(), this::handleScanResult);
        scannerLauncher.launch(options);

//        Button galleryButton = findViewById(com.jujie.paipai.R.id.gallery_button);
//        galleryButton.setOnClickListener(v -> openGallery());
//
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

    }

    @Override
    protected void onResume() {
        super.onResume();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }


    private void handleScanResult(ScanIntentResult result) {
        if (result.getContents() == null) {
            Toast.makeText(this, "扫描被取消", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "扫描结果" + result.getContents().toString(), Toast.LENGTH_SHORT).show();
//            scannedValue.setText("扫描结果: " + result.getContents());
        }
    }

    private void openGallery() {
        Intent galleryIntent = new Intent(Intent.ACTION_GET_CONTENT);
        galleryIntent.setType("image/*");
        startActivityForResult(galleryIntent, GALLERY_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d("CaptureActivity", "onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode);

        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == GALLERY_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            Uri imageUri = data.getData();
            if (imageUri != null) {
                decodeQRCodeFromImage(imageUri);
            }
        }
    }

    private void decodeQRCodeFromImage(Uri uri) {
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
            if (bitmap == null) {
                Toast.makeText(this, "无法加载图片", Toast.LENGTH_SHORT).show();
                return;
            }
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

            RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
            BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));

            Result result = new com.google.zxing.MultiFormatReader().decode(binaryBitmap);

            if (result != null) {
                Toast.makeText(this, "从图片中解析的二维码结果：" + result.getText(), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "未能在图片中识别到二维码", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "解析二维码失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

}

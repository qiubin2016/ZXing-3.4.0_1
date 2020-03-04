/*
 * Copyright (C) 2008 ZXing authors
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

package com.google.zxing.client.android;

import com.google.android.material.snackbar.Snackbar;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.ResultPoint;
import com.google.zxing.client.android.camera.CameraManager;
import com.google.zxing.client.android.clipboard.ClipboardInterface;
import com.google.zxing.client.android.history.HistoryActivity;
import com.google.zxing.client.android.history.HistoryItem;
import com.google.zxing.client.android.history.HistoryManager;
import com.google.zxing.client.android.result.ResultButtonListener;
import com.google.zxing.client.android.result.ResultHandler;
import com.google.zxing.client.android.result.ResultHandlerFactory;
import com.google.zxing.client.android.result.supplement.SupplementalInfoRetriever;
import com.google.zxing.client.android.share.ShareActivity;
import com.google.zxing.client.android.utils.Utils;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.hardware.camera2.CaptureRequest;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;

/**
 * This activity opens the camera and does the actual scanning on a background thread. It draws a
 * viewfinder to help the user place the barcode correctly, shows feedback as the image processing
 * is happening, and then overlays the results when a scan is successful.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 * @author Sean Owen
 * 注意事项：在Redmi 6 Pro上（MIUI 11.0.4 android 9.0）运行会出现崩溃，报错："getDiskStats failed with result NOT_SUPPORTED and size 0"
 * 解决办法：app内需添加动态申请相机权限或者手动在系统权限管理里对该应用授权相机权限
 */
public final class CaptureActivity extends Activity implements SurfaceHolder.Callback, ActivityCompat.OnRequestPermissionsResultCallback{

  private static final String TAG = CaptureActivity.class.getSimpleName();

  private static final long DEFAULT_INTENT_RESULT_DURATION_MS = 1500L;
  private static final long BULK_MODE_SCAN_DELAY_MS = 1000L;

  private static final String[] ZXING_URLS = { "http://zxing.appspot.com/scan", "zxing://scan/" };

  private static final int HISTORY_REQUEST_CODE = 0x0000bacc;

  private static final Collection<ResultMetadataType> DISPLAYABLE_METADATA_TYPES =
      EnumSet.of(ResultMetadataType.ISSUE_NUMBER,
                 ResultMetadataType.SUGGESTED_PRICE,
                /**
                * ERROR_CORRECTION_LEVEL:容错率，也就是纠错水平，二维码破损一部分也能扫码就归功于容错率，容错率可分为L、 M、 Q、 H四个等级，
                * 其分别占比为：L：7% M：15% Q：25% H：35%。传null时，默认使用 “L”
                * 当然容错率越高，二维码能存储的内容也随之变小。
                */
                 ResultMetadataType.ERROR_CORRECTION_LEVEL,
                 ResultMetadataType.POSSIBLE_COUNTRY);

  private CameraManager cameraManager;
  private CaptureActivityHandler handler;
  private Result savedResultToShow;
  private ViewfinderView viewfinderView;
  private TextView statusView;
  private View resultView;
  private Result lastResult;
  private boolean hasSurface;
  private boolean copyToClipboard;
  private IntentSource source;
  private String sourceUrl;
  private ScanFromWebPageManager scanFromWebPageManager;
  private Collection<BarcodeFormat> decodeFormats;
  private Map<DecodeHintType,?> decodeHints;
  private String characterSet;
  private HistoryManager historyManager;
  private InactivityTimer inactivityTimer;
  private BeepManager beepManager;
  private AmbientLightManager ambientLightManager;

  private static final int PERMISSION_REQUEST_MULTI = 0;

  ViewfinderView getViewfinderView() {
    return viewfinderView;
  }

  public Handler getHandler() {
    return handler;
  }

  CameraManager getCameraManager() {
    return cameraManager;
  }

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);

    Window window = getWindow();
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    setContentView(R.layout.capture);

    hasSurface = false;
    inactivityTimer = new InactivityTimer(this);
    beepManager = new BeepManager(this);
    ambientLightManager = new AmbientLightManager(this);

    PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

//    requestPermission();  //动态申请权限

  }

  private void requestPermission() {
    final ArrayList<String> permissionList = new ArrayList<>();
    String[] permissions = new String[]{Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE};

    //逐个判断是否还有未通过的权限
    for (int i = 0; i < permissions.length; i++) {
      int checkSelfPermission = -1;
      try {
        checkSelfPermission = ContextCompat.checkSelfPermission(this, permissions[i]);
        if (PackageManager.PERMISSION_GRANTED == checkSelfPermission) {
          permissionList.add(permissions[i]);
        }
      } catch (RuntimeException e) {
        Toast.makeText(this, "please open those permission", Toast.LENGTH_SHORT)
                .show();
        Log.e(TAG, "RuntimeException:" + e.getMessage());

        return ;
      }
    }
    if (permissionList.size() > 0) {
      Log.i(TAG, "==============================size > 0");
      // Request the permission. The result will be received in onRequestPermissionResult().
      ActivityCompat.requestPermissions(this,
              permissionList.toArray(new String[permissionList.size()]),
              PERMISSION_REQUEST_MULTI);
    } else {
      Log.i(TAG, "==============================size > 1");
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    // BEGIN_INCLUDE(onRequestPermissionsResult)
    if (requestCode == PERMISSION_REQUEST_MULTI) {
      // Request for camera permission.
      if (grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        // Permission has been granted. Start camera preview Activity.
        try {
//          Snackbar.make(mLayout, R.string.camera_permission_granted,
//                  Snackbar.LENGTH_SHORT)
//                  .show();
          Toast.makeText(this, R.string.camera_permission_granted, Toast.LENGTH_SHORT)
                  .show();
        } catch (Exception e) {
          Log.i(TAG, "====================crash 1");
          e.printStackTrace();
        }
//        startCamera();
      } else {
        // Permission request was denied.
//        Snackbar.make(mLayout, R.string.camera_permission_denied,
//                Snackbar.LENGTH_SHORT)
//                .show();
        Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_SHORT)
                .show();
      }
    }
    // END_INCLUDE(onRequestPermissionsResult)
  }

  @Override
  protected void onResume() {
    super.onResume();
    Log.i(TAG, "onResume======================================");
    // historyManager must be initialized here to update the history preference
    historyManager = new HistoryManager(this);
    historyManager.trimHistory();

    // CameraManager must be initialized here, not in onCreate(). This is necessary because we don't
    // want to open the camera driver and measure the screen size if we're going to show the help on
    // first launch. That led to bugs where the scanning rectangle was the wrong size and partially
    // off screen.
    cameraManager = new CameraManager(getApplication());

    viewfinderView = (ViewfinderView) findViewById(R.id.viewfinder_view);
    viewfinderView.setCameraManager(cameraManager);

    resultView = findViewById(R.id.result_view);
    statusView = (TextView) findViewById(R.id.status_view);

    handler = null;
    lastResult = null;

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

    if (prefs.getBoolean(PreferencesActivity.KEY_DISABLE_AUTO_ORIENTATION, true)) {
      setRequestedOrientation(getCurrentOrientation());
      Log.i(TAG, "onResume:getCurrentOrientation");
    } else {
      setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
      Log.i(TAG, "onResume:ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE");
    }

    resetStatusView();


    beepManager.updatePrefs();
    ambientLightManager.start(cameraManager);

    inactivityTimer.onResume();

    Intent intent = getIntent();

    copyToClipboard = prefs.getBoolean(PreferencesActivity.KEY_COPY_TO_CLIPBOARD, true)
        && (intent == null || intent.getBooleanExtra(Intents.Scan.SAVE_HISTORY, true));

    source = IntentSource.NONE;
    sourceUrl = null;
    scanFromWebPageManager = null;
    decodeFormats = null;
    characterSet = null;

    if (intent != null) {
      Log.i(TAG, "onResume:intent is not null!");
      String action = intent.getAction();
      String dataString = intent.getDataString();

      Log.i(TAG, "Intent get type:" + intent.getType());
      Log.i(TAG, "Intent get action:" + intent.getAction());
      Log.i(TAG, "Intent get dataString:" + intent.getDataString());
      Log.i(TAG, "Intent get scheme:" + intent.getScheme());

      if (Intents.Scan.ACTION.equals(action)) {
        Log.i(TAG, "action == com.google.zxing.client.android.SCAN");
        // Scan the formats the intent requested, and return the result to the calling activity.
        source = IntentSource.NATIVE_APP_INTENT;
        decodeFormats = DecodeFormatManager.parseDecodeFormats(intent);
        decodeHints = DecodeHintManager.parseDecodeHints(intent);

        if (intent.hasExtra(Intents.Scan.WIDTH) && intent.hasExtra(Intents.Scan.HEIGHT)) {
          int width = intent.getIntExtra(Intents.Scan.WIDTH, 0);
          int height = intent.getIntExtra(Intents.Scan.HEIGHT, 0);
          if (width > 0 && height > 0) {
            cameraManager.setManualFramingRect(width, height);
          }
        }

        if (intent.hasExtra(Intents.Scan.CAMERA_ID)) {
          int cameraId = intent.getIntExtra(Intents.Scan.CAMERA_ID, -1);
          if (cameraId >= 0) {
            cameraManager.setManualCameraId(cameraId);
          }
        }
        
        String customPromptMessage = intent.getStringExtra(Intents.Scan.PROMPT_MESSAGE);
        if (customPromptMessage != null) {
          statusView.setText(customPromptMessage);
        }

      } else if (dataString != null &&
                 dataString.contains("http://www.google") &&
                 dataString.contains("/m/products/scan")) {

        // Scan only products and send the result to mobile Product Search.
        source = IntentSource.PRODUCT_SEARCH_LINK;
        sourceUrl = dataString;
        decodeFormats = DecodeFormatManager.PRODUCT_FORMATS;

      } else if (isZXingURL(dataString)) {

        // Scan formats requested in query string (all formats if none specified).
        // If a return URL is specified, send the results there. Otherwise, handle it ourselves.
        source = IntentSource.ZXING_LINK;
        sourceUrl = dataString;
        Uri inputUri = Uri.parse(dataString);
        scanFromWebPageManager = new ScanFromWebPageManager(inputUri);
        decodeFormats = DecodeFormatManager.parseDecodeFormats(inputUri);
        // Allow a sub-set of the hints to be specified by the caller.
        decodeHints = DecodeHintManager.parseDecodeHints(inputUri);

      } else {
        Log.i(TAG, "intent else");
      }

      characterSet = intent.getStringExtra(Intents.Scan.CHARACTER_SET);
      Log.i(TAG, "characterSet:" + characterSet);
    }

    SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
    SurfaceHolder surfaceHolder = surfaceView.getHolder();
    if (hasSurface) {
      // The activity was paused but not stopped, so the surface still exists. Therefore
      // surfaceCreated() won't be called, so init the camera here.
      initCamera(surfaceHolder);  //-----------------------------------------初始化相机相关参数
    } else {
      // Install the callback and wait for surfaceCreated() to init the camera.
      surfaceHolder.addCallback(this);
    }
  }

  private int getCurrentOrientation() {
    int rotation = getWindowManager().getDefaultDisplay().getRotation();
    Log.i(TAG, "current rotation:" + rotation);
    Log.i(TAG, "getResources().getConfiguration().orientation:" + getResources().getConfiguration().orientation);
    Log.i(TAG, "Configuration.ORIENTATION_LANDSCAPE:" + Configuration.ORIENTATION_LANDSCAPE);
    if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
      switch (rotation) {
        case Surface.ROTATION_0:
        case Surface.ROTATION_90:
          return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        default:
          return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
      }
    } else {
      switch (rotation) {
        case Surface.ROTATION_0:
        case Surface.ROTATION_270:
          return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        default:
          return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
      }
    }
  }
  
  private static boolean isZXingURL(String dataString) {
    if (dataString == null) {
      return false;
    }
    for (String url : ZXING_URLS) {
      if (dataString.startsWith(url)) {
        return true;
      }
    }
    return false;
  }

  //当系统即将启动另一个activity之前调用
  @Override
  protected void onPause() {
    if (handler != null) {
      handler.quitSynchronously();
      handler = null;
    }
    inactivityTimer.onPause();
    ambientLightManager.stop();
    beepManager.close();
    cameraManager.closeDriver();  //关闭相机等操作
    //historyManager = null; // Keep for onActivityResult
    if (!hasSurface) {
      SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
      SurfaceHolder surfaceHolder = surfaceView.getHolder();
      surfaceHolder.removeCallback(this);
    }
    super.onPause();
  }

  //当前activity被销毁之前将会调用
  @Override
  protected void onDestroy() {
    inactivityTimer.shutdown();
    super.onDestroy();
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    Log.i(TAG, "onKeyDown");
    switch (keyCode) {
      case KeyEvent.KEYCODE_BACK:  //手机的返回键
        Log.i(TAG, "key code:back, source:" + source);
        if (source == IntentSource.NATIVE_APP_INTENT) {
          Log.i(TAG, "intent source == native app intent");
          setResult(RESULT_CANCELED);
          finish();
          return true;
        }
        if ((source == IntentSource.NONE || source == IntentSource.ZXING_LINK) && lastResult != null) {
          Log.i(TAG, "intent source == none,lastResult:" + lastResult.toString());
          restartPreviewAfterDelay(0L);
          return true;
        }
        break;
      case KeyEvent.KEYCODE_FOCUS:
      case KeyEvent.KEYCODE_CAMERA:
        // Handle these events so they don't launch the Camera app
        return true;
      // Use volume up/down to turn on light
      case KeyEvent.KEYCODE_VOLUME_DOWN:  //音量减--关闭闪光灯
        cameraManager.setTorch(false);
        return true;
      case KeyEvent.KEYCODE_VOLUME_UP:  //音量加--打开闪光灯
        cameraManager.setTorch(true);
        return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  /**---------------menu begin----------------**/
  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MenuInflater menuInflater = getMenuInflater();
    menuInflater.inflate(R.menu.capture, menu);
    return super.onCreateOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    Intent intent = new Intent(Intent.ACTION_VIEW);
    intent.addFlags(Intents.FLAG_NEW_DOC);
    switch (item.getItemId()) {
      case R.id.menu_share:  //分享二维码
        intent.setClassName(this, ShareActivity.class.getName());
        startActivity(intent);
        break;
      case R.id.menu_history:  //历史记录
        intent.setClassName(this, HistoryActivity.class.getName());
        startActivityForResult(intent, HISTORY_REQUEST_CODE);
        break;
      case R.id.menu_settings:  //设置
        intent.setClassName(this, PreferencesActivity.class.getName());
        startActivity(intent);
        break;
      case R.id.menu_help:  //帮助
        intent.setClassName(this, HelpActivity.class.getName());
        startActivity(intent);
        break;
      default:
        return super.onOptionsItemSelected(item);
    }
    return true;
  }
  /**---------------menu end----------------**/

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent intent) {
      Log.i(TAG, "requestCode:" + requestCode + ",resultCode:" + resultCode);
    if (resultCode == RESULT_OK && requestCode == HISTORY_REQUEST_CODE && historyManager != null) {
      int itemNumber = intent.getIntExtra(Intents.History.ITEM_NUMBER, -1);
      if (itemNumber >= 0) {
        HistoryItem historyItem = historyManager.buildHistoryItem(itemNumber);
        decodeOrStoreSavedBitmap(null, historyItem.getResult());
      }
    }
  }

  private void decodeOrStoreSavedBitmap(Bitmap bitmap, Result result) {
    // Bitmap isn't used yet -- will be used soon
    if (handler == null) {
      savedResultToShow = result;
    } else {
      if (result != null) {
        savedResultToShow = result;
      }
      if (savedResultToShow != null) {
        Message message = Message.obtain(handler, R.id.decode_succeeded, savedResultToShow);
        handler.sendMessage(message);
      }
      savedResultToShow = null;
    }
  }

  /**----------------SurfaceHolder.Callback begin--------------------------**/
  @Override
  public void surfaceCreated(SurfaceHolder holder) {
    if (holder == null) {
      Log.e(TAG, "*** WARNING *** surfaceCreated() gave us a null surface!");
    }
    Log.i(TAG, "surface:hasSruface:" + hasSurface);
    if (!hasSurface) {
      hasSurface = true;
      initCamera(holder);
    }
  }

  @Override
  public void surfaceDestroyed(SurfaceHolder holder) {
    Log.i(TAG, "surface:hasSruface set false");
    hasSurface = false;
  }

  @Override
  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    // do nothing
  }
  /**----------------SurfaceHolder.Callback end--------------------------**/


  /**
   * A valid barcode has been found, so give an indication of success and show the results.
   *
   * @param rawResult The contents of the barcode.
   * @param scaleFactor amount by which thumbnail was scaled
   * @param barcode   A greyscale bitmap of the camera data which was decoded.
   */
  /**---------------解码成功后，UI显示等处理------------------**/
  public void handleDecode(Result rawResult, Bitmap barcode, float scaleFactor) {
    inactivityTimer.onActivity();
    lastResult = rawResult;
    ResultHandler resultHandler = ResultHandlerFactory.makeResultHandler(this, rawResult);

    boolean fromLiveScan = barcode != null;
    if (fromLiveScan) {
      Log.i(TAG, "++++++++++" + Utils.getLineNumber(new Exception()));
      historyManager.addHistoryItem(rawResult, resultHandler);
      // Then not from history, so beep/vibrate and we have an image to draw on
      beepManager.playBeepSoundAndVibrate();
      drawResultPoints(barcode, scaleFactor, rawResult);
    }

    switch (source) {
      case NATIVE_APP_INTENT:
      case PRODUCT_SEARCH_LINK:
        handleDecodeExternally(rawResult, resultHandler, barcode);
        break;
      case ZXING_LINK:
        if (scanFromWebPageManager == null || !scanFromWebPageManager.isScanFromWebPage()) {
          handleDecodeInternally(rawResult, resultHandler, barcode);
        } else {
          handleDecodeExternally(rawResult, resultHandler, barcode);
        }
        break;
      case NONE:
        Log.i(TAG, "----------------1");
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (fromLiveScan && prefs.getBoolean(PreferencesActivity.KEY_BULK_MODE, false)) {
          Log.i(TAG, "----------------2");
          Toast.makeText(getApplicationContext(),
                         getResources().getString(R.string.msg_bulk_mode_scanned) + " (" + rawResult.getText() + ')',
                         Toast.LENGTH_SHORT).show();
          maybeSetClipboard(resultHandler);
          // Wait a moment or else it will scan the same barcode continuously about 3 times
          restartPreviewAfterDelay(BULK_MODE_SCAN_DELAY_MS);
        } else {
          Log.i(TAG, "----------------3");
          handleDecodeInternally(rawResult, resultHandler, barcode);  //UI显示识别结果
        }
        Log.i(TAG, "----------------4");
        break;
    }
  }

  /**
   * Superimpose a line for 1D or dots for 2D to highlight the key features of the barcode.
   *
   * @param barcode   A bitmap of the captured image.
   * @param scaleFactor amount by which thumbnail was scaled
   * @param rawResult The decoded results which contains the points to draw.
   */
  private void drawResultPoints(Bitmap barcode, float scaleFactor, Result rawResult) {
    ResultPoint[] points = rawResult.getResultPoints();
    Log.i(TAG, "--------------" + Utils.getLineNumber(new Exception()));
    if (points != null && points.length > 0) {
      Log.i(TAG, "--------------" + Utils.getLineNumber(new Exception()));
      Canvas canvas = new Canvas(barcode);
      Paint paint = new Paint();
      paint.setColor(getResources().getColor(R.color.result_points));
      if (points.length == 2) {
        Log.i(TAG, "--------------" + Utils.getLineNumber(new Exception()));
        paint.setStrokeWidth(4.0f);
        drawLine(canvas, paint, points[0], points[1], scaleFactor);
      } else if (points.length == 4 &&
                 (rawResult.getBarcodeFormat() == BarcodeFormat.UPC_A ||
                  rawResult.getBarcodeFormat() == BarcodeFormat.EAN_13)) {
        Log.i(TAG, "--------------" + Utils.getLineNumber(new Exception()));
        // Hacky special case -- draw two lines, for the barcode and metadata
        drawLine(canvas, paint, points[0], points[1], scaleFactor);
        drawLine(canvas, paint, points[2], points[3], scaleFactor);
      } else {
        Log.i(TAG, "--------------" + Utils.getLineNumber(new Exception()));
        paint.setStrokeWidth(10.0f);
        int count = 0, count1 = 0;
        for (ResultPoint point : points) {
          count++;
          if (point != null) {
            count1++;
            Log.i(TAG, "==");
            canvas.drawPoint(scaleFactor * point.getX(), scaleFactor * point.getY(), paint);
          }
        }
        Log.i(TAG, Utils.getLineNumber(new Exception()) + "--------------count:" + count + ",count1:" + count1);
      }
    }
  }

  private static void drawLine(Canvas canvas, Paint paint, ResultPoint a, ResultPoint b, float scaleFactor) {
    if (a != null && b != null) {
      canvas.drawLine(scaleFactor * a.getX(), 
                      scaleFactor * a.getY(), 
                      scaleFactor * b.getX(), 
                      scaleFactor * b.getY(), 
                      paint);
    }
  }

  /**---------------------解码成功后的UI显示-------------------------**/
  // Put up our own UI for how to handle the decoded contents.
  private void handleDecodeInternally(Result rawResult, ResultHandler resultHandler, Bitmap barcode) {

    maybeSetClipboard(resultHandler);

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

    if (resultHandler.getDefaultButtonID() != null && prefs.getBoolean(PreferencesActivity.KEY_AUTO_OPEN_WEB, false)) {
      resultHandler.handleButtonPress(resultHandler.getDefaultButtonID());
      return;
    }

    statusView.setVisibility(View.GONE);  //隐藏底部提示（“请将条码置于取景框内扫描。”）
    viewfinderView.setVisibility(View.GONE);  //隐藏取景框
    resultView.setVisibility(View.VISIBLE);  //显示扫描结果

    ImageView barcodeImageView = (ImageView) findViewById(R.id.barcode_image_view);
    if (barcode == null) {
      barcodeImageView.setImageBitmap(BitmapFactory.decodeResource(getResources(),
          R.drawable.launcher_icon));
//      barcodeImageView.setImageResource(R.drawable.launcher_icon);  //可以使用这种方式吗？
    } else {
      barcodeImageView.setImageBitmap(barcode);  //显示识别成功后的缩略图
    }

    TextView formatTextView = (TextView) findViewById(R.id.format_text_view);
    formatTextView.setText(rawResult.getBarcodeFormat().toString());  //格式

    TextView typeTextView = (TextView) findViewById(R.id.type_text_view);
    typeTextView.setText(resultHandler.getType().toString());  //类型

    DateFormat formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
    TextView timeTextView = (TextView) findViewById(R.id.time_text_view);
    timeTextView.setText(formatter.format(rawResult.getTimestamp()));  //时间


    TextView metaTextView = (TextView) findViewById(R.id.meta_text_view);
    View metaTextViewLabel = findViewById(R.id.meta_text_view_label);
    metaTextView.setVisibility(View.GONE);  //隐藏（若metadata为空）
    metaTextViewLabel.setVisibility(View.GONE);  //隐藏（若metadata为空）
    Map<ResultMetadataType,Object> metadata = rawResult.getResultMetadata();
    if (metadata != null) {
      StringBuilder metadataText = new StringBuilder(20);
      for (Map.Entry<ResultMetadataType,Object> entry : metadata.entrySet()) {
        Log.i(TAG, "entry:" + entry.toString());
        if (DISPLAYABLE_METADATA_TYPES.contains(entry.getKey())) {
          metadataText.append(entry.getValue()).append('\n');
          //以下为调试代码 qiub_200131
          if (ResultMetadataType.BYTE_SEGMENTS == entry.getKey()) {
            Log.i(TAG, "key:BYTE_SEGMENTS");
          } else if (ResultMetadataType.ERROR_CORRECTION_LEVEL == entry.getKey()) {
            Log.i(TAG, "key:ERROR_CORRECTION_LEVEL");
          } else if (ResultMetadataType.ISSUE_NUMBER == entry.getKey()) {
            Log.i(TAG, "key:ISSUE_NUMBER");
          } else if (ResultMetadataType.ORIENTATION == entry.getKey()) {
            Log.i(TAG, "key:ORIENTATION");
          } else if (ResultMetadataType.OTHER == entry.getKey()) {
            Log.i(TAG, "key:OTHER");
          } else if (ResultMetadataType.PDF417_EXTRA_METADATA == entry.getKey()) {
            Log.i(TAG, "key:PDF417_EXTRA_METADATA");
          } else if (ResultMetadataType.POSSIBLE_COUNTRY == entry.getKey()) {
            Log.i(TAG, "key:POSSIBLE_COUNTRY");
          } else if (ResultMetadataType.STRUCTURED_APPEND_PARITY == entry.getKey()) {
            Log.i(TAG, "key:STRUCTURED_APPEND_PARITY");
          } else if (ResultMetadataType.STRUCTURED_APPEND_SEQUENCE == entry.getKey()) {
            Log.i(TAG, "key:STRUCTURED_APPEND_SEQUENCE");
          } else if (ResultMetadataType.SUGGESTED_PRICE == entry.getKey()) {
            Log.i(TAG, "key:SUGGESTED_PRICE");
          } else if (ResultMetadataType.UPC_EAN_EXTENSION == entry.getKey()) {
            Log.i(TAG, "key:UPC_EAN_EXTENSION");
          }
        }
      }
      if (metadataText.length() > 0) {
        Log.i(TAG, "metadataText.length() > 0,length:" + metadataText.length());
        metadataText.setLength(metadataText.length() - 1);
        metaTextView.setText(metadataText);
        metaTextView.setVisibility(View.VISIBLE);  //显示
        metaTextViewLabel.setVisibility(View.VISIBLE);  //显示
      }
      Log.i(TAG, "metadataText length:" + metadataText.length());
    }

    CharSequence displayContents = resultHandler.getDisplayContents();
    TextView contentsTextView = (TextView) findViewById(R.id.contents_text_view);
    contentsTextView.setText(displayContents);  //显示二维码内容
    int scaledSize = Math.max(22, 32 - displayContents.length() / 4);
    Log.i(TAG, "displayContents:" + displayContents);
    Log.i(TAG, "scaledSize:" + scaledSize);
    contentsTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSize);  //设置字体大小

    TextView supplementTextView = (TextView) findViewById(R.id.contents_supplement_text_view);
    supplementTextView.setText("");  //显示空字符串
    supplementTextView.setOnClickListener(null);
    if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
        PreferencesActivity.KEY_SUPPLEMENTAL, true)) {
      //暂不清楚什么意思？ qiub_200131
      SupplementalInfoRetriever.maybeInvokeRetrieval(supplementTextView,
                                                     resultHandler.getResult(),
                                                     historyManager,
                                                     this);
    }

    /**----------UI底部的按钮显示----------------**/
    int buttonCount = resultHandler.getButtonCount();
    Log.i(TAG, "buttonCount:" + buttonCount);
    ViewGroup buttonView = (ViewGroup) findViewById(R.id.result_button_view);
    buttonView.requestFocus();
    for (int x = 0; x < ResultHandler.MAX_BUTTON_COUNT; x++) {
      TextView button = (TextView) buttonView.getChildAt(x);
      if (x < buttonCount) {
        button.setVisibility(View.VISIBLE);  //设置为可见
        button.setText(resultHandler.getButtonText(x));  //显示内容
        button.setOnClickListener(new ResultButtonListener(resultHandler, x));
      } else {
        button.setVisibility(View.GONE);  //设置为隐藏
      }
    }
  }

  // Briefly show the contents of the barcode, then handle the result outside Barcode Scanner.
  private void handleDecodeExternally(Result rawResult, ResultHandler resultHandler, Bitmap barcode) {

    if (barcode != null) {
      viewfinderView.drawResultBitmap(barcode);
    }

    long resultDurationMS;
    if (getIntent() == null) {
      resultDurationMS = DEFAULT_INTENT_RESULT_DURATION_MS;
    } else {
      resultDurationMS = getIntent().getLongExtra(Intents.Scan.RESULT_DISPLAY_DURATION_MS,
                                                  DEFAULT_INTENT_RESULT_DURATION_MS);
    }

    if (resultDurationMS > 0) {
      String rawResultString = String.valueOf(rawResult);
      if (rawResultString.length() > 32) {
        rawResultString = rawResultString.substring(0, 32) + " ...";
      }
      statusView.setText(getString(resultHandler.getDisplayTitle()) + " : " + rawResultString);
    }

    maybeSetClipboard(resultHandler);

    switch (source) {
      case NATIVE_APP_INTENT:
        // Hand back whatever action they requested - this can be changed to Intents.Scan.ACTION when
        // the deprecated intent is retired.
        Intent intent = new Intent(getIntent().getAction());
        intent.addFlags(Intents.FLAG_NEW_DOC);
        intent.putExtra(Intents.Scan.RESULT, rawResult.toString());
        intent.putExtra(Intents.Scan.RESULT_FORMAT, rawResult.getBarcodeFormat().toString());
        byte[] rawBytes = rawResult.getRawBytes();
        if (rawBytes != null && rawBytes.length > 0) {
          intent.putExtra(Intents.Scan.RESULT_BYTES, rawBytes);
        }
        Map<ResultMetadataType, ?> metadata = rawResult.getResultMetadata();
        if (metadata != null) {
          if (metadata.containsKey(ResultMetadataType.UPC_EAN_EXTENSION)) {
            intent.putExtra(Intents.Scan.RESULT_UPC_EAN_EXTENSION,
                metadata.get(ResultMetadataType.UPC_EAN_EXTENSION).toString());
          }
          Number orientation = (Number) metadata.get(ResultMetadataType.ORIENTATION);
          if (orientation != null) {
            intent.putExtra(Intents.Scan.RESULT_ORIENTATION, orientation.intValue());
          }
          String ecLevel = (String) metadata.get(ResultMetadataType.ERROR_CORRECTION_LEVEL);
          if (ecLevel != null) {
            intent.putExtra(Intents.Scan.RESULT_ERROR_CORRECTION_LEVEL, ecLevel);
          }
          @SuppressWarnings("unchecked")
          Iterable<byte[]> byteSegments = (Iterable<byte[]>) metadata.get(ResultMetadataType.BYTE_SEGMENTS);
          if (byteSegments != null) {
            int i = 0;
            for (byte[] byteSegment : byteSegments) {
              intent.putExtra(Intents.Scan.RESULT_BYTE_SEGMENTS_PREFIX + i, byteSegment);
              i++;
            }
          }
        }
        sendReplyMessage(R.id.return_scan_result, intent, resultDurationMS);
        break;

      case PRODUCT_SEARCH_LINK:
        // Reformulate the URL which triggered us into a query, so that the request goes to the same
        // TLD as the scan URL.
        int end = sourceUrl.lastIndexOf("/scan");
        String productReplyURL = sourceUrl.substring(0, end) + "?q=" + 
            resultHandler.getDisplayContents() + "&source=zxing";
        sendReplyMessage(R.id.launch_product_query, productReplyURL, resultDurationMS);
        break;
        
      case ZXING_LINK:
        if (scanFromWebPageManager != null && scanFromWebPageManager.isScanFromWebPage()) {
          String linkReplyURL = scanFromWebPageManager.buildReplyURL(rawResult, resultHandler);
          scanFromWebPageManager = null;
          sendReplyMessage(R.id.launch_product_query, linkReplyURL, resultDurationMS);
        }
        break;
    }
  }

  private void maybeSetClipboard(ResultHandler resultHandler) {
    if (copyToClipboard && !resultHandler.areContentsSecure()) {
      ClipboardInterface.setText(resultHandler.getDisplayContents(), this);
    }
  }
  
  private void sendReplyMessage(int id, Object arg, long delayMS) {
    if (handler != null) {
      Message message = Message.obtain(handler, id, arg);
      if (delayMS > 0L) {
        handler.sendMessageDelayed(message, delayMS);
      } else {
        handler.sendMessage(message);
      }
    }
  }

  private void initCamera(SurfaceHolder surfaceHolder) {
    if (surfaceHolder == null) {
      throw new IllegalStateException("No SurfaceHolder provided");
    }
    if (cameraManager.isOpen()) {
      Log.w(TAG, "initCamera() while already open -- late SurfaceView callback?");
      return;
    }
    try {
      cameraManager.openDriver(surfaceHolder);  //Opens the camera driver and initializes the hardware parameters.
      // Creating the handler starts the preview, which can also throw a RuntimeException.
      if (handler == null) {
        handler = new CaptureActivityHandler(this, decodeFormats, decodeHints, characterSet, cameraManager);
      }
      decodeOrStoreSavedBitmap(null, null);
    } catch (IOException ioe) {
      Log.w(TAG, ioe);
      displayFrameworkBugMessageAndExit();
    } catch (RuntimeException e) {
      // Barcode Scanner has seen crashes in the wild of this variety:
      // java.?lang.?RuntimeException: Fail to connect to camera service
      Log.w(TAG, "Unexpected error initializing camera", e);
      displayFrameworkBugMessageAndExit();
    }
  }

  private void displayFrameworkBugMessageAndExit() {
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(getString(R.string.app_name));
    builder.setMessage(getString(R.string.msg_camera_framework_bug));
    builder.setPositiveButton(R.string.button_ok, new FinishListener(this));
    builder.setOnCancelListener(new FinishListener(this));
    builder.show();
  }

  public void restartPreviewAfterDelay(long delayMS) {
    if (handler != null) {
      handler.sendEmptyMessageDelayed(R.id.restart_preview, delayMS);
    }
    resetStatusView();
  }

  private void resetStatusView() {
    resultView.setVisibility(View.GONE);
    statusView.setText(R.string.msg_default_status);
    statusView.setVisibility(View.VISIBLE);
    viewfinderView.setVisibility(View.VISIBLE);
    lastResult = null;
  }

  public void drawViewfinder() {
    viewfinderView.drawViewfinder();
  }
}

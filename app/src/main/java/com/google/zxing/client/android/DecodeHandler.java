/*
 * Copyright (C) 2010 ZXing authors
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

import android.graphics.Bitmap;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class DecodeHandler extends Handler {

  private static final String TAG = DecodeHandler.class.getSimpleName();

  private final CaptureActivity activity;
  private final MultiFormatReader multiFormatReader;
  private final QRCodeReader qrCodeReader;
  private boolean running = true;

  DecodeHandler(CaptureActivity activity, Map<DecodeHintType,Object> hints) {
    multiFormatReader = new MultiFormatReader();
    multiFormatReader.setHints(hints);

    qrCodeReader = new QRCodeReader();

    this.activity = activity;
  }

  @Override
  public void handleMessage(Message message) {
    if (message == null || !running) {
      return;
    }
    switch (message.what) {
      case R.id.decode:
        Log.i(TAG, "handlerMessage R.id.decode");
        long startTime = System.nanoTime();
        decode((byte[]) message.obj, message.arg1, message.arg2);  //图片解码 qiub_200128
        long consumingTime = System.nanoTime() - startTime;
        Log.i(TAG, "----decode:" + ((float)consumingTime / 1000000) + "ms!");
        break;
      case R.id.quit:
        running = false;
        Looper.myLooper().quit();
        break;
    }
  }

  /**
   * Decode the data within the viewfinder rectangle, and time how long it took. For efficiency,
   * reuse the same reader objects from one decode to the next.
   *
   * @param data   The YUV preview frame.
   * @param width  The width of the preview frame.
   * @param height The height of the preview frame.
   */
  private void decode(byte[] data, int width, int height) {
    Log.i(TAG, "width:" + width + ",heigth:" + height + ",size:" + data.length);

    long start = System.nanoTime();
    Result rawResult = null;
    PlanarYUVLuminanceSource source = activity.getCameraManager().buildLuminanceSource(data, width, height);
    if (source != null) {
      BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
      try {
        /**multiFormatReader.setHints();  //此方法将状态添加到MultiFormatReader，通过一次设置提示，后续调用
          multiFormatReader.decodeWithState可以重用同一组读取器，而无需重新分配内存。这个对于连续扫描客户端的性能很重要。
          在Redmi 6 Pro上（MIUI 11.0.4 android 9.0）运行，耗时52-152ms(152ms是第一次识别，需要分配内存)
         **/
        rawResult = multiFormatReader.decodeWithState(bitmap);  //调用core-3.4.0.jar中的MultiFormatReader.decodeWithState()解码
        //在Redmi 6 Pro上（MIUI 11.0.4 android 9.0）运行，耗时37-136ms(136ms是第一次识别，需要分配内存)
//        rawResult = qrCodeReader.decode(bitmap);    //直接用QRCodeReader解码
      } catch (ReaderException re) {
        // continue
      } finally {
        multiFormatReader.reset();
      }
    }

    Handler handler = activity.getHandler();
    if (rawResult != null) {
      // Don't log the barcode contents for security.
      long end = System.nanoTime();
      Log.d(TAG, "Found barcode in " + TimeUnit.NANOSECONDS.toMillis(end - start) + " ms");
      if (handler != null) {
        //创建一个message
        //h:handler
        //what:R.id.decode_succeeded
        //obj:rawResult
        Message message = Message.obtain(handler, R.id.decode_succeeded, rawResult);
        Bundle bundle = new Bundle();
        bundleThumbnail(source, bundle);        
        message.setData(bundle);
        message.sendToTarget();  //发送消息
      }
    } else {
      if (handler != null) {
        //创建一个message
        //h:handler
        //what:R.id.decode_failed
        Message message = Message.obtain(handler, R.id.decode_failed);
        message.sendToTarget();  //发送消息
      }
    }
  }

  private static void bundleThumbnail(PlanarYUVLuminanceSource source, Bundle bundle) {
    int[] pixels = source.renderThumbnail();
    int width = source.getThumbnailWidth();
    int height = source.getThumbnailHeight();
    Bitmap bitmap = Bitmap.createBitmap(pixels, 0, width, width, height, Bitmap.Config.ARGB_8888);
    ByteArrayOutputStream out = new ByteArrayOutputStream();    
    bitmap.compress(Bitmap.CompressFormat.JPEG, 50, out);
    bundle.putByteArray(DecodeThread.BARCODE_BITMAP, out.toByteArray());
    bundle.putFloat(DecodeThread.BARCODE_SCALED_FACTOR, (float) width / source.getWidth());
  }

}

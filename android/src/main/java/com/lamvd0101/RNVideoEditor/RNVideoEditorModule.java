//
//  RNVideoEditorModule.java
//  ReactionSocial
//
//  Created by Vuong Duc Lam on 9/3/19.
//

package com.lamvd0101.RNVideoEditor;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

import Jni.FFmpegCmd;
import Jni.VideoUitls;
import VideoHandle.EpEditor;
import VideoHandle.EpVideo;
import VideoHandle.OnEditorListener;

public class RNVideoEditorModule extends ReactContextBaseJavaModule {
  private final ReactApplicationContext reactContext;
  private final int VIDEO_FPS = 30;
  private final int VIDEO_BITRATE = 4; // 4MB

  public RNVideoEditorModule(ReactApplicationContext reactContext) {
    super(reactContext);
    this.reactContext = reactContext;
  }

  @Override
  public String getName() {
    return "RNVideoEditor";
  }

  private void cmdExec(StringBuffer strBuffer, long duration, final File tempFile, final Promise promise) {
    try {
      String cmd = strBuffer.toString() + " " + tempFile.getPath();
      Log.d("cmdExec", cmd);
      EpEditor.execCmd(cmd, duration, new OnEditorListener() {
        @Override
        public void onSuccess() {
          promise.resolve("file://" + tempFile.getPath());
        }

        @Override
        public void onFailure() {
          promise.reject(new Exception("Execution failed."));
        }

        @Override
        public void onProgress(float progress) {
        }
      });
    } catch (Exception e) {
      promise.reject(e);
    }
  }

  @ReactMethod
  public void getLocalURL(String source, Promise promise) {
    try {
      promise.resolve(source);
    } catch (Exception e) {
      promise.reject(null, e);
    }
  }

  @ReactMethod
  public void getVideoInfo(String source, Promise promise) {
    try {
      WritableMap data = Arguments.createMap();

      double duration = (double) (VideoUitls.getDuration(source) / 1000000);
      data.putDouble("duration", duration);

      promise.resolve(data);
    } catch (Exception e) {
      promise.reject(null, e);
    }
  }

  @ReactMethod
  public void getPictureAtPosition(String source, ReadableMap options, Promise promise) {
    MediaMetadataRetriever retriever = null;
    try {
      String format = options.hasKey("format") ? options.getString("format") : "base64";
      double second = options.hasKey("second") ? options.getDouble("second") : 0;

      double duration = (double) (VideoUitls.getDuration(source) / 1000000);
      if (second > duration || second < 0) {
        second = 0;
      }

      retriever = new MediaMetadataRetriever();
      if (RNVideoEditorUtilities.shouldUseURI(source)) {
        Uri uri = Uri.parse(source);
        retriever.setDataSource(uri.getPath());
      } else {
        retriever.setDataSource(source);
      }
      Bitmap bm = retriever.getFrameAtTime((long) (second * 1000));
      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

      if (format != null && format.equals("jpg")) {
        final File tempFile = RNVideoEditorUtilities.createTempFile("jpg", reactContext);
        bm.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();

        FileOutputStream fos = new FileOutputStream( tempFile.getPath() );
        fos.write( byteArray );
        fos.close();

        promise.resolve("file://" + tempFile.getPath());
      } else {
        bm.compress(Bitmap.CompressFormat.PNG, 50, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();

        String base64String = Base64.encodeToString(byteArray, Base64.DEFAULT);
        promise.resolve("data:image/png;base64," + base64String);
      }
    } catch (Exception e) {
      promise.reject(null, e);
    } finally {
      if (retriever != null) {
        retriever.release();
      }
    }
  }

  @ReactMethod
  public void getPictures(String source, Promise promise) {
    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
    try {
      double numberOfPictures = 8;
      double duration = (double) (VideoUitls.getDuration(source) / 1000000);
      if (duration > 30) {
        numberOfPictures = 4 * (Math.floor((duration / 30) + 1));
      }
      double second = Math.floor(duration / numberOfPictures);

      retriever = new MediaMetadataRetriever();
      if (RNVideoEditorUtilities.shouldUseURI(source)) {
        Uri uri = Uri.parse(source);
        retriever.setDataSource(uri.getPath());
      } else {
        retriever.setDataSource(source);
      }

      WritableArray pictures = Arguments.createArray();
      for (int i = 0; i < numberOfPictures; i++) {
        Bitmap bm = retriever.getFrameAtTime((long) (i * second * 1000));

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 50, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();

        String base64String = Base64.encodeToString(byteArray, Base64.DEFAULT);
        String picture = "data:image/png;base64," + base64String;
        pictures.pushString(picture);
      }

      promise.resolve(pictures);
    } catch (Exception e) {
      promise.reject(null, e);
    } finally {
      retriever.release();
    }
  }

  @ReactMethod
  public void merge(ReadableArray videoFiles, final Promise promise) {
    try {
      final File tempFile = RNVideoEditorUtilities.createTempFile("mp4", reactContext);

      int size = videoFiles.size();
      ArrayList<EpVideo> epVideos = new ArrayList<>();
      for (int i = 0; i < videoFiles.size(); i++) {
        epVideos.add(new EpVideo(videoFiles.getString(i)));
      }

      EpEditor.OutputOption outputOption = new EpEditor.OutputOption(tempFile.getPath());
      outputOption.outFormat = "mp4";

      VideoSize videoSize;
      if (size > 0) {
        videoSize = RNVideoEditorUtilities.determineOutputVideoSize(videoFiles.getString(0));
      } else {
        videoSize = RNVideoEditorUtilities.determineOutputVideoSize(null);
      }
      outputOption.setWidth(videoSize.width);
      outputOption.setHeight(videoSize.height);

      outputOption.frameRate = VIDEO_FPS;
      outputOption.bitRate = VIDEO_BITRATE;

      if (size > 1) {
        EpEditor.merge(epVideos, outputOption, new OnEditorListener() {
          @Override
          public void onSuccess() {
            promise.resolve("file://" + tempFile.getPath());
          }

          @Override
          public void onFailure() {
            promise.reject(new Exception("Merge failed."));
          }

          @Override
          public void onProgress(float progress) {
          }
        });
        return;
      }

      EpEditor.exec(epVideos.get(0), outputOption, new OnEditorListener() {
        @Override
        public void onSuccess() {
          promise.resolve("file://" + tempFile.getPath());
        }

        @Override
        public void onFailure() {
          promise.reject(new Exception("Merge failed."));
        }

        @Override
        public void onProgress(float progress) {
        }
      });
    } catch (Exception e) {
      promise.reject(null, e);
    }
  }

  @ReactMethod
  public void mergeWithAudio(String source, String audioSource, final Promise promise) {
    try {
      final File tempFile = RNVideoEditorUtilities.createTempFile("mp4", reactContext);
      EpEditor.music(source, audioSource, tempFile.getPath(), 1, 1, new OnEditorListener() {
        @Override
        public void onSuccess() {
          promise.resolve("file://" + tempFile.getPath());
        }

        @Override
        public void onFailure() {
          promise.reject(new Exception("Music failed."));
        }

        @Override
        public void onProgress(float progress) {
        }
      });
    } catch (Exception e) {
      promise.reject(null, e);
    }
  }

  @ReactMethod
  public void trim(String source, ReadableMap options, final Promise promise) {
    try {
      double startTime = options.hasKey("startTime") ? options.getDouble("startTime") : 0;
      double endTime = options.hasKey("endTime") ? options.getDouble("endTime") : 0;

      final File tempFile = RNVideoEditorUtilities.createTempFile("mp4", reactContext);

      EpVideo epVideo = new EpVideo(source);
      epVideo.clip((float) startTime, (float) endTime);

      EpEditor.OutputOption outputOption = new EpEditor.OutputOption(tempFile.getPath());
      outputOption.outFormat = "mp4";

      VideoSize videoSize = RNVideoEditorUtilities.determineOutputVideoSize(source);
      outputOption.setWidth(videoSize.width);
      outputOption.setHeight(videoSize.height);

      outputOption.frameRate = VIDEO_FPS;
      outputOption.bitRate = VIDEO_BITRATE;

      EpEditor.exec(epVideo, outputOption, new OnEditorListener() {
        @Override
        public void onSuccess() {
          promise.resolve("file://" + tempFile.getPath());
        }

        @Override
        public void onFailure() {
          promise.reject(new Exception("Trim failed."));
        }

        @Override
        public void onProgress(float progress) {
        }
      });
    } catch (Exception e) {
      promise.reject(null, e);
    }
  }

  @ReactMethod
  public void cleanFiles(Callback callback) {
    try {
      RNVideoEditorUtilities.cleanFiles(reactContext);
      callback.invoke();
    } catch (Exception e) {
    }
  }

  @ReactMethod
  public void cancel(Callback callback) {
    try {
      FFmpegCmd.exit();
      callback.invoke();
    } catch (Exception e) {
    }
  }
}
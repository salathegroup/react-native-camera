package org.reactnative.camera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.CamcorderProfile;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.ThemedReactContext;
import com.google.android.cameraview.AspectRatio;
import com.google.android.cameraview.CameraView;
import com.google.android.gms.vision.face.Face;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;

import org.reactnative.camera.tasks.BarCodeScannerAsyncTask;
import org.reactnative.camera.tasks.BarCodeScannerAsyncTaskDelegate;
import org.reactnative.camera.tasks.FaceDetectorAsyncTask;
import org.reactnative.camera.tasks.FaceDetectorAsyncTaskDelegate;
import org.reactnative.camera.tasks.ResolveTakenPictureAsyncTask;
import org.reactnative.camera.utils.BitmapRotate;
import org.reactnative.camera.utils.ImageDimensions;
import org.reactnative.camera.utils.RNFileUtils;
import org.reactnative.camera.utils.YuvToBitmap;
import org.reactnative.facedetector.RNFaceDetector;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class RNCameraView extends CameraView implements LifecycleEventListener, BarCodeScannerAsyncTaskDelegate, FaceDetectorAsyncTaskDelegate {
  private ThemedReactContext mThemedReactContext;
  private Queue<Promise> mPictureTakenPromises = new ConcurrentLinkedQueue<>();
  private Map<Promise, ReadableMap> mPictureTakenOptions = new ConcurrentHashMap<>();
  private Map<Promise, File> mPictureTakenDirectories = new ConcurrentHashMap<>();
  private Promise mVideoRecordedPromise;
  private List<String> mBarCodeTypes = null;
  private YuvToBitmap mYuvToBitmap = null;
  private BitmapRotate mBitmapRotate = null;

  private boolean mIsPaused = false;
  private boolean mIsNew = true;
  private AspectRatio mAspectRatio;
  private boolean mAutoAspectRatio = false;

  // Concurrency lock for scanners to avoid flooding the runtime
  public volatile boolean barCodeScannerTaskLock = false;
  public volatile boolean faceDetectorTaskLock = false;

  // Scanning-related properties
  private final MultiFormatReader mMultiFormatReader = new MultiFormatReader();
  private final RNFaceDetector mFaceDetector;
  private boolean mShouldDetectFaces = false;
  private boolean mShouldScanBarCodes = false;
  private int mFaceDetectorMode = RNFaceDetector.FAST_MODE;
  private int mFaceDetectionLandmarks = RNFaceDetector.NO_LANDMARKS;
  private int mFaceDetectionClassifications = RNFaceDetector.NO_CLASSIFICATIONS;

  public RNCameraView(ThemedReactContext themedReactContext) {
    super(themedReactContext, true);
    initBarcodeReader();
    mThemedReactContext = themedReactContext;
    mFaceDetector = new RNFaceDetector(themedReactContext);
    setupFaceDetector();
    themedReactContext.addLifecycleEventListener(this);

    addCallback(new Callback() {
      @Override
      public void onCameraOpened(CameraView cameraView) {
        RNCameraViewHelper.emitCameraReadyEvent(cameraView);

        relayoutPreview(getLeft(), getTop(), getRight(), getBottom());
      }

      @Override
      public void onMountError(CameraView cameraView) {
        RNCameraViewHelper.emitMountErrorEvent(cameraView);
      }

      @Override
      public void onPictureTaken(CameraView cameraView, final byte[] data) {
        Promise promise = mPictureTakenPromises.poll();
        ReadableMap options = mPictureTakenOptions.remove(promise);
        final File cacheDirectory = mPictureTakenDirectories.remove(promise);
        new ResolveTakenPictureAsyncTask(data, promise, options, cacheDirectory).execute();
      }

      @Override
      public void onVideoRecorded(CameraView cameraView, String path) {
        if (mVideoRecordedPromise != null) {
          if (path != null) {
            WritableMap result = Arguments.createMap();
            result.putString("uri", RNFileUtils.uriFromFile(new File(path)).toString());
            mVideoRecordedPromise.resolve(result);
          } else {
            mVideoRecordedPromise.reject("E_RECORDING", "Couldn't stop recording - there is none in progress");
          }
          mVideoRecordedPromise = null;
        }
      }

      private void yuvToBitmapNeeded() {
        if (mYuvToBitmap == null) {
          mYuvToBitmap = new YuvToBitmap(getContext());
        }
      }

      private void bitmapRotateNeeded() {
        if (mBitmapRotate == null) {
          mBitmapRotate = new BitmapRotate(getContext());
        }
      }

      @Override
      public void onFramePreview(CameraView cameraView, final byte[] data, final int width, final int height, final int rotation) {
        if (useTakePicture()) return;

        final int correctRotation = RNCameraViewHelper.getCorrectCameraRotation(rotation, getFacing());

        if (mShouldScanBarCodes && !barCodeScannerTaskLock && cameraView instanceof BarCodeScannerAsyncTaskDelegate) {
          barCodeScannerTaskLock = true;
          BarCodeScannerAsyncTaskDelegate delegate = (BarCodeScannerAsyncTaskDelegate) cameraView;
          new BarCodeScannerAsyncTask(delegate, mMultiFormatReader, data, width, height).execute();
        }

        if (mShouldDetectFaces && !faceDetectorTaskLock && cameraView instanceof FaceDetectorAsyncTaskDelegate) {
          faceDetectorTaskLock = true;
          FaceDetectorAsyncTaskDelegate delegate = (FaceDetectorAsyncTaskDelegate) cameraView;
          new FaceDetectorAsyncTask(delegate, mFaceDetector, data, width, height, correctRotation).execute();
        }

        final Promise promise = mPictureTakenPromises.poll();

        if (promise != null) {
          yuvToBitmapNeeded();

          Thread thread = new Thread() {
            @Override
            public void run() {
              Log.d("PROFILE", "***************");
              long start = System.nanoTime();
              // Get RGB
              Bitmap bitmap = mYuvToBitmap.refreshBitmap(data, width, height);
              long elapsedTime = System.nanoTime() - start;
              Log.d("PROFILE", "Get RGB: " + elapsedTime / 1E6);

              Bitmap rotated;
              if (correctRotation == 0) {
                rotated = bitmap;
              } else {
                // Rotate
                if (true) {
                  Log.d("PROFILE", "Rotation: " + rotation + " (correctRotation: " + correctRotation + ")");
                  bitmapRotateNeeded();
                  if (false) {
                    rotated = mBitmapRotate.refreshBitmap(bitmap, correctRotation);
                  } else {
                    rotated = mBitmapRotate.refreshBitmap(mYuvToBitmap.getOut(), correctRotation);
                  }
                } else {
                  Matrix matrix = new Matrix();
                  matrix.postRotate(correctRotation);
                  rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, false);
                }
              }

              elapsedTime = System.nanoTime() - start;
              Log.d("PROFILE", "Rotate: " + elapsedTime / 1E6);

                // Resolve
              ReadableMap options = mPictureTakenOptions.remove(promise);
              final File cacheDirectory = mPictureTakenDirectories.remove(promise);
              new ResolveTakenPictureAsyncTask(rotated, promise, options, cacheDirectory).execute();
            }
          };
          thread.start();
        }
      }
    });
  }

  @Override
  public void setAspectRatio(@NonNull AspectRatio ratio) {
    mAspectRatio = ratio;
    if (!mAutoAspectRatio) {
      super.setAspectRatio(ratio);
      this.requestLayout();
    }
  }

  public void setAutoRatio(boolean autoAspectRatio) {
    if (autoAspectRatio != mAutoAspectRatio) {
      mAutoAspectRatio = autoAspectRatio;
      if (!autoAspectRatio && mAspectRatio != null) {
        super.setAspectRatio(mAspectRatio);
      }
      requestLayout();
    }
  }

  private AspectRatio getOptimalAspectRatio(int w, int h) {
    Set<AspectRatio> sizes = getSupportedAspectRatios();
    if (sizes == null) return null;

    AspectRatio optimalSize = null;
    double ratio = (double)h / w;
    double minDiff = Double.MAX_VALUE;
    double newDiff;
    for (AspectRatio size : sizes) {
      newDiff = Math.abs((double)size.getY()/ size.getX() - ratio);
      if (newDiff < minDiff) {
        optimalSize = size;
        minDiff = newDiff;
      }
    }
    return optimalSize;
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    relayoutPreview(left, top, right, bottom);
  }

  private void relayoutPreview(int left, int top, int right, int bottom) {
    View preview = getView();
    if (null == preview) {
      return;
    }
    this.setBackgroundColor(Color.BLACK);
    int width = right - left;
    int height = bottom - top;
    float ratio = (float)height / (float)width;
    float targetRatio;
    if (isCameraOpened()) {
      AspectRatio aspectRatio = RNCameraViewHelper.getCurrentAspectRatio(this);
      targetRatio = (float)aspectRatio.getX() / (float)aspectRatio.getY();
    } else {
      targetRatio = (float)preview.getMeasuredHeight() / (float)preview.getMeasuredWidth();
    }
    if (targetRatio > ratio) {
      float diff = (float)width * targetRatio - (float)height;
      preview.layout(0, -(int)(diff / 2), width, height + (int)(diff / 2));
    } else if (targetRatio < ratio) {
      float diff = (float)height / targetRatio - (float)width;
      preview.layout(-(int)(diff / 2),0, width + (int)(diff / 2), height);
    } else {
      preview.layout(0, 0, width, height);
    }
    if (mAutoAspectRatio) {
      AspectRatio aspectRatio = getOptimalAspectRatio(preview.getWidth(), preview.getHeight());
      boolean wasNull = super.getAspectRatio() == null;
      boolean wouldBeNull = aspectRatio == null;
      if (wasNull != wouldBeNull || !aspectRatio.equals(super.getAspectRatio())) {
        super.setAspectRatio(aspectRatio);
        stop();
        start();
        requestLayout();
      }
    }
  }

  @Override
  public void requestLayout() {
    // React handles this for us, so we don't need to call super.requestLayout();
  }

  @Override
  public void onViewAdded(View child) {
    if (this.getView() == child || this.getView() == null) return;
    // remove and readd view to make sure it is in the back.
    // @TODO figure out why there was a z order issue in the first place and fix accordingly.
    this.removeView(this.getView());
    this.addView(this.getView(), 0);
  }

  public void setBarCodeTypes(List<String> barCodeTypes) {
    mBarCodeTypes = barCodeTypes;
    initBarcodeReader();
  }

  public Object getImpl() {
    try {
      Field field = CameraView.class.getDeclaredField("mImpl");
      field.setAccessible(true);
      Object value = field.get(this);
      field.setAccessible(false);
      return value;

    } catch (NoSuchFieldException e) {
      throw new RuntimeException(e);

    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }

  }

  private boolean usingCamera2() {
    boolean res = false;
    Object impl = getImpl();
    if (impl != null) {
      String className = impl.getClass().getName();
      if (className.contains("cameraview.Camera2")) {
        res = true;
      }
    }
    return res;
  }

  private boolean useTakePicture() {
    return usingCamera2();
  }

  public void takePicture(ReadableMap options, final Promise promise, File cacheDirectory) {
    mPictureTakenPromises.add(promise);
    mPictureTakenOptions.put(promise, options);
    mPictureTakenDirectories.put(promise, cacheDirectory);
    if (useTakePicture()) {
      super.takePicture();
    }
  }

  public void record(ReadableMap options, final Promise promise, File cacheDirectory) {
    try {
      String path = RNFileUtils.getOutputFilePath(cacheDirectory, ".mp4");
      int maxDuration = options.hasKey("maxDuration") ? options.getInt("maxDuration") : -1;
      int maxFileSize = options.hasKey("maxFileSize") ? options.getInt("maxFileSize") : -1;

      CamcorderProfile profile = CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH);
      if (options.hasKey("quality")) {
        profile = RNCameraViewHelper.getCamcorderProfile(options.getInt("quality"));
      }

      boolean recordAudio = !options.hasKey("mute");

      if (super.record(path, maxDuration * 1000, maxFileSize, recordAudio, profile)) {
        mVideoRecordedPromise = promise;
      } else {
        promise.reject("E_RECORDING_FAILED", "Starting video recording failed. Another recording might be in progress.");
      }
    } catch (IOException e) {
      promise.reject("E_RECORDING_FAILED", "Starting video recording failed - could not create video file.");
    }
  }

  /**
   * Initialize the barcode decoder.
   * Supports all iOS codes except [code138, code39mod43, itf14]
   * Additionally supports [codabar, code128, maxicode, rss14, rssexpanded, upc_a, upc_ean]
   */
  private void initBarcodeReader() {
    EnumMap<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
    EnumSet<BarcodeFormat> decodeFormats = EnumSet.noneOf(BarcodeFormat.class);

    if (mBarCodeTypes != null) {
      for (String code : mBarCodeTypes) {
        String formatString = (String) CameraModule.VALID_BARCODE_TYPES.get(code);
        if (formatString != null) {
          decodeFormats.add(BarcodeFormat.valueOf(code));
        }
      }
    }

    hints.put(DecodeHintType.POSSIBLE_FORMATS, decodeFormats);
    mMultiFormatReader.setHints(hints);
  }

  public void setShouldScanBarCodes(boolean shouldScanBarCodes) {
    this.mShouldScanBarCodes = shouldScanBarCodes;
    setScanning(mShouldDetectFaces || mShouldScanBarCodes);
  }

  public void onBarCodeRead(Result barCode) {
    String barCodeType = barCode.getBarcodeFormat().toString();
    if (!mShouldScanBarCodes || !mBarCodeTypes.contains(barCodeType)) {
      return;
    }

    RNCameraViewHelper.emitBarCodeReadEvent(this, barCode);
  }

  public void onBarCodeScanningTaskCompleted() {
    barCodeScannerTaskLock = false;
    mMultiFormatReader.reset();
  }

  /**
   * Initial setup of the face detector
   */
  private void setupFaceDetector() {
    mFaceDetector.setMode(mFaceDetectorMode);
    mFaceDetector.setLandmarkType(mFaceDetectionLandmarks);
    mFaceDetector.setClassificationType(mFaceDetectionClassifications);
    mFaceDetector.setTracking(true);
  }

  public void setFaceDetectionLandmarks(int landmarks) {
    mFaceDetectionLandmarks = landmarks;
    if (mFaceDetector != null) {
      mFaceDetector.setLandmarkType(landmarks);
    }
  }

  public void setFaceDetectionClassifications(int classifications) {
    mFaceDetectionClassifications = classifications;
    if (mFaceDetector != null) {
      mFaceDetector.setClassificationType(classifications);
    }
  }

  public void setFaceDetectionMode(int mode) {
    mFaceDetectorMode = mode;
    if (mFaceDetector != null) {
      mFaceDetector.setMode(mode);
    }
  }

  public void setShouldDetectFaces(boolean shouldDetectFaces) {
    this.mShouldDetectFaces = shouldDetectFaces;
    setScanning(mShouldDetectFaces || mShouldScanBarCodes);
  }

  public void onFacesDetected(SparseArray<Face> facesReported, int sourceWidth, int sourceHeight, int sourceRotation) {
    if (!mShouldDetectFaces) {
      return;
    }

    SparseArray<Face> facesDetected = facesReported == null ? new SparseArray<Face>() : facesReported;

    ImageDimensions dimensions = new ImageDimensions(sourceWidth, sourceHeight, sourceRotation, getFacing());
    RNCameraViewHelper.emitFacesDetectedEvent(this, facesDetected, dimensions);
  }

  public void onFaceDetectionError(RNFaceDetector faceDetector) {
    if (!mShouldDetectFaces) {
      return;
    }

    RNCameraViewHelper.emitFaceDetectionErrorEvent(this, faceDetector);
  }

  @Override
  public void onFaceDetectingTaskCompleted() {
    faceDetectorTaskLock = false;
  }

  @Override
  public void onHostResume() {
    if (hasCameraPermissions()) {
      if ((mIsPaused && !isCameraOpened()) || mIsNew) {
        mIsPaused = false;
        mIsNew = false;
        if (!Build.FINGERPRINT.contains("generic")) {
          start();
          if (!useTakePicture()) {
            setScanning(true);
          }
        }
      }
    } else {
      WritableMap error = Arguments.createMap();
      error.putString("message", "Camera permissions not granted - component could not be rendered.");
      RNCameraViewHelper.emitMountErrorEvent(this);
    }
  }

  @Override
  public void onHostPause() {
    if (!mIsPaused && isCameraOpened()) {
      mIsPaused = true;
      stop();
    }
  }

  @Override
  public void onHostDestroy() {
    mFaceDetector.release();
    stop();
  }

  private boolean hasCameraPermissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      int result = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA);
      return result == PackageManager.PERMISSION_GRANTED;
    } else {
      return true;
    }
  }
}

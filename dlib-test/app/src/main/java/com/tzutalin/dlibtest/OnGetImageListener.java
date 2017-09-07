/*
 * Copyright 2016-present Tzutalin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tzutalin.dlibtest;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Handler;
import android.os.Trace;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import com.tzutalin.dlib.Constants;
import com.tzutalin.dlib.FaceDet;
import com.tzutalin.dlib.VisionDetRet;

import junit.framework.Assert;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Class that takes in preview frames and converts the image to Bitmaps to process with dlib lib.
 */
public class OnGetImageListener implements OnImageAvailableListener {
    private static final boolean SAVE_PREVIEW_BITMAP = false;

    private static final int INPUT_SIZE = 224;
    private static final String TAG = "OnGetImageListener";

    private int mScreenRotation = 90;

    private int mPreviewWdith = 0;
    private int mPreviewHeight = 0;
    private byte[][] mYUVBytes;
    private int[] mRGBBytes = null;
    private Bitmap mRGBframeBitmap = null;
    private Bitmap mCroppedBitmap = null;
    private Bitmap mRotatedBitmap = null;

    private boolean mIsComputing = false;
    private Handler mInferenceHandler;
    private boolean mFront_back;

    private Context mContext;
    private FaceDet mFaceDet;
    private Paint mFaceLandmardkPaint;
    private CustomView mTransparent;
    private File mFile;

    private float resizeRatio;
    private float translationX;
    private float translationY;
    private float scaleRatio;

    private int frames;
    private int skip = 2;
    //private List<VisionDetRet> results;
    private boolean faceUpdated = false;
    private Point prev_glass = new Point();
    private Point prev_beard = new Point();
    private ArrayList<Point> landmarks;


    public void initialize(
            final Context context,
            final AssetManager assetManager,
            final CustomView transparent,
            final Handler handler,
            final boolean front_back) {
        this.mContext = context;
        this.mInferenceHandler = handler;
        this.mTransparent = transparent;
        this.mFront_back = front_back;
        //this.mFile = file;
        mFaceDet = new FaceDet(Constants.getFaceShapeModelPath());

        mFaceLandmardkPaint = new Paint();
        mFaceLandmardkPaint.setColor(Color.GREEN);
        mFaceLandmardkPaint.setStrokeWidth(4);
        mFaceLandmardkPaint.setStyle(Paint.Style.STROKE);
    }

    public void deInitialize() {
        synchronized (OnGetImageListener.this) {
            if (mFaceDet != null) {
                mFaceDet.release();
            }
        }
    }

    private void drawResizedBitmap(final Bitmap src, final Bitmap dst) {
        Display getOrient = ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        int orientation = Configuration.ORIENTATION_UNDEFINED;
        Point point = new Point();
        getOrient.getSize(point);
        int screen_width = point.x;
        int screen_height = point.y;
        Log.d(TAG, String.format("screen size (%d,%d)", screen_width, screen_height));
        if (screen_width < screen_height) {
            orientation = Configuration.ORIENTATION_PORTRAIT;
            if(mFront_back == true)
                mScreenRotation = 270;
            else
                mScreenRotation = 90;
        } else {
            orientation = Configuration.ORIENTATION_LANDSCAPE;
            mScreenRotation = 0;
        }

        Assert.assertEquals(dst.getWidth(), dst.getHeight());
        final float minDim = Math.min(src.getWidth(), src.getHeight());

        final Matrix matrix = new Matrix();

        // We only want the center square out of the original rectangle.
        final float translateX = -Math.max(0, (src.getWidth() - minDim) / 2);
        final float translateY = -Math.max(0, (src.getHeight() - minDim) / 2);
        translationX = -translateX;
        translationY = -translateY;
        matrix.preTranslate(translateX, translateY);

        final float scaleFactor = dst.getHeight() / minDim;
        scaleRatio = scaleFactor;
        matrix.postScale(scaleFactor, scaleFactor);

        // Rotate around the center if necessary.
        if (mScreenRotation != 0) {
            translationX = -translateY;
            translationY = -translateX;
            matrix.postTranslate(-dst.getWidth() / 2.0f, -dst.getHeight() / 2.0f);
            matrix.postRotate(mScreenRotation);
            matrix.postTranslate(dst.getWidth() / 2.0f, dst.getHeight() / 2.0f);
        }

        if(mFront_back) {
            matrix.postScale(-1, 1, dst.getWidth()/2f, dst.getHeight()/2f);
        }

        final Canvas canvas = new Canvas(dst);
        canvas.drawBitmap(src, matrix, null);
    }

    private Bitmap convertToGrayscale(Bitmap src)
    {
        int width, height;
        height = src.getHeight();
        width = src.getWidth();

        Bitmap bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmpGrayscale);
        Paint paint = new Paint();
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0);
        ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);
        paint.setColorFilter(f);
        c.drawBitmap(src, 0, 0, paint);
        return bmpGrayscale;
    }

    @Override
    public void onImageAvailable(final ImageReader reader) {

        Image image = null;
        try {
            image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            // No mutex needed as this method is not reentrant.
            if (mIsComputing) {
                image.close();
                return;
            }
            mIsComputing = true;
            Trace.beginSection("imageAvailable");


            final Plane[] planes = image.getPlanes();

            // Initialize the storage bitmaps once when the resolution is known.
            if (mPreviewWdith != image.getWidth() || mPreviewHeight != image.getHeight()) {
                mPreviewWdith = image.getWidth();
                mPreviewHeight = image.getHeight();

                Log.d(TAG, String.format("Initializing at size %dx%d", mPreviewWdith, mPreviewHeight));
                mRGBBytes = new int[mPreviewWdith * mPreviewHeight];
                mRGBframeBitmap = Bitmap.createBitmap(mPreviewWdith, mPreviewHeight, Config.ARGB_8888);
                mCroppedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Config.ARGB_8888);

                mYUVBytes = new byte[planes.length][];
                for (int i = 0; i < planes.length; ++i) {
                    mYUVBytes[i] = new byte[planes[i].getBuffer().capacity()];
                }
            }

            for (int i = 0; i < planes.length; ++i) {
                planes[i].getBuffer().get(mYUVBytes[i]);
            }

            final int yRowStride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();
            ImageUtils.convertYUV420ToARGB8888(
                    mYUVBytes[0],
                    mYUVBytes[1],
                    mYUVBytes[2],
                    mRGBBytes,
                    mPreviewWdith,
                    mPreviewHeight,
                    yRowStride,
                    uvRowStride,
                    uvPixelStride,
                    false);

            image.close();
        } catch (final Exception e) {
            if (image != null) {
                image.close();
            }
            Log.e(TAG, "Exception!", e);
            Trace.endSection();
            return;
        }

        mRGBframeBitmap.setPixels(mRGBBytes, 0, mPreviewWdith, 0, 0, mPreviewWdith, mPreviewHeight);
        //drawResizedBitmap(mRGBframeBitmap, mCroppedBitmap);
        float aspect = 1f * mRGBframeBitmap.getWidth() / mRGBframeBitmap.getHeight();
        final float scale = 2;
        drawResizedBitmap(scaleAndRotateBitmap(mRGBframeBitmap, mRGBframeBitmap.getWidth()/scale, 0, aspect), mCroppedBitmap);
        final Bitmap grayBitmap = convertToGrayscale(mCroppedBitmap);
        Display getOrient = ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        Point point = new Point();
        getOrient.getSize(point);
        int screen_width = point.x;
        resizeRatio = (float) screen_width / (float) mPreviewHeight;
        mInferenceHandler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        frames++;
                        if (!new File(Constants.getFaceShapeModelPath()).exists()) {
                            FileUtils.copyFileFromRawToOthers(mContext, R.raw.shape_predictor_68_face_landmarks, Constants.getFaceShapeModelPath());
                        }

                        List<VisionDetRet> results;
                        long startTime = System.currentTimeMillis();
                        synchronized (OnGetImageListener.this) {
                            //if(frames % skip == 0) {
                            results = mFaceDet.detect(grayBitmap);
//                            }
                        }
                        long endTime = System.currentTimeMillis();
                        //Log.e(TAG, "Time cost: " + String.valueOf((endTime - startTime) / 1000f) + " sec");
                        // Draw on bitmap
                        SurfaceHolder holder = mTransparent.mHolder;
                        Canvas canvas = holder.lockCanvas();
                        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                        if (results != null) {
                            for (final VisionDetRet ret : results) {
//                                Rect bounds = new Rect();
//                                bounds.left = (int) ( (ret.getLeft() / scaleRatio + translationX) * resizeRatio * 2);
//                                bounds.top = (int) ( (ret.getTop() / scaleRatio + translationY) * resizeRatio * 2);
//                                bounds.right = (int) ( (ret.getRight() / scaleRatio + translationX) * resizeRatio * 2);
//                                bounds.bottom = (int) ( (ret.getBottom() / scaleRatio + translationY) * resizeRatio * 2);
                                if(holder.getSurface().isValid()) {
                                    //canvas.drawRect(bounds, mFaceLandmardkPaint);
                                    ArrayList<Point> temp = ret.getFaceLandmarks();
                                    for (int i = 0; i < temp.size(); i++) {
                                        Point point = temp.get(i);
                                        point.x = (int) ((point.x / scaleRatio + translationX) * resizeRatio * scale);
                                        point.y = (int) ((point.y / scaleRatio + translationY) * resizeRatio * scale);
                                    }
                                    if(landmarks == null || Math.sqrt(Math.pow(temp.get(0).x - landmarks.get(0).x, 2) +
                                            Math.pow(temp.get(0).y - landmarks.get(0).y, 2)) > 10) {
                                        landmarks = ret.getFaceLandmarks();
                                        //faceUpdated = true;
                                    }
//                                    if(faceUpdated) {
//                                        for (int i = 0; i < landmarks.size(); i++) {
//                                            Point point = landmarks.get(i);
//                                            point.x = (int) ((point.x / scaleRatio + translationX) * resizeRatio * scale);
//                                            point.y = (int) ((point.y / scaleRatio + translationY) * resizeRatio * scale);
//                                        }
//                                    }
//                                    if(landmarks.size()==68){
//                                        drawLeftEye(landmarks, canvas);
//                                        drawRightEye(landmarks, canvas);
//                                        drawNose(landmarks, canvas);
//                                        drawOuterMouth(landmarks, canvas);
//                                        drawInnerMouth(landmarks, canvas);
//                                        drawJaw(landmarks, canvas);
//                                        drawEyebrow(landmarks, canvas);
//                                    }
//                                    for (Point point : landmarks) {
//                                        int pointX = (int) ( (point.x / scaleRatio + translationX) * resizeRatio);
//                                        int pointY = (int) ( (point.y / scaleRatio + translationY) * resizeRatio);
//                                        canvas.drawCircle(pointX, pointY, 2, mFaceLandmardkPaint);
//                                    }
                                    if(OnClickBooleans.drawSunglasses)
                                        drawSunglass(landmarks, canvas);
                                    if(OnClickBooleans.drawBeard)
                                        drawBeard(landmarks, canvas);
                                    if(OnClickBooleans.drawDogFace) {
                                        drawDogNose(landmarks, canvas);
                                        drawDogEars(landmarks, canvas);
                                    }
                                    if(OnClickBooleans.drawFlowerCrown) {
                                        drawFlower(landmarks, canvas);
                                    }
                                    if(OnClickBooleans.drawGlassesPlus1) {
                                        drawGlassesPlus1(landmarks, canvas);
                                    }
                                    if(OnClickBooleans.drawBeard1)
                                        drawBeard1(landmarks, canvas);
                                    if(OnClickBooleans.drawKingCrown)
                                        drawKingCrown(landmarks, canvas);
                                    if(OnClickBooleans.drawPigNose)
                                        drawPigNose(landmarks, canvas);
                                    if(OnClickBooleans.drawBatmanMask)
                                        drawBatmanMask(landmarks, canvas);
                                    if(OnClickBooleans.drawHalo)
                                        drawHalo(landmarks, canvas);
                                    if(OnClickBooleans.drawGenji)
                                        drawGenjiSpray(landmarks, canvas);
                                }
                            }
                        }
                        mIsComputing = false;
                        faceUpdated = false;
                        holder.unlockCanvasAndPost(canvas);
                    }
                });

        Trace.endSection();
    }

    private void drawSunglass(ArrayList<Point> landmarks, Canvas canvas) {
        Bitmap glasses = BitmapFactory.decodeResource(mContext.getResources(),R.drawable.sunglasses);
        float aspectRatio = 1f * glasses.getWidth() / glasses.getHeight();

        Point point0 = landmarks.get(0);
        Point point16 = landmarks.get(16);
        Point pointGlass = landmarks.get(28);

        float distanceX = point16.x - point0.x;
        double radian = Math.atan(1f*(point0.y-point16.y)/(point0.x-point16.x));
        double degree = radianToDegree(radian);
        Bitmap newGlasses = scaleAndRotateBitmap(glasses, distanceX / (float)Math.cos(radian), degree, aspectRatio);

        float x, y;
            x = pointGlass.x - newGlasses.getWidth() / 2f;
            y = pointGlass.y - newGlasses.getHeight() / 2f;
        //}
        canvas.drawBitmap(newGlasses, x, y, new Paint());
    }

    private void drawBeard(ArrayList<Point> landmarks, Canvas canvas) {
        Bitmap beard = BitmapFactory.decodeResource(mContext.getResources(),R.drawable.beard);
        float aspectRatio = 1f * beard.getWidth() / beard.getHeight();

        Point point4 = landmarks.get(4);
        Point point12 = landmarks.get(12);
        Point pointBeard = landmarks.get(57);

        float distanceX = point12.x - point4.x;
        double radian = Math.atan(1f*(point4.y-point12.y)/(point4.x-point12.x));
        double degree = radianToDegree(radian);
        Bitmap newBeard = scaleAndRotateBitmap(beard, distanceX / (float)Math.cos(radian) * 6 / 5, degree, aspectRatio);

        float x, y;
            x = pointBeard.x - newBeard.getWidth() / 2f;
            y = pointBeard.y - newBeard.getHeight() / 2f;
        canvas.drawBitmap(newBeard, x, y, new Paint());
    }

    private void drawDogNose(ArrayList<Point> landmarks, Canvas canvas) {
        Bitmap nose = BitmapFactory.decodeResource(mContext.getResources(),R.drawable.dog_face_nose);
        float aspectRatio = 1f * nose.getWidth() / nose.getHeight();

        Point point31 = landmarks.get(31);
        Point point35 = landmarks.get(35);
        Point pointNose = landmarks.get(30);

        float distanceX = (point35.x - point31.x) * 5 / 3f;
        double radian = Math.atan(1f*(point35.y-point31.y)/(point35.x-point31.x));
        double degree = radianToDegree(radian);
        Bitmap newNose = scaleAndRotateBitmap(nose, distanceX / (float)Math.cos(radian), degree, aspectRatio);

        float x, y;
        x = pointNose.x - newNose.getWidth() / 2f;
        y = pointNose.y - newNose.getHeight() / 2f;
        //}
        canvas.drawBitmap(newNose, x, y, new Paint());
    }

    private void drawDogEars(ArrayList<Point> landmarks, Canvas canvas) {
        Bitmap left_ear = BitmapFactory.decodeResource(mContext.getResources(),R.drawable.dog_face_left_ear);
        Bitmap right_ear = BitmapFactory.decodeResource(mContext.getResources(),R.drawable.dog_face_right_ear);
        //left ear
        float aspectRatio = 1f * left_ear.getWidth() / left_ear.getHeight();

        Point point17 = landmarks.get(17);
        Point point21 = landmarks.get(21);
        Point pointLeftEar = landmarks.get(18);

        float distanceX = point21.x - point17.x;
        double radian = Math.atan(1f*(point21.y-point17.y)/(point21.x-point17.x));
        double degree = radianToDegree(radian);
        float distance = (float)getDistance(landmarks.get(18), landmarks.get(36));
        Bitmap newLeftEar = scaleAndRotateBitmap(left_ear, distanceX / (float)Math.cos(radian)*1.2f, degree, aspectRatio);

        float x, y;
        x = pointLeftEar.x - newLeftEar.getWidth() / 2f + distance * 3f * (float)Math.sin(radian);
        y = pointLeftEar.y - newLeftEar.getHeight() / 2f - distance * 3f * (float)Math.cos(radian);
        canvas.drawBitmap(newLeftEar, x, y, new Paint());

        //right ear
        aspectRatio = 1f * right_ear.getWidth() / right_ear.getHeight();

        Point pointRightEar = landmarks.get(25);

        Bitmap newRightEar = scaleAndRotateBitmap(right_ear, distanceX / (float)Math.cos(radian)*1.2f, degree, aspectRatio);
        x = pointRightEar.x - newRightEar.getWidth() / 2f + distance * 3f * (float)Math.sin(radian);
        y = pointRightEar.y - newRightEar.getHeight() / 2f - distance * 3f * (float)Math.cos(radian);

        canvas.drawBitmap(newRightEar, x, y, new Paint());
    }

    private void drawFlower(ArrayList<Point> landmarks, Canvas canvas) {
        Bitmap flower = BitmapFactory.decodeResource(mContext.getResources(),R.drawable.flower);

        float aspectRatio = 1f * flower.getWidth() / flower.getHeight();

        Point point0 = landmarks.get(0);
        Point point16 = landmarks.get(16);
        Point pointFlower = landmarks.get(27);

        float distanceX = point16.x - point0.x;
        double radian = Math.atan(1f*(point16.y-point0.y)/(point16.x-point0.x));
        double degree = radianToDegree(radian);
        float distance = (float)getDistance(landmarks.get(27), landmarks.get(30));
        Bitmap newFlower = scaleAndRotateBitmap(flower, distanceX / (float)Math.cos(radian) * 1.5f, degree, aspectRatio);

        float x, y;
        x = pointFlower.x - newFlower.getWidth() / 2f + distance * 1.33f * (float)Math.sin(radian);
        y = pointFlower.y - newFlower.getHeight() / 2f - distance * 1.33f * (float)Math.cos(radian);

        canvas.drawBitmap(newFlower, x, y, new Paint());
    }

    private void drawGlassesPlus1(ArrayList<Point> landmarks, Canvas canvas) {
        Bitmap glasses = BitmapFactory.decodeResource(mContext.getResources(),R.drawable.glasses_plus1);
        float aspectRatio = 1f * glasses.getWidth() / glasses.getHeight();

        Point point0 = landmarks.get(0);
        Point point16 = landmarks.get(16);
        Point pointGlass = landmarks.get(28);

        float distanceX = point16.x - point0.x;
        double radian = Math.atan(1f*(point0.y-point16.y)/(point0.x-point16.x));
        double degree = radianToDegree(radian);
        Bitmap newGlasses = scaleAndRotateBitmap(glasses, distanceX / (float)Math.cos(radian), degree, aspectRatio);

        float x, y;
        x = pointGlass.x - newGlasses.getWidth() / 2f;
        y = (pointGlass.y - newGlasses.getHeight() / 2f) * 0.97f;

        canvas.drawBitmap(newGlasses, x, y, new Paint());
    }

    private void drawBeard1(ArrayList<Point> landmarks, Canvas canvas) {
        Bitmap beard1 = BitmapFactory.decodeResource(mContext.getResources(),R.drawable.beard1);
        float aspectRatio = 1f * beard1.getWidth() / beard1.getHeight();

        Point point31 = landmarks.get(31);
        Point point35 = landmarks.get(35);
        Point pointBeard1 = landmarks.get(33);

        float distanceX = point35.x - point31.x;
        double radian = Math.atan(1f*(point35.y-point31.y)/(point35.x-point31.x));
        double degree = radianToDegree(radian);
        Bitmap newBeard = scaleAndRotateBitmap(beard1, distanceX / (float)Math.cos(radian) * 1.6f, degree, aspectRatio);

        float x, y;
        x = pointBeard1.x - newBeard.getWidth() / 2f;
        y = (pointBeard1.y - newBeard.getHeight() / 2f) * 1.02f;
        canvas.drawBitmap(newBeard, x, y, new Paint());
    }

    private void drawKingCrown(ArrayList<Point> landmarks, Canvas canvas) {
        Bitmap crown = BitmapFactory.decodeResource(mContext.getResources(),R.drawable.crown);

        float aspectRatio = 1f * crown.getWidth() / crown.getHeight();

        Point point39 = landmarks.get(39);
        Point point42 = landmarks.get(42);
        Point pointFlower = landmarks.get(27);

        float distanceX = point42.x - point39.x;
        double radian = Math.atan(1f*(point42.y-point39.y)/(point42.x-point39.x));
        double degree = radianToDegree(radian);
        float distance = (float)getDistance(landmarks.get(39), landmarks.get(42));
        Bitmap newCrown = scaleAndRotateBitmap(crown, distanceX / (float)Math.cos(radian), degree, aspectRatio);

        float x, y;
        x = pointFlower.x - newCrown.getWidth() / 2f + distance * 3.3f * (float)Math.sin(radian);
        y = pointFlower.y - newCrown.getHeight() / 2f - distance * 3.3f * (float)Math.cos(radian);
        //}
        canvas.drawBitmap(newCrown, x, y, new Paint());
    }

    private void drawPigNose(ArrayList<Point> landmarks, Canvas canvas) {
        Bitmap nose = BitmapFactory.decodeResource(mContext.getResources(),R.drawable.pig_nose);
        float aspectRatio = 1f * nose.getWidth() / nose.getHeight();

        Point point31 = landmarks.get(31);
        Point point35 = landmarks.get(35);
        Point pointNose = landmarks.get(30);

        float distanceX = (point35.x - point31.x) * 5 / 3f;
        double radian = Math.atan(1f*(point35.y-point31.y)/(point35.x-point31.x));
        double degree = radianToDegree(radian);
        Bitmap newNose = scaleAndRotateBitmap(nose, distanceX / (float)Math.cos(radian), degree, aspectRatio);

        float x, y;
        x = pointNose.x - newNose.getWidth() / 2f;
        y = pointNose.y - newNose.getHeight() / 2f;
        //}
        canvas.drawBitmap(newNose, x, y, new Paint());
    }

    private void drawBatmanMask(ArrayList<Point> landmarks, Canvas canvas) {
        Bitmap batman = BitmapFactory.decodeResource(mContext.getResources(),R.drawable.mask_batman);
        float aspectRatio = 1f * batman.getWidth() / batman.getHeight();

        Point point0 = landmarks.get(0);
        Point point16 = landmarks.get(16);
        Point pointMask = landmarks.get(28);

        float distanceX = (point16.x - point0.x) * 1.05f;
        double radian = Math.atan(1f*(point16.y-point0.y)/(point16.x-point0.x));
        double degree = radianToDegree(radian);
        float distance = (float)getDistance(landmarks.get(27), landmarks.get(28));
        Bitmap newBatman = scaleAndRotateBitmap(batman, distanceX / (float)Math.cos(radian), degree, aspectRatio);

        float x, y;
        x = pointMask.x - newBatman.getWidth() / 2f + distance * 4.55f * (float)Math.sin(radian);
        y = pointMask.y - newBatman.getHeight() / 2f - distance * 4.55f * (float)Math.cos(radian);
        //}
        canvas.drawBitmap(newBatman, x, y, new Paint());
    }

    private void drawHalo(ArrayList<Point> landmarks, Canvas canvas) {
        Bitmap batman = BitmapFactory.decodeResource(mContext.getResources(),R.drawable.halo);
        float aspectRatio = 1f * batman.getWidth() / batman.getHeight();

        Point point0 = landmarks.get(0);
        Point point16 = landmarks.get(16);
        Point pointMask = landmarks.get(27);

        float distanceX = (point16.x - point0.x) * 0.5f;
        double radian = Math.atan(1f*(point16.y-point0.y)/(point16.x-point0.x));
        double degree = radianToDegree(radian);
        float distance = (float)getDistance(landmarks.get(27), landmarks.get(33));
        Bitmap newBatman = scaleAndRotateBitmap(batman, distanceX / (float)Math.cos(radian), degree, aspectRatio);

        float x, y;
        x = pointMask.x - newBatman.getWidth() / 2f + distance * 2.5f * (float)Math.sin(radian);
        y = pointMask.y - newBatman.getHeight() / 2f - distance * 2.5f * (float)Math.cos(radian);
        //}
        canvas.drawBitmap(newBatman, x, y, new Paint());
    }
    private void drawGenjiSpray(ArrayList<Point> landmarks, Canvas canvas) {
        Bitmap batman = BitmapFactory.decodeResource(mContext.getResources(), R.drawable.genji_spray);
        float aspectRatio = 1f * batman.getWidth() / batman.getHeight();

        Point point13 = landmarks.get(13);
        Point point35 = landmarks.get(35);
        Point pointGenji = landmarks.get(11);

        float distanceX = (point13.x - point35.x) * 0.5f;
        double radian = Math.atan(1f * (point13.y - point35.y) / (point13.x - point35.x));
        double degree = radianToDegree(radian);
        float distance = (float) getDistance(landmarks.get(35), landmarks.get(42));
        Bitmap newGenji = scaleAndRotateBitmap(batman, distanceX / (float) Math.cos(radian), degree + 30, aspectRatio);

        float x, y;
        x = pointGenji.x - newGenji.getWidth() / 2f + distance * 1f * (float) Math.sin(radian);
        y = pointGenji.y - newGenji.getHeight() / 2f - distance * 1f * (float) Math.cos(radian);
        //}
        canvas.drawBitmap(newGenji, x, y, new Paint());
    }
    private double radianToDegree(double radian) {
        return radian / Math.PI * 180;
    }

    private Bitmap scaleAndRotateBitmap(Bitmap src, float width, double degree, float aspectRatio){
        Matrix matrix = new Matrix();
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(src, (int)width, (int)(width/aspectRatio) ,true);
        matrix.postRotate((float)degree, scaledBitmap.getWidth() / 2f, scaledBitmap.getHeight() / 2f);
        return Bitmap.createBitmap(scaledBitmap, 0, 0, scaledBitmap.getWidth(), scaledBitmap.getHeight(), matrix, true);
    }

    private double getDistance(Point x, Point y) {
        return Math.sqrt(Math.pow((x.x - y.x), 2) + Math.pow((x.y - y.y), 2));
    }

    private void drawLeftEye(ArrayList<Point> landmarks, Canvas canvas){
        Paint pt = new Paint();
        pt.setTextSize(30f);
        pt.setColor(Color.GREEN);
        for(int i=36;i<=41;i++){
            Point point = landmarks.get(i);
//            int pointX = (int) ((point.x / scaleRatio + tx) * resizeRatio * 2); //scaled when drawresizebitmap by 2
//            int pointY = (int) ((point.y / scaleRatio + ty) * resizeRatio * 2);
//            //canvas.drawCircle(pointX, pointY, 2, paint);
//            //canvas.drawText("" + i, pointX, pointY, new Paint());
            canvas.drawText("" + i, point.x, point.y, pt);
        }
    }

    private void drawRightEye(ArrayList<Point> landmarks, Canvas canvas){
        Paint pt = new Paint();
        pt.setTextSize(30f);
        pt.setColor(Color.GREEN);
        for(int i=42;i<=47;i++){
            Point point = landmarks.get(i);
//            int pointX = (int) ((point.x / scaleRatio + tx) * resizeRatio * 2);
//            int pointY = (int) ((point.y / scaleRatio + ty) * resizeRatio * 2);
//            //canvas.drawCircle(pointX, pointY, 2, paint);
            canvas.drawText("" + i, point.x, point.y, pt);
        }
    }

    private void drawNose(ArrayList<Point> landmarks, Canvas canvas){
        Paint pt = new Paint();
        pt.setTextSize(30f);
        pt.setColor(Color.GREEN);
        for(int i=27;i<=35;i++){
            Point point = landmarks.get(i);
//            int pointX = (int) ((point.x / scaleRatio + tx) * resizeRatio * 2);
//            int pointY = (int) ((point.y / scaleRatio + ty) * resizeRatio * 2);
//            //canvas.drawCircle(pointX, pointY, 2, paint);
////            canvas.drawText("" + i, pointX, pointY, new Paint());
            canvas.drawText("" + i, point.x, point.y, pt);
        }
    }

    private void drawOuterMouth(ArrayList<Point> landmarks, Canvas canvas){
        Paint pt = new Paint();
        pt.setTextSize(30f);
        pt.setColor(Color.GREEN);
        for(int i=48;i<=59;i++){
            Point point = landmarks.get(i);
//            int pointX = (int) ((point.x / scaleRatio + tx) * resizeRatio * 2);
//            int pointY = (int) ((point.y / scaleRatio + ty) * resizeRatio * 2);
//            //canvas.drawCircle(pointX, pointY, 2, paint);
////            canvas.drawText("" + i, pointX, pointY, new Paint());
            canvas.drawText("" + i, point.x, point.y, pt);
        }
    }

    private void drawInnerMouth(ArrayList<Point> landmarks, Canvas canvas){
        Paint pt = new Paint();
        pt.setTextSize(30f);
        pt.setColor(Color.GREEN);
        for(int i=60;i<=63;i++){
            Point point = landmarks.get(i);
//            int pointX = (int) ((point.x / scaleRatio + tx) * resizeRatio * 2);
//            int pointY = (int) ((point.y / scaleRatio + ty) * resizeRatio * 2);
//            //canvas.drawCircle(pointX, pointY, 2, paint);
////            canvas.drawText("" + i, pointX, pointY, new Paint());
            canvas.drawText("" + i, point.x, point.y, pt);
        }

        for(int i=64;i<=67;i++){
            Point point = landmarks.get(i);
//            int pointX = (int) ((point.x / scaleRatio + tx) * resizeRatio * 2);
//            int pointY = (int) ((point.y / scaleRatio + ty) * resizeRatio * 2);
//            //canvas.drawCircle(pointX, pointY, 2, paint);
////            canvas.drawText("" + i, pointX, pointY, new Paint());
            canvas.drawText("" + i, point.x, point.y, pt);
        }
    }

    private void drawJaw(ArrayList<Point> landmarks, Canvas canvas){
        Paint pt = new Paint();
        pt.setTextSize(30f);
        pt.setColor(Color.GREEN);
        for(int i=0;i<=14;i++){
            Point point = landmarks.get(i);
//            int pointX = (int) ((point.x / scaleRatio + tx) * resizeRatio * 2);
//            int pointY = (int) ((point.y / scaleRatio + ty) * resizeRatio * 2);
//            //canvas.drawCircle(pointX, pointY, 2, paint);
////            canvas.drawText("" + i, pointX, pointY, new Paint());
            canvas.drawText("" + i, point.x, point.y, pt);
        }
    }

    private void drawEyebrow(ArrayList<Point> landmarks, Canvas canvas){
        Paint pt = new Paint();
        pt.setTextSize(30f);
        pt.setColor(Color.GREEN);
        for(int i=15;i<=26;i++){
            Point point = landmarks.get(i);
//            int pointX = (int) ((point.x / scaleRatio + tx) * resizeRatio * 2);
//            int pointY = (int) ((point.y / scaleRatio + ty) * resizeRatio * 2);
//            //canvas.drawCircle(pointX, pointY, 2, paint);
////            canvas.drawText("" + i, pointX, pointY, new Paint());
            canvas.drawText("" + i, point.x, point.y, pt);
        }
    }
}

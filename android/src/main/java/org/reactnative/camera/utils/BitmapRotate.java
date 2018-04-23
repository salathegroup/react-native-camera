package org.reactnative.camera.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.util.Log;

import com.example.android.rs.hellocompute.ScriptC_rotate;

//import java.math.BigInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by Boris Conforty on 27.03.18.
 */

public class BitmapRotate {
    private Context mContext;
    private RenderScript mRenderScript;
    private ScriptC_rotate mScript;
    private Allocation aIn, aOut;
    Allocation mInAllocation, mRotateAllocation, mRowIndicesAllocation;
    private Bitmap mBitmap;
    private int mRotation, mWidth, mHeight;
    private ReentrantLock lock;

    public BitmapRotate(Context context) {
        lock = new ReentrantLock();
        mContext = context;
    }

    private void prepare(int width, int height, int rotation) {
        boolean renderScriptNeeded = mRenderScript == null;
        boolean allocNeeded = renderScriptNeeded || mBitmap == null
                || mRotation != ((rotation % 360) + 360) % 360 || width != mWidth || height != mHeight;

        if (renderScriptNeeded) {
            mRenderScript = RenderScript.create(mContext);
            mScript = new ScriptC_rotate(mRenderScript);
        }
        if (allocNeeded) {
            mRotation = rotation;
            mWidth = width;
            mHeight = height;

            int resWidth, resHeight;

            if (rotation % 180 == 0) {
                resWidth = width;
                resHeight = height;
            } else {
                resWidth = height;
                resHeight = width;
            }
            mBitmap = Bitmap.createBitmap(resWidth, resHeight, Bitmap.Config.ARGB_8888);

            Type.Builder inTypeBuilder = new Type.Builder(mRenderScript, Element.U8_4(mRenderScript));
            inTypeBuilder.setX(width).setY(height);
            Type inType = inTypeBuilder.create();
            mInAllocation = Allocation.createTyped(mRenderScript, inType);

            Type.Builder outTypeBuilder = new Type.Builder(mRenderScript, Element.U32(mRenderScript));
            outTypeBuilder.setX(resWidth).setY(resHeight);
            Type outType = outTypeBuilder.create();
            mRotateAllocation = Allocation.createTyped(mRenderScript, outType);

            mRowIndicesAllocation = Allocation.createSized(mRenderScript,
                    Element.I32(mRenderScript), height, Allocation.USAGE_SCRIPT);

            int[] rowIndices = new int[height];
            for (int i = 0; i < height; i++) {
                rowIndices[i] = i;
            }
            mRowIndicesAllocation.copyFrom(rowIndices);

            mScript.set_gImageWidth(width);
            mScript.set_gImageHeight(height);
            mScript.bind_gInPixels(mInAllocation);
            mScript.bind_gRotatePixels(mRotateAllocation);
            mScript.set_gRotation(rotation);
        }
    }

    public Bitmap refreshBitmap(byte[] data, int width, int height, int rotation) {
        this.prepare(width, height, rotation);
        aIn.copyFrom(data);
        mScript.forEach_root(aIn, aOut);
        aOut.copyTo(mBitmap);
        return mBitmap;
    }

    public Bitmap refreshBitmap(Bitmap bitmap, int rotation) {
        lock.lock();
        try {
            long start = System.nanoTime();

            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            this.prepare(width, height, rotation);

            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            /*for (int i=0; i<2000; i++) {
                Log.d("PIXEL", "" + Long.toHexString(pixels[i] & 0xFFFFFFFFl));
            }*/
            Log.d("PROFILE", "Get pixels: " + (System.nanoTime() - start) / 1E6);

            mInAllocation.copyFromUnchecked(pixels);
            Log.d("PROFILE", "Copy pixels to alloc: " + (System.nanoTime() - start) / 1E6);

            mScript.set_gFixByteOrder(false);

            return refreshBitmap();
        } finally {
            lock.unlock();
        }
    }

    public Bitmap refreshBitmap(Allocation aIn, int rotation) {
        lock.lock();
        try {
            long start = System.nanoTime();

            int width = aIn.getType().getX();
            int height = aIn.getType().getY();
            this.prepare(width, height, rotation);

            int w = aIn.getType().getX();
            int h = aIn.getType().getY();
            int c2 = w * h * 4;
            int c = aIn.getBytesSize();
            assert c == c2;
            byte[] pixels = new byte[c];
            aIn.copyTo(pixels);
            Log.d("PROFILE", "Get pixels: " + (System.nanoTime() - start) / 1E6);
            mInAllocation.copyFrom(pixels);
            Log.d("PROFILE", "Copy from pixels: " + (System.nanoTime() - start) / 1E6);

            mScript.set_gFixByteOrder(true);
            return refreshBitmap();
        } finally {
            lock.unlock();
        }
    }

    private Bitmap refreshBitmap() {
        long start = System.nanoTime();

        Log.d("PROFILE", "Set script variables: " + (System.nanoTime() - start) / 1E6);

        mScript.forEach_root(mRowIndicesAllocation, mRowIndicesAllocation);
        Log.d("PROFILE", "Run script: " + (System.nanoTime() - start) / 1E6);

        int[] pixels = new int[mWidth * mHeight];
        assert mRotateAllocation.getBytesSize() == pixels.length;
        mRotateAllocation.copyTo(pixels);
        Log.d("PROFILE", "Get pixels: " + (System.nanoTime() - start) / 1E6);

        int bmW = mBitmap.getWidth();
        int bmH = mBitmap.getHeight();
        mBitmap.setPixels(pixels, 0, bmW, 0, 0, bmW, bmH);
        Log.d("PROFILE", "Set pixels: " + (System.nanoTime() - start) / 1E6);

        return mBitmap;
/*
        rotation = 90;
        this.prepare(width, height, rotation);

        //Bitmap res = bitmap.copy(bitmap.getConfig(), true);
        aIn.copyFrom(bitmap);
        //aIn = Allocation.createFromBitmap(mRenderScript, bitmap,
        //        Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);

        mScript.set_rotation(rotation);
        mScript.set_gIn(aIn);
        mScript.set_gOut(aOut);
        mScript.set_gScript(mScript);

        mScript.invoke_filter();
        //mScript.forEach_root(aIn, aOut);

        aOut.copyTo(mBitmap);
        return mBitmap;
        */
    }

}

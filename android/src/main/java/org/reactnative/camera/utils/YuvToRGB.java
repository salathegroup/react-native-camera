package org.reactnative.camera.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;

/**
 * Created by Boris Conforty on 26.03.18.
 */

public class YuvToRGB {
    private Context mContext;
    private RenderScript mRenderScript;
    private ScriptIntrinsicYuvToRGB mYuvToRgbIntrinsic;
    private Allocation aIn, aOut;
    private Bitmap mBitmap;

    public YuvToRGB(Context context) {
        mContext = context;
    }

    private void prepare(int width, int height) {
        if (mRenderScript == null) {
            mRenderScript = RenderScript.create(mContext);
            mYuvToRgbIntrinsic = ScriptIntrinsicYuvToRGB.create(mRenderScript, Element.U8_4(mRenderScript));
        }
        if (mBitmap == null || mBitmap.getWidth() != width || mBitmap.getHeight() != height) {
            int yuvDatalength = width * height * 3 / 2;  // 12 bits per pixel
            aIn = Allocation.createSized(mRenderScript, Element.U8(mRenderScript), yuvDatalength);

            mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

            aOut = Allocation.createFromBitmap(mRenderScript, mBitmap);  // this simple !

            mYuvToRgbIntrinsic.setInput(aIn);
        }
    }

    public Bitmap refreshBitmap(byte[] data, int width, int height) {
        this.prepare(width, height);
        aIn.copyFrom(data);
        mYuvToRgbIntrinsic.forEach(aOut);
        aOut.copyTo(mBitmap);
        return mBitmap;
    }
}

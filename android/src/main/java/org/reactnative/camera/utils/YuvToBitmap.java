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

public class YuvToBitmap {
    private Context mContext;
    private RenderScript mRenderScript;
    private ScriptIntrinsicYuvToRGB mYuvToRgbIntrinsic;
    private Allocation aIn, aOut;
    private Bitmap mBitmap;

    public YuvToBitmap(Context context) {
        mContext = context;
    }

    private void prepare(int width, int height) {
        boolean renderScriptNeeded = mRenderScript == null;
        boolean bitmapNeeded = mBitmap == null || mBitmap.getWidth() != width || mBitmap.getHeight() != height;
        boolean inNeeded = aIn == null || (mBitmap != null && mBitmap.getWidth() * mBitmap.getHeight() != width * height);

        if (renderScriptNeeded) {
            mRenderScript = RenderScript.create(mContext);
            mYuvToRgbIntrinsic = ScriptIntrinsicYuvToRGB.create(mRenderScript, Element.U8_4(mRenderScript));
        }
        if (bitmapNeeded) {
            mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            aOut = Allocation.createFromBitmap(mRenderScript, mBitmap);  // this simple !
        }
        if (inNeeded) {
            int yuvDatalength = width * height * 3 / 2;  // 12 bits per pixel
            aIn = Allocation.createSized(mRenderScript, Element.U8(mRenderScript), yuvDatalength);
            mYuvToRgbIntrinsic.setInput(aIn);
        }
    }

    public Allocation getOut() {
        return aOut;
    }

    public Bitmap refreshBitmap(byte[] data, int width, int height) {
        this.prepare(width, height);
        aIn.copyFrom(data);
        mYuvToRgbIntrinsic.forEach(aOut);
        aOut.copyTo(mBitmap);
        return mBitmap;
    }
}

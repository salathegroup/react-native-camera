/*
 * Copyright (C) 2011 The Android Open Source Project
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

#pragma version(1)
#pragma rs java_package_name(com.example.android.rs.hellocompute)

rs_allocation gIn;
rs_allocation gOut;
rs_script gScript;

int gRotation;

bool gFixByteOrder;

int gImageWidth;
int gImageHeight;
const uchar4 *gInPixels;
uchar4 *gRotatePixels;

/*
uchar4 __attribute__ ((kernel)) rotate_90_clockwise (uchar4 in, uint32_t x, uint32_t y) {
    uint32_t inX  = inWidth - 1 - y;
    uint32_t inY = x;
    const uchar4 *out = rsGetElementAt(inImage, inX, inY);
    return *out;
}

uchar4 __attribute__ ((kernel)) rotate_270_clockwise (uchar4 in, uint32_t x, uint32_t y) {
    uint32_t inX = y;
    uint32_t inY = inHeight - 1 - x;

    const uchar4 *out = rsGetElementAt(inImage, inX, inY);
    return *out;
}
*/


void root_(const uchar4 *v_in, uchar4 *v_out, const void *usrData, uint32_t x, uint32_t y) {
/*
    if (rotation == 0) {
        *v_out = *v_in;
    } else if (rotation == 90) {
        uint32_t inX = y; //gImageWidth - 1 - y;
        uint32_t inY = x;
        const uchar4 *out = rsGetElementAt(gIn, inX, inY);
        *v_out = *out;
    }

    /*
    uint32_t inX  = gImageWidth - 1 - y;
    uint32_t inY = x;
    const uchar4 *out = rsGetElementAt(gIn, inX, inY);
    *v_out = *out;
    */

    /*
    uint32_t inX  = gImageWidth - 1 - y;
    uint32_t inY = x;
    const uchar4 *out = rsGetElementAt(gIn, inX, inY);
    return *out;
*/

    rs_matrix4x4 matrix;
    rsMatrixLoadIdentity(&matrix);
    rsMatrixTranslate(&matrix, gImageWidth / 2.0f, gImageHeight / 2.0f, 0.0f);
    rsMatrixRotate(&matrix, gRotation, 0.0f, 0.0f, 1.0f);
    rsMatrixTranslate(&matrix, -gImageWidth/2.0f, -gImageHeight/2.0f, 0.0f);

    float4 in_vec = {x, y, 0.0f, 1.0f};
    float4 trans = rsMatrixMultiply(&matrix, in_vec);

    float trans_x = trans.x;
    float trans_y = trans.y;

    if ( trans_x < 0.0f) {
        trans_x = 0.0f;
    }
    else if ( trans_x >= gImageWidth) {
        trans_x = gImageWidth - 1.0f;
    }
    if ( trans_y < 0.0f) {
        trans_y = 0.0f;
    }
    else if ( trans_y >= gImageHeight) {
        trans_y = gImageHeight - 1.0f;
    }

    const uchar4 *element = rsGetElementAt(gIn, trans_x, trans_y);
    *v_out = *element;



/*
    float4 f4 = rsUnpackColor8888(*v_in);

    // get the grayscale value of all the pixels
  	float val = 0.2989 * f4.r + 0.5870 * f4.g + 0.1140 * f4.b;

  	// check if it is more than the threshold, if it is, set to white, else make it black
  	val = val > threshold ? 1 : 0;

  	// set the pixel values to the computed value
  	f4.r = f4.g = f4.b = val;

    float3 output = {f4.r, f4.g, f4.b};

    *v_out = rsPackColorTo8888(output);
    */
}

//90 degree rotate and convert
void root(const int32_t *v_in, int32_t *v_out, const void *usrData, uint32_t x, uint32_t y) {
    int32_t row_index = *v_in;

    if (gRotation == 0) {
        for (int i = gImageWidth - 1; i >= 0; i--) {
            uchar4 inPixel = gInPixels[row_index * gImageWidth + i];
            int targetPos = row_index * gImageWidth + i;
            if (gFixByteOrder) {
                float4 f4 = rsUnpackColor8888(inPixel);
                float3 output = {f4.b, f4.g, f4.r};
                gRotatePixels[targetPos] = rsPackColorTo8888(output);
            } else {
                gRotatePixels[targetPos] = inPixel;
            }
        }
    } else if (gRotation == 90) {
        uchar4 *inRow = &gInPixels[row_index * gImageWidth];
        int outOffset = gImageHeight - row_index - 1;
        for (int i = gImageWidth - 1; i >= 0; i--) {
            uchar4 inPixel = inRow[i];
            int targetPos = i * gImageHeight + outOffset;
            if (gFixByteOrder) {
                float4 f4 = rsUnpackColor8888(inPixel);
                float3 output = {f4.b, f4.g, f4.r};
                gRotatePixels[targetPos] = rsPackColorTo8888(output);
            } else {
                gRotatePixels[targetPos] = inPixel;
            }
        }
    } else if (gRotation == 180) {
        for (int i = gImageWidth - 1; i >= 0; i--) {
            uchar4 inPixel = gInPixels[row_index * gImageWidth + i];
            int targetPos = gImageWidth * gImageHeight - (row_index * gImageWidth + i) - 1;
            if (gFixByteOrder) {
                float4 f4 = rsUnpackColor8888(inPixel);
                float3 output = {f4.b, f4.g, f4.r};
                gRotatePixels[targetPos] = rsPackColorTo8888(output);
            } else {
                gRotatePixels[targetPos] = inPixel;
            }
        }
    } else if (gRotation == 270) {
        for (int i = gImageWidth - 1; i >= 0; i--) {
            uchar4 inPixel = gInPixels[row_index * gImageWidth + i];
            int targetPos = gImageHeight - i * gImageHeight - 1 + row_index;
            if (gFixByteOrder) {
                float4 f4 = rsUnpackColor8888(inPixel);
                float3 output = {f4.b, f4.g, f4.r};
                gRotatePixels[targetPos] = rsPackColorTo8888(output);
            } else {
                gRotatePixels[targetPos] = inPixel;
            }
        }
    }
}

void filter() {
    gImageWidth = rsAllocationGetDimX(gIn);
    gImageHeight = rsAllocationGetDimY(gIn);
    rsForEach(gScript, gIn, gOut);
}
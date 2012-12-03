package android.httpimage;

import android.graphics.Bitmap;

/**
 * @author gomino (amine.bezzarga@labgency.com)
 */
abstract public class PersistedBitmapCache implements BitmapCache {
	
    protected int mMaxNumOfPixelsConstraint = HttpImageManager.DECODING_MAX_PIXELS_DEFAULT;
	
    /**
     * maxNumOfPixels is used to specify the maximal size in
     * pixels that is tolerable in terms of memory usage.
     * @param max
     */
    public void setDecodingPixelConstraint (int maxNumOfPixels){
    	mMaxNumOfPixelsConstraint = maxNumOfPixels;
    }
    
    public int getDecodingPixelConstraint(){
    	return mMaxNumOfPixelsConstraint;
    }

}

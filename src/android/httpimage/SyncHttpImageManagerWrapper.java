package android.httpimage;

import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import android.httpimage.HttpImageManager.LoadRequest;


/**
 * 
 * Wrapper around HttpImageManager to provide synchronous loading behavior.
 * 
 * @author zonghai
 *
 */
public class SyncHttpImageManagerWrapper {

    private static final String TAG = "SyncHttpImageManagerWrapper";
    private static final boolean DEBUG = true;


    public SyncHttpImageManagerWrapper(HttpImageManager mgr) {
        mManager = mgr;
    }


    public Bitmap syncLoadImage ( Uri uri ) {

        mCompleted = false;
        Bitmap bitmap = mManager.loadImage(new HttpImageManager.LoadRequest(uri, new HttpImageManager.OnLoadResponseListener() {

            @Override
            public void onLoadResponse(LoadRequest r, Bitmap data) {
                synchronized (mLock) {
                    mBitmap = data;
                    mCompleted = true;
                    mLock.notifyAll();
                }

            }

            @Override
            public void onLoadError(LoadRequest r, Throwable e) {
                synchronized (mLock) {
                    mException = e;
                    mCompleted = true;
                    mLock.notifyAll();
                }
            }

            
            @Override
            public void onLoadProgress(LoadRequest r, long totalContentSize,
                    long loadedContentSize) {
            }

        }));

        if (bitmap != null)  return bitmap;
        
        synchronized ( mLock ) {
            if ( mBitmap != null ) return mBitmap;
            while ( !mCompleted ) {
                try {
                    if(DEBUG)  Log.d(TAG, "waiting for the request to be completed ");
                    mLock .wait();
                } catch (InterruptedException e1) {}
            }

        }
        if ( mException != null ) 
            throw new RuntimeException (mException);

        return mBitmap;
    }


    ////////PRIVATE
    private HttpImageManager mManager;
    private Object     mLock = new Object();
    private Bitmap  mBitmap;
    private Throwable mException;
    private boolean mCompleted;
}

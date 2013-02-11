package android.httpimage;


import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;

import android.annotation.TargetApi;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Bitmap.Config;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.widget.ImageView;


/**
 * HttpImageManager uses 3-level caching to download and store network images.
 * <p>
 *     ---------------<br>
 *     memory cache<br>
 *     ---------------<br>
 *     persistent storage (DB/FS)<br>
 *     ---------------<br>
 *     network loader<br>
 *     ---------------
 *     
 * <p>
 * HttpImageManager will first look up the memory cache, return the image bitmap if it was already
 * cached in memory. Upon missing, it will further look at the 2nd level cache, 
 * which is the persistence layer. It only goes to network if the resource has never been downloaded.
 * 
 * <p>
 * The downloading process is handled in asynchronous manner. To get notification of the response, 
 * one can add an OnLoadResponseListener to the LoadRequest object.
 * 
 * <p>
 * HttpImageManager is usually used for ImageView to display a network image. To simplify the code, 
 * One can register an ImageView object as target to the LoadRequest instead of an 
 * OnLoadResponseListener. HttpImageManager will try to feed the loaded resource to the target ImageView
 * upon successful download. Following code snippet shows how it is used in a customer list adapter.
 * 
 * <p>
 * <pre>
 *         ...
 *         String imageUrl = userInfo.getUserImage();
 *         ImageView imageView = holder.image;
 * 
 *         imageView.setImageResource(R.drawable.default_image);
 * 
 *         if(!TextUtils.isEmpty(imageUrl)){
 *             Bitmap bitmap = mHttpImageManager.loadImage(new HttpImageManager.LoadRequest(Uri.parse(imageUrl), imageView));
 *            if (bitmap != null) {
 *                imageView.setImageBitmap(bitmap);
 *            }
 *        }
 *
 * </pre>
 * 
 * 
 * @author zonghai@gmail.com
 * @author abezzarg@gmail.com
 */
public class HttpImageManager{

    private static final String TAG = HttpImageManager.class.getSimpleName();
    private static final boolean DEBUG = true;

    public static final int DEFAULT_CACHE_SIZE 			= 64;
    public static final int UNCONSTRAINED 				= -1;
    public static final int DECODING_MAX_PIXELS_DEFAULT = 600 * 800;
	public static final int SCRUB_FACTOR 				= 2;//scrub factor - bitmaps will be scrubbed down by a factor of this value (used for thumbnail)
	
    
    private int mMaxNumOfPixelsConstraint = DECODING_MAX_PIXELS_DEFAULT;
    private MemoryBitmapCache mCache;
    private PersistedBitmapCache mPersistence;
    private NetworkResourceLoader mNetworkResourceLoader = new NetworkResourceLoader(); 
	private HashMap<Integer, Drawable> 	mDefaults;

    private Handler mHandler = new Handler();
    private PausableThreadPoolExecutor mExecutor = new PausableThreadPoolExecutor(1, 4, 10, TimeUnit.SECONDS, new LinkedBlockingStack<Runnable>());
    private Set<LoadRequest> mActiveRequests = new HashSet<LoadRequest>();
    private BitmapFilter mFilter;
    private static HttpImageManager sInstance = null;

    public static interface OnLoadResponseListener {
        public void onLoadResponse(LoadRequest r, Bitmap data);
        public void onLoadProgress(LoadRequest r, long totalContentSize, long loadedContentSize);
        public void onLoadError(LoadRequest r, Throwable e);
    }
    
    public static class LoadRequest {
    	
        private Uri mUri;
        private String mHashedUri;
        private OnLoadResponseListener mListener;
        private ImageView mImageView;
        private Boolean mIsAnimated;
        private Boolean mIsThumbnailed;
        
        public LoadRequest (Uri uri) {
            this(uri, null, null);
        }


        public LoadRequest(Uri uri, ImageView v){
            this(uri, v, null);
        }


        public LoadRequest(Uri uri, OnLoadResponseListener l){
            this( uri, null, l);
        }


        public LoadRequest(Uri uri, ImageView v, OnLoadResponseListener l){
        	this( uri, v, false, false, l);
        }
        
        public LoadRequest(Uri uri, ImageView v, boolean isThumbnailed, boolean isAnimated, OnLoadResponseListener l){
            if(uri == null) 
                throw new NullPointerException("uri must not be null");

            mUri = uri;
            mImageView = v;
            mListener = l;
            mIsAnimated = isAnimated;
            mIsThumbnailed = isThumbnailed;
//            mHashedUri = computeHashedName(uri.toString());
//            mHashedUri = uri.toString();
            mHashedUri = Integer.toString(uri.hashCode());
        }


        public ImageView getImageView() {
            return mImageView;
        }


        public Uri getUri() {
            return mUri;
        }


        public String getHashedUri () {
            return this.mHashedUri;
        }


        @Override 
        public int hashCode() {
            return mUri.hashCode();
        }
 
        public boolean isAnimated(){
        	return this.mIsAnimated;
        }
        
        public boolean isThumbnailed(){
        	return this.mIsThumbnailed;
        }

        @Override 
        public boolean equals(Object b){
            if(b instanceof LoadRequest)
                return mUri.equals(((LoadRequest)b).getUri());

            return false;
        }

        /* Hex representation of the hash over input name */
        private String computeHashedName (String name) {
            try {
                MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
                digest.update(name.getBytes());
                
                byte[] result = digest.digest();
                BigInteger i = new BigInteger(1,result);
                return String.format("%1$032x", i); 
//                return String.format("%02X%02X%02X%02X%02X%02X%02X%02X%02X%02X%02X%02X%02X%02X%02X%02X",
//                        result[0], result[1], result[2], result[3], result[4], result[5], result[6], result[7],
//                        result[8],result[9], result[10], result[11],result[12], result[13], result[14], result[15]);
            } 
            catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

    }

    
    /**
     * 
     * Give a chance to apply any future processing on the bitmap retrieved from network. 
     */
    public static interface BitmapFilter {
        public Bitmap filter ( final Bitmap in );
    }
    

    ////////HttpImageManager
    private HttpImageManager (MemoryBitmapCache cache,  PersistedBitmapCache persistence ) {
        mCache = cache;
        mPersistence = persistence;
        if (mPersistence == null) {
            throw new IllegalArgumentException (" persistence layer should be specified");
        }
        
        mDefaults = new HashMap<Integer, Drawable>();
    }

    private HttpImageManager ( PersistedBitmapCache persistence ) {
        this(null, persistence);
    }
    
    public static HttpImageManager getInstance(){
    	if(sInstance == null){
    		throw new IllegalStateException(HttpImageManager.class.getSimpleName() + " should be initialized");
    	}
    	return sInstance;
	}

    public static void initialize(MemoryBitmapCache cache,  PersistedBitmapCache persistence){
    	if (sInstance == null){
			sInstance = new HttpImageManager(cache, persistence);
		}
    }
    
    public static void initialize( PersistedBitmapCache persistence ){
    	if (sInstance == null){
			sInstance = new HttpImageManager(persistence);
		}
    }

    public void setDecodingPixelConstraint (int max) {
        mMaxNumOfPixelsConstraint = max;
        mPersistence.setDecodingPixelConstraint(max);
    }
    
    
    public int getDecodingPixelConstraint(){
        return mMaxNumOfPixelsConstraint;
    }
    
    public void setBitmapMemoryCacheSize(int size){
    	if (mCache!=null){
    		mCache.setMaxSize(size);
    		Log.e(TAG, "setBitmapMemoryCacheSize | max size setted : " + mCache.getMaxSize());
    	}else{
    		Log.e(TAG, "setBitmapMemoryCacheSize | max size not setted using default : " + mCache.getMaxSize() );
    	}
    }
    
    public int getBitmapMemoryCacheSize(){
    	int result = 0;
    	if (mCache!=null){
    		result = mCache.getMaxSize();
    	}
    	return result;
    }
    
    
    public void setBitmapFilter (BitmapFilter filter) {
        mFilter = filter;
    }
    
    
    static public MemoryBitmapCache createDefaultMemoryCache() {
        return new MemoryBitmapCache(DEFAULT_CACHE_SIZE);
    }


    public Bitmap loadImage(Uri uri) {
        return loadImage(new LoadRequest(uri));
    }


    /**
     * Nonblocking call, return null if the bitmap is not in cache.
     * @param r
     * @return
     */
    public Bitmap loadImage( LoadRequest r ) {
        if(r == null || r.getUri() == null || TextUtils.isEmpty(r.getUri().toString())) 
            throw new IllegalArgumentException( "null or empty request");

        ImageView iv = r.getImageView();
        if(iv != null){
            synchronized ( iv ) {
                iv.setTag(r.getUri()); // bind URI to the ImageView, to prevent image write-back of earlier requests.
            }
        }

        String key = r.getHashedUri();
        if(mCache != null && mCache.exists(key)) {
            Bitmap bitmap = mCache.loadData(key);
            if (bitmap != null) {
//			      setImageBitmapWithFade(iv, bitmap);
			      iv.setImageBitmap(bitmap);
			}
         // callback listener if any
            fireLoadResponse(r, bitmap);
            return bitmap;
        }
        else { 
            // not ready yet, try to retrieve it asynchronously.
            mExecutor.execute( newRequestCall(r));
            return null;
        }
    }


    ////PRIVATE
    private Runnable newRequestCall(final LoadRequest request) {
        return new Runnable() {

            public void run() {

                // if the request dosen't represent the intended ImageView, do nothing.
                if(request.getImageView() != null) {
                    final ImageView iv = request.getImageView();
                    synchronized ( iv ) {
                        if ( iv.getTag() != request.getUri() ) {
                            if(DEBUG)  Log.d(TAG, "give up loading: " + request.getUri().toString());
                            return;
                        }else{
                    		//try to get ImageView Background
//                			final Drawable imageDrawable = iv.getDrawable();
//                			if(imageDrawable!=null && !mActiveRequests.contains(request)){
//                				final Bitmap defaultBitmap = drawableToBitmap(imageDrawable);
//                				if(DEBUG) Log.e(TAG, "postAtFrontOfQueue for " + iv.getTag());
                				//TODO should not post here if a request is already pending for this url and imageview
//                				mHandler.postAtFrontOfQueue(new Runnable() {
//									
//									@Override
//									public void run() {
//										if(DEBUG) Log.e(TAG, "setImageBitmap for " + iv.getTag());
//										iv.setImageBitmap(defaultBitmap);
//									}
//								});
//                			}
                        }
                    }
                }

                synchronized (mActiveRequests) {
                    // If there's been already request pending for the same URL, we just wait until it is handled.
                    while (mActiveRequests.contains(request)) {
                        try {
                            mActiveRequests.wait();
                        } catch(InterruptedException e) {}
                    }

                    mActiveRequests.add(request);
                }
                
                //Trying to get ImageView Background
                if(request.getImageView() != null) {
                    final ImageView iv = request.getImageView();
                    synchronized ( iv ) {
                        if ( iv.getTag() == request.getUri() ) {
    
                			final Drawable imageDrawable = iv.getDrawable();
                			if(imageDrawable!=null && !mActiveRequests.contains(request)){
                				
                				final Bitmap defaultBitmap = drawableToBitmap(imageDrawable);
                				if(DEBUG) Log.e(TAG, "postAtFrontOfQueue for " + iv.getTag());
                				mHandler.postAtFrontOfQueue(new Runnable() {
									
									@Override
									public void run() {
										if(DEBUG) Log.e(TAG, "setImageBitmap for " + iv.getTag());
										iv.setImageBitmap(defaultBitmap);
//		                				iv.setImageDrawable(imageDrawable);
									}
								});
                				
                			}
                        }
                    }
                }

                Bitmap data = null;
                String key = request.getHashedUri();

                try {
                    //first we lookup memory cache
                    if (mCache != null)
                        data = mCache.loadData(key);

                    if(data == null) {
                        if(DEBUG)  Log.d(TAG, "cache missing " + request.getUri().toString());
                        //then check the persistent storage
                        data = mPersistence.loadData(key);
                        if(data != null) {
                            if(DEBUG)  Log.d(TAG, "found in persistent: " + request.getUri().toString());
                            
                            // load it into memory
                            if (mCache != null)
                                mCache.storeData(key, data);

                            fireLoadProgress(request, 1, 1); // fire progress done
                        }
                        else {
                            // we go to network
                            if(DEBUG)  Log.d(TAG, "go to network " + request.getUri().toString());
                            long millis = System.currentTimeMillis();
                            
                            byte[] binary = null;
                            HttpResponse httpResp = mNetworkResourceLoader.load(request.getUri());

                            if(DEBUG) {
                                Header[] headers = httpResp.getAllHeaders();
                                for (Header header :headers) {
                                    Log.i(TAG, header.toString());
                                }
                            }

                            HttpEntity entity = httpResp.getEntity();
                            if (entity != null) {
                                InputStream responseStream = entity.getContent();
                                try {
                                    Header header = entity.getContentEncoding();
                                    if (header != null && header.getValue() != null && header.getValue().contains("gzip")) {
                                        responseStream =  new GZIPInputStream(responseStream);
                                    }

                                    responseStream = new FlushedInputStream(responseStream); //patch the inputstream
                                    
                                    long contentSize = entity.getContentLength();
                                    binary = readInputStreamProgressively(responseStream, (int)contentSize, request);
//                                    if(request.isThumbnailed()){
//                                    	BitmapFactory.Options opt = new BitmapFactory.Options();				//get a scrubbed version of this bitmap
//                            			opt.inSampleSize = SCRUB_FACTOR;				    
//                                    	data = BitmapFactory.decodeByteArray(binary, 0, binary.length, opt);
//                                    }else{
                                    	data = BitmapUtil.decodeByteArray(binary, mMaxNumOfPixelsConstraint);
//                                    }
//                                    
                                } 
                                finally {
                                    if(responseStream != null) {
                                        try { responseStream.close(); } catch (IOException e) {}
                                    }
                                }
                            }

                            if(data == null) 
                                throw new RuntimeException("data from remote can't be decoded to bitmap");

                            if(DEBUG) Log.d(TAG, "decoded image: " + data.getWidth() + "x" + data.getHeight() );
                            if(DEBUG) Log.d(TAG, "time consumed: " + (System.currentTimeMillis() - millis));

                            //apply filter(s)
                            if (mFilter != null) {
                                try {
                                    Bitmap newData = mFilter.filter(data);
                                    if (newData != null) data = newData;
                                }
                                catch (Throwable e) {}
                            }
                            
                            // load it into memory
                            if (mCache != null)
                                mCache.storeData(key, data);

                            // persist it. Save the file as-is, preserving the format.
                            if(binary!=null)
                            	mPersistence.storeData(key, binary);
                        }
                    }

                    if(data != null && request.getImageView() != null) {
                        final Bitmap finalData = data;
                        final ImageView iv = request.getImageView();

                        synchronized ( iv ) {
                            if ( iv.getTag() == request.getUri() ) {
                                mHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if ( iv.getTag() == request.getUri()) {
                                        	
//                                			Drawable imageDrawable = iv.getDrawable();
//                                			if(imageDrawable!=null){
//                                				
////                                				Drawable defaultBitmap = drawableToBitmap(imageDrawable);
//                                				iv.setImageDrawable(imageDrawable);
//                                			}
                                        	
                                        	if(DEBUG) Log.e(TAG, "setImageBitmapWithFade for request " + request.getUri());
                                        	if(request.isAnimated())
                                        		setImageBitmapWithFade(iv, finalData);
                                        	else{
                                        		iv.setImageBitmap(finalData);
                                        	}
                                        }
                                    }
                                });
                            }
                        }
                    }

                    // callback listener if any
                    fireLoadResponse(request, data);
                }
                catch (Throwable e) {
                    fireLoadFailure(request, e);
//                    if(DEBUG) 
                    	Log.e(TAG, "error handling request " + request.getUri(), e);
                }
                finally{
                    synchronized (mActiveRequests) {
                        mActiveRequests.remove(request);
                        mActiveRequests.notifyAll();  // wake up pending requests who's querying the same URL. 
                    }

                    if (DEBUG) Log.d(TAG, "finished request for: " + request.getUri());
                }
            }
        };
    }


    /**
     * Make memory cache empty, release all bitmap reference held. 
     */
    public void emptyCache () {
        if ( mCache != null) 
            mCache.clear();
    }


    /**
     * Remove the persistent data. This is a blocking call. 
     */
    public void emptyPersistence () {
        if (mPersistence != null)
            mPersistence .clear();
    }


    ////////PRIVATE
    private byte[] readInputStreamProgressively (InputStream is, int totalSize, LoadRequest r) 
            throws IOException {

        fireLoadProgress(r, 3, 1); // compensate 33% of total time, which was consumed by establishing HTTP connection

        if (totalSize > 0) { // content length is known
            byte[] data = new byte[totalSize];
            int offset = 0;
            int readed;

            while (offset < totalSize && (readed = is.read(data, offset, totalSize - offset)) != -1) {
                offset += readed;
                fireLoadProgress(r, totalSize, (totalSize + offset) >> 1 );
            }

            if (offset != totalSize)
                throw new IOException("Unexpected readed size. current: " + offset + ", excepted: " + totalSize);
            
            return data;

        }
        else if (totalSize == 0) {
            return new byte[0];
        }
        else {
            // content length is unknown
            byte[] buf = new byte[1024];
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            long count = 0;
            int readed;
            while ((readed = is.read(buf)) != -1) {
                output.write(buf, 0, readed);
                count += readed;
            }

            fireLoadProgress(r, count, count);

            if (count > Integer.MAX_VALUE) 
                throw new IOException("content too large: " + (count / (1024 * 1024 )) + " M");

            return output.toByteArray();
        }
    }


    private void fireLoadResponse(final LoadRequest r, final Bitmap image) {
    	if(DEBUG) Log.e(TAG, "fireLoadResponse :" + r.getUri());
    	
        if ( r.mListener != null) {
            try {
                r.mListener.onLoadResponse(r, image);
            }
            catch (Throwable t) {
            	if(DEBUG) t.printStackTrace();
            }
        }else{
        	if(DEBUG) Log.e(TAG, "fireLoadResponse :" + r.getUri() + " no listener");
        }
    }


    private void fireLoadProgress(final LoadRequest r, final long totalContentSize, final long loadedContentSize) {
        if(DEBUG) Log.e(TAG, "fireLoadProgress :" + r.getUri());
    	
    	if ( r.mListener != null) {
            try {
                r.mListener.onLoadProgress(r, totalContentSize, loadedContentSize);
            }
            catch (Throwable t) {
            	if(DEBUG) t.printStackTrace();
            }
        }else{
        	if(DEBUG) Log.e(TAG, "fireLoadProgress :" + r.getUri() + " no listener");
        }
    }
    
    
    private void fireLoadFailure(final LoadRequest r, final Throwable e) {
    	if(DEBUG) Log.e(TAG, "fireLoadFailure :" + r.getUri());
    	
        if ( r.mListener != null) {
            try {
                r.mListener.onLoadError(r, e);
            }
            catch (Throwable t) {
            	if(DEBUG) t.printStackTrace();
            }
        }else{
        	if(DEBUG) Log.e(TAG, "fireLoadFailure :" + r.getUri() + " no listener");
        }
    }

    /*
     * The BitmapFactory.decodeStream() method fails to read a JPEG image (i.e.
     * returns null) if the skip() method of the used InputStream skip less bytes
     * than the required amount.
     * 
     * author: public domain
     */
    private static class FlushedInputStream extends FilterInputStream {
        public FlushedInputStream(InputStream inputStream) {
            super(inputStream);
        }

        
        @Override
        public long skip(long n) throws IOException {
            long totalBytesSkipped = 0L;
            while (totalBytesSkipped < n) {
                long bytesSkipped = in.skip(n - totalBytesSkipped);
                if (bytesSkipped == 0L) {
                    int byt = read();
                    if (byt < 0) {
                        break;  // we reached EOF
                    } else {
                        bytesSkipped = 1; // we read one byte
                    }
                }
                totalBytesSkipped += bytesSkipped;
            }
            return totalBytesSkipped;
        }
    }
    
	@TargetApi(Build.VERSION_CODES.DONUT)
	private void setImageBitmapWithFade(final ImageView imageView, final Bitmap bitmap) {
		Resources resources = imageView.getResources();
		BitmapDrawable bitmapDrawable = new BitmapDrawable(resources,bitmap);
		setImageDrawableWithFade(imageView, bitmapDrawable);
	}
	        
	private void setImageDrawableWithFade(final ImageView imageView, final Drawable drawable) {
		Drawable currentDrawable = imageView.getDrawable();
		if (currentDrawable != null) {
			Drawable [] arrayDrawable = new Drawable[2];
			arrayDrawable[0] = currentDrawable;
			arrayDrawable[1] = drawable;
			TransitionDrawable transitionDrawable = new TransitionDrawable(arrayDrawable);
			transitionDrawable.setCrossFadeEnabled(true);
			imageView.setImageDrawable(transitionDrawable);
			transitionDrawable.startTransition(250);
		} else {
			imageView.setImageDrawable(drawable);
		}
	}
	
	public void PickupDefaultImage(int resourceId, ImageView imageView){
		Drawable drawable = null;
		if(!mDefaults.containsKey(resourceId)){
			mDefaults.put(resourceId, imageView.getResources().getDrawable(resourceId));
		}
		drawable = mDefaults.get(resourceId);
		if(DEBUG) Log.e(TAG, "PickupDefaultImage for " + resourceId + " : " + drawable);
		imageView.setImageDrawable(drawable);
	}
	
	public void pause(){
		mExecutor.pause();
	}
	
	public void resume(){
		mExecutor.resume();
	}
	
	public double getImageRatioOnly(String url){
		double ratio = 0;
        HttpResponse httpResp;
		try {
			httpResp = mNetworkResourceLoader.load(Uri.parse(url));
			HttpEntity entity = httpResp.getEntity();
	        if (entity != null) {
	            InputStream responseStream = entity.getContent();
	            try {
	                Header header = entity.getContentEncoding();
	                if (header != null && header.getValue() != null && header.getValue().contains("gzip")) {
	                    responseStream =  new GZIPInputStream(responseStream);
	                }

	                responseStream = new FlushedInputStream(responseStream); //patch the inputstream
	                
	                ratio = BitmapUtil.getRatio(responseStream);
	            } 
	            finally {
	                if(responseStream != null) {
	                    try { responseStream.close(); } catch (IOException e) {}
	                }
	            }
	        }
		} catch (IOException e) {
			e.printStackTrace();
		}
        
		return ratio;
	}
	
	public BitmapFactory.Options getImageOptionsOnly(String url){
		BitmapFactory.Options options = null;
        HttpResponse httpResp;
		try {
			httpResp = mNetworkResourceLoader.load(Uri.parse(url));
			HttpEntity entity = httpResp.getEntity();
	        if (entity != null) {
	            InputStream responseStream = entity.getContent();
	            try {
	                Header header = entity.getContentEncoding();
	                if (header != null && header.getValue() != null && header.getValue().contains("gzip")) {
	                    responseStream =  new GZIPInputStream(responseStream);
	                }

	                responseStream = new FlushedInputStream(responseStream); //patch the inputstream
	                
	                options = BitmapUtil.getImageOptions(responseStream);
	            } 
	            finally {
	                if(responseStream != null) {
	                    try { responseStream.close(); } catch (IOException e) {}
	                }
	            }
	        }
		} catch (IOException e) {
			e.printStackTrace();
		}
        
		return options;
	}
	
	public static Bitmap drawableToBitmap (Drawable drawable) {
	    if (drawable instanceof BitmapDrawable) {
	        return ((BitmapDrawable)drawable).getBitmap();
	    }
	    Bitmap bitmap = null;
	    if(drawable.getIntrinsicWidth()>0 && drawable.getIntrinsicHeight()>0){
	    	bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Config.ARGB_8888);
	    	Canvas canvas = new Canvas(bitmap); 
	    	drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
	    	drawable.draw(canvas);
	    }

	    return bitmap;
	}
}

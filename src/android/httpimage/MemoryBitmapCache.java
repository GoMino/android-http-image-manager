package android.httpimage;

import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import android.graphics.Bitmap;
import android.util.Log;


/**
 * Basic implementation of BitmapCache 
 * 
 * @author zonghai@gmail.com
 * @author abezzarg@gmail.com
 */
public class MemoryBitmapCache implements BitmapCache{
    
    private static class CacheEntry {
        public Bitmap data;
        public int nUsed;
        public long timestamp;
    }
    
    
    private static final String TAG = MemoryBitmapCache.class.getSimpleName();
    private static final boolean DEBUG = false;
    
    private int mMaxSize;
//    private Map<String, SoftReference<CacheEntry>> mMap = new ConcurrentHashMap<String, SoftReference<CacheEntry>> ();
    private Map<String, CacheEntry> mMap = new ConcurrentHashMap<String, CacheEntry> ();

    /**
     * max number of resource this cache contains
     * @param size
     */
    public MemoryBitmapCache (int size) {
        this.mMaxSize = size;
    }
    
    public void setMaxSize(int maxSize){
    	mMaxSize = maxSize;
    } 
    
    public int getMaxSize(){
    	return mMaxSize;
    } 
    
    @Override
    public synchronized boolean exists(String key){
       return mMap.get(key) != null;
    }

    
    @Override
    public synchronized void invalidate(String key){
//        SoftReference<CacheEntry> entry = mMap.get(key);
        CacheEntry entry = mMap.get(key);
//        if(entry.get() != null && !entry.isEnqueued()){
        	// CacheEntry found in soft cache
//        	CacheEntry e = entry.get();
//            Bitmap data = e.data;
//          data.recycle(); // we are only relying on GC to reclaim the memory
//        }
        mMap.remove(key);
        if(DEBUG) Log.v(TAG,"[invalidate]" + key + " is invalidated from the cache");
    }

    
    @Override
    public synchronized void clear(){
         for ( String key : mMap.keySet()) {
             invalidate(key);
         }
    }

    
    /**
     * If the cache storage is full, return an item to be removed. 
     * Will remove garbage collected items if some has been found while looking for eldest entry.
     * Default strategy:  oldest out: O(n)
     * 
     * @return item key
     */
    protected synchronized String findItemToInvalidate() {
//        Map.Entry<String, SoftReference<CacheEntry>> out = null;
        Map.Entry<String, CacheEntry> out = null;
//        for(Map.Entry<String, SoftReference<CacheEntry>> e : mMap.entrySet()){
        for(Map.Entry<String, CacheEntry> e : mMap.entrySet()){

//          	if( e.getValue().get() == null ||  e.getValue().isEnqueued()){
//        		//item has been garbage collected
//        		invalidate(e.getKey());
//        		continue;
//        	}
          	
//        	CacheEntry candidate = e.getValue().get();
        	CacheEntry candidate = e.getValue();
//            if( out == null || (out.getValue().get()!=null && candidate.timestamp < out.getValue().get().timestamp) ) {
            if( out == null || (out.getValue()!=null && candidate.timestamp < out.getValue().timestamp) ) {
                out = e;
            }
        }
        return (out!=null)?out.getKey():null;
    }

    
    @Override
    public synchronized Bitmap loadData(String key) {
        if(!exists(key)) {
            return null;
        }
//        SoftReference<CacheEntry> softRef =  mMap.get(key);
//        CacheEntry res = softRef.get();
        CacheEntry res = mMap.get(key);
//        if(softRef.get() == null || softRef.isEnqueued()){
        if(res == null){
        	// Soft reference has been Garbage Collected
        	invalidate(key);
        	return null;
        }else{
        	// CacheEntry found in soft cache
       	 	res.nUsed++;
            res.timestamp = System.currentTimeMillis();
            return res.data;
        }
       
    }


    @Override
    public synchronized void storeData(String key, Object data) {
        if(this.exists(key)) {
            return;
        }
        CacheEntry res = new CacheEntry();
        res.nUsed = 1;
        res.timestamp = System.currentTimeMillis();
        res.data = (Bitmap)data;
        
        //if the number exceeds, move an item out 
        //to prevent the storage from increasing indefinitely.
        
        if(DEBUG)
        	Log.v(TAG, "[storeData] maxsize:" + mMaxSize + " current size:" + mMap.size());
        
        if(mMap.size() >= mMaxSize) {
            String outkey = this.findItemToInvalidate();
            
            if(DEBUG)
            	Log.v(TAG, "[storeData] size : " + mMap.size() + " outkey : " + outkey);
            
            this.invalidate(outkey);
        }
        
        
        
//        mMap.put(key, new SoftReference<CacheEntry>(res));
        mMap.put(key, res);
    }

}

package android.httpimage;

import java.util.HashMap;
import java.util.Map;

import android.graphics.Bitmap;
import android.util.Log;


/**
 * Basic implementation of BitmapCache 
 * 
 * @author zonghai@gmail.com
 */
public class MemoryBitmapCache implements BitmapCache{
    
    private static class CacheEntry {
        public Bitmap data;
        public int nUsed;
        public long timestamp;
    }
    
    
    private static final String TAG = MemoryBitmapCache.class.getSimpleName();
    private static final boolean DEBUG = true;
    
    private int mMaxSize;
    private HashMap<String, CacheEntry> mMap = new HashMap<String, CacheEntry> ();
    

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
        CacheEntry e = mMap.get(key);
        Bitmap data = e.data;
//        data.recycle(); // we are only relying on GC to reclaim the memory
        mMap.remove(key);
        if(DEBUG) Log.d(TAG, key + " is invalidated from the cache");
    }

    
    @Override
    public synchronized void clear(){
         for ( String key : mMap.keySet()) {
             invalidate(key);
         }
    }

    
    /**
     * If the cache storage is full, return an item to be removed. 
     * 
     * Default strategy:  oldest out: O(n)
     * 
     * @return item key
     */
    protected synchronized String findItemToInvalidate() {
        Map.Entry<String, CacheEntry> out = null;
        for(Map.Entry<String, CacheEntry> e : mMap.entrySet()){
            if( out == null || e.getValue().timestamp < out.getValue().timestamp) {
                out = e;
            }
        }
        return out.getKey();
    }

    
    @Override
    public synchronized Bitmap loadData(String key) {
        if(!exists(key)) {
            return null;
        }
        CacheEntry res = mMap.get(key);
        res.nUsed++;
        res.timestamp = System.currentTimeMillis();
        return res.data;
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
        	Log.e(TAG, "maxsize : " + mMaxSize);
        
        if(mMap.size() >= mMaxSize) {
            String outkey = this.findItemToInvalidate();
            
            if(DEBUG)
            	Log.e(TAG, "size : " + mMap.size() + "outkey : " + outkey);
            
            this.invalidate(outkey);
        }
        
        
        
        mMap.put(key, res);
    }

}

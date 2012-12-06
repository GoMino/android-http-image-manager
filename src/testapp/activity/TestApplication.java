package testapp.activity;

import android.httpimage.*;

public class TestApplication extends android.app.Application {

	public static final String BASEDIR = "/sdcard/httpimage";
	
	
	@Override
	public void onCreate() {
		super.onCreate();

		// init HttpImageManager manager.
		
		HttpImageManager.initialize(HttpImageManager.createDefaultMemoryCache(), new FileSystemPersistence(BASEDIR));
		mHttpImageManager= HttpImageManager.getInstance();
	}

	
	public HttpImageManager getHttpImageManager() {
		return mHttpImageManager;
	}


	//////PRIVATE
	private HttpImageManager mHttpImageManager; 
}

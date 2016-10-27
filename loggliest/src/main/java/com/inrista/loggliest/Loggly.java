/**
 * Copyright 2015 Inrista
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */
package com.inrista.loggliest;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import retrofit.RestAdapter;
import retrofit.RetrofitError;
import retrofit.client.Response;
import retrofit.http.Body;
import retrofit.http.Headers;
import retrofit.http.POST;
import retrofit.http.Path;
import retrofit.mime.TypedByteArray;
import retrofit.mime.TypedInput;

/**
 * Android Loggly client using the HTTP/S bulk api.
 * <p>
 * First, initialize the global singleton instance using the 
 * {@link Builder} provided by {@link #with(android.content.Context, String)}.
 * You must provide your Loggly token. A minimal setup looks like this:   
 * <pre>
 * final String TOKEN = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
 * Loggly.with(this, TOKEN).init();
 * </pre>
 * Log a message:
 * <pre>
 * Loggly.i("stringfield", "test"); 
 * Loggly.i("numberfield", 123);
 * </pre>
 * Log messages are formatted as json messages as expected by Loggly. Along with
 * the json field and value provided by calling one of the log functions, a "level"
 * field is appended with the current log level (verbose, debug, info, warning, error),
 * and a "timestamp" set according to when the message was logged. Also, a set of
 * various default fields can be appended, see {@link Builder#appendDefaultInfo(boolean)}
 * <p>
 * The log messages are saved in the internal app storage and uploaded to Loggly  
 * in bulk when either one of these conditions are fulfilled:
 * <p>
 * <ul>
 * <li>1. The time since the last upload exceeds the 
 * {@link Builder#uploadIntervalSecs(int seconds)} setting</li>
 * <li>2. The number of log messages since the last upload exceeds the 
 * {@link Builder#uploadIntervalLogCount(int count)} setting</li>
 * <li>3. A new {@link Loggly} instance is created and there are (old) 
 * messages that have not been uploaded</li>
 * <li>4. {@link #forceUpload()} is called</li>
 * </ul>
 * <p>
 * A typical configuration that sets upload intervals to 30mins, max number of messages
 * between uploads to 1000 messages, limits the internal app storage log buffer to 500k 
 * and appends the default info fields along with a custom language field looks like this:
 * <pre>
 * Loggly.with(this, TOKEN)
 *     .appendDefaultInfo(true)
 *     .uploadIntervalLogCount(1000)
 *     .uploadIntervalSecs(1800)
 *     .maxSizeOnDisk(500000)
 *     .appendStickyInfo("language", currentLanguage)
 *     .init();
 * </pre>
 * <b>NOTE:</b> Short upload intervals will have a negative effect on the battery life. 
 *
 */
public class Loggly {

    private static final String LOGGLY_DEFAULT_URL = "https://logs-01.loggly.com";
    private static final String LOG_FOLDER = "logglylogs";
    private static final int LOGGLY_MAX_POST_SIZE = 1000000;
    private static final int MAX_SIZE_ON_DISK_DEFAULT = 10000000;
    private static final int MAX_SIZE_ON_DISK_MIN = 1000;
    private static final int UPLOAD_INTERVAL_SECS_MIN = 5;
    private static final int UPLOAD_INTERVAL_SECS_DEFAULT = 900;
    private static final int UPLOAD_INTERVAL_LOG_COUNT_MIN = 1;
    private static final int UPLOAD_INTERVAL_LOG_COUNT_DEFAULT = 500;
    private static final int IDLE_SECS_MIN = 0;
    private static final int IDLE_SECS_DEFAULT = 1200;
    private static final boolean APPEND_DEFAULT_INFO_DEFAULT = true;
    private static final String UPDATE_STICKY_INFO_MSG = "update-sticky-info";
    private static final String THREAD_NAME = "loggliest";

    private static volatile Loggly mInstance;
    private static volatile Builder mBuilder;
    
    private static Context mContext;
    private static String mToken;
    private static String mLogglyTag;
    private static int mUploadIntervalSecs;
    private static int mUploadIntervalLogCount;
    private static int mIdleSecs;
    private static boolean mAppendDefaultInfo;
     
    private static IBulkLog mBulkLogService;
    private static Thread mThread = null;
    private static LinkedBlockingQueue<JSONObject> mLogQueue = new LinkedBlockingQueue<JSONObject>();
    private static long mLastUpload = 0;
    private static long mLastLog = 0;
    private static int mLogCounter = 0;
    private static File mRecentLogFile;
    private static SimpleDateFormat mDateFormat;
    private static String mAppVersionName = "";
    private static int mAppVersionCode = 0;
    private static HashMap<String, String> mStickyInfo = null;
    private static int mMaxSizeOnDisk = 0;
    
    /**
     * Configures the {@link Loggly} instance.
     *
     */
    public static class Builder {
        private final Context context;
        private final String token;
        private String logglyTag;
        private String logglyUrl;
        private int uploadIntervalSecs = UPLOAD_INTERVAL_SECS_DEFAULT;
        private int uploadIntervalLogCount = UPLOAD_INTERVAL_LOG_COUNT_DEFAULT;
        private int idleSecs = IDLE_SECS_DEFAULT;
        private boolean appendDefaultInfo = APPEND_DEFAULT_INFO_DEFAULT;
        private HashMap<String, String> stickyInfo = new HashMap<String, String>();
        private int maxSizeOnDisk = MAX_SIZE_ON_DISK_DEFAULT;
        
        private Builder(Context context, String token) {
            this.token = token;
            this.context = context.getApplicationContext();
        }
        
        /**
         * Set the Loggly tag that the log messages are tagged with. 
         * If not specified this defaults to the package name.
         * @param logglyTag The Loggly tag
         */
        public Builder tag(String logglyTag) {
            this.logglyTag = logglyTag;
            return this;
        }

        /**
         * Set the Loggly REST api endpoint. If not specified this defaults
         * to the HTTPS endpoint.
         * @param logglyUrl The api endpoint url
         */
        public Builder url(String logglyUrl) {
            this.logglyUrl = logglyUrl;
            return this;
        }
        
        /**
         * Set the interval between Loggly log uploads. Note that short intervals
         * drains the battery. If not specified this defaults to 900 (15min). Also see 
         * {@link #uploadIntervalLogCount(int count)}, when either condition is
         * fulfilled the logs will be uploaded.
         * @param seconds Time in seconds
         */
        public Builder uploadIntervalSecs(int seconds) {
            this.uploadIntervalSecs = seconds;
            return this;
        }

        /**
         * Set the interval between Loggly log uploads. Note that small intervals
         * drains the battery if you log many messages. If not specified this 
         * defaults to 500. Also see {@link #uploadIntervalSecs(int seconds)}, 
         * when either condition is fulfilled the logs will be uploaded.
         * @param count Number of logs
         */
        public Builder uploadIntervalLogCount(int count) {
            this.uploadIntervalLogCount = count;
            return this;
        }

        /**
         * Set the time before the background log processing stops if no messages have
         * been logged. This processing is automatically resumed when a new message is logged.
         * This defaults to 1200 seconds (20min) if not specified. 
         * @param seconds Time in seconds
         * @return
         */
        public Builder idleSecs(int seconds) {
            this.idleSecs = seconds;
            return this;
        }
        
        /**
         * Set whether four default key-value pairs should be appended to each logged message:
         * <ul>
         * <li>"appversionname" - verisonName from the manifest</li>
         * <li>"appversioncode" - versionCode from the manifest</li>
         * <li>"devicemodel" - the android.os.Build.MODEL constant</li>
         * <li>"androidversioncode" - the android.os.Build.VERSION.SDK_INT constant</li>
         * </ul>
         * @param append Set to true to append default info
         */
        public Builder appendDefaultInfo(boolean append) {
            this.appendDefaultInfo = append;
            return this;
        }
        
        /**
         * Append a key-value pair to each logged message. Chain multiple appendStickyInfo
         * to append multiple key-value pairs.
         * @param key The Loggly json field
         * @param value The value
         */
        public Builder appendStickyInfo(String key, String value) {
            this.stickyInfo.put(key, value);
            return this;
        }
        
        /**
         * Configure the buffer size for log messages that are not yet uploaded.
         * The oldest messages will be dropped if more info is logged than what 
         * fits in the buffer. 
         * @param size Max size in bytes
         * @return
         */
        public Builder maxSizeOnDisk(int size) {
            this.maxSizeOnDisk = size;
            return this;
        }
        
        /**
         * Create the {@link Loggly} instance.
         */
        public Loggly init() {
            if(logglyTag == null)
                logglyTag = context.getPackageName();
            
            if(logglyUrl == null) 
                logglyUrl = LOGGLY_DEFAULT_URL;
            
            if(uploadIntervalSecs < UPLOAD_INTERVAL_SECS_MIN)
                uploadIntervalSecs = UPLOAD_INTERVAL_SECS_MIN;
            
            if(uploadIntervalLogCount < UPLOAD_INTERVAL_LOG_COUNT_MIN)
                uploadIntervalLogCount = UPLOAD_INTERVAL_LOG_COUNT_MIN;

            if(idleSecs < IDLE_SECS_MIN)
                idleSecs = IDLE_SECS_MIN;
            
            if(maxSizeOnDisk < MAX_SIZE_ON_DISK_MIN)
                maxSizeOnDisk = MAX_SIZE_ON_DISK_MIN;

            if (mInstance == null) {
                synchronized (Loggly.class) {
                    if (mInstance == null) {
                        mInstance = new Loggly(context, token, logglyTag, logglyUrl, 
                                uploadIntervalSecs, uploadIntervalLogCount, idleSecs, 
                                appendDefaultInfo, stickyInfo, maxSizeOnDisk);
                    }
                }
            }
                        
            return mInstance;
        }
    }
    
    /**
     * Creates a {@link Builder} instance.
     * @param context Context
     * @param token Loggly token
     * @return A {@link Builder} instance
     */
    public static Builder with(Context context, String token) {
        if (mBuilder == null) {
            synchronized (Loggly.class) {
                if (mBuilder == null) {
                    mBuilder = new Builder(context, token);
                }
            }
        }
        
        return mBuilder; 
    }
    
    private Loggly(Context context, String token, String logglyTag, String logglyUrl, 
            int uploadIntervalSecs, int uploadIntervalLogCount, int idleSecs, 
            boolean appendDefaultInfo, HashMap<String, String> stickyInfo, int maxSizeOnDisk) {
        
        mContext = context;
        mToken = token;
        mLogglyTag = logglyTag;
        mUploadIntervalSecs = uploadIntervalSecs;
        mUploadIntervalLogCount = uploadIntervalLogCount;
        mIdleSecs = idleSecs;
        mAppendDefaultInfo = appendDefaultInfo;
        mStickyInfo = stickyInfo;
        mMaxSizeOnDisk = maxSizeOnDisk;
                
        mRecentLogFile = recentLogFile();
        mDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
        
        PackageManager manager = context.getPackageManager();
        try {
            PackageInfo info = manager.getPackageInfo(context.getPackageName(), 0);
            mAppVersionName = info.versionName;
            mAppVersionCode = info.versionCode;
        } catch (NameNotFoundException e) {}
        
        RestAdapter restAdapter = new RestAdapter.Builder()
        .setEndpoint(logglyUrl)
        .build();
        mBulkLogService = restAdapter.create(IBulkLog.class);
        
        start();
    }    

    private static synchronized void start() {
        if(mThread != null && mThread.isAlive())
            return;
        
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                mThread.setName(THREAD_NAME);
                
                List<JSONObject> logBatch = new ArrayList<JSONObject>();
                while(true) {
                    try {
                        if (mThread.isInterrupted()) {
                            throw new InterruptedException();
                        }

                        JSONObject msg = mLogQueue.poll(10, TimeUnit.SECONDS);
                        if(msg != null) {
                            logBatch.add(msg);
                            while ((msg = mLogQueue.poll()) != null)
                                logBatch.add(msg);
                        }

                        long now = SystemClock.elapsedRealtime() / 1000;
                        
                        if(!logBatch.isEmpty()) {
                            mLastLog = now;
                            mLogCounter += logBatch.size();
                            logToFile(logBatch);
                            logBatch.clear();
                        }
                        
                        if((now - mLastUpload) >= mUploadIntervalSecs 
                                || mLogCounter >= mUploadIntervalLogCount) {
                            postLogs();
                        }
                        
                        if(((now - mLastLog) >= mIdleSecs) && mIdleSecs > 0 && mLastLog > 0) {
                            mThread.interrupt();
                        }
                        
                    } catch (InterruptedException e) {
                        mLogQueue.drainTo(logBatch);
                        logToFile(logBatch);
                        postLogs();
                        return;
                    }
                }
            }
        });
         
        mThread.start();
    }
        
    private static void log(JSONObject jsonObject) {
        if(!mThread.isAlive())
            start();
        
        mLogQueue.offer(jsonObject);
    }

    private static void log(String key, Object msg, String level, long time) {
        JSONObject json = new JSONObject();
        try {
            json.put("timestamp", time);
            json.put("level", level);
            json.put(key, msg);
            log(json);
        } catch (JSONException e) {}
    }
    
    /**
     * Log a verbose message.
     * @param key Loggly json field
     * @param msg The log message, either a JSONObject, String, Boolean, Integer, Long or Double
     */
    public static void v(String key, Object msg) {
        log(key, msg, "verbose", System.currentTimeMillis());
    }        

    /**
     * Log a verbose message and an exception.
     * @param key Loggly json field
     * @param msg The log message
     * @param tr The exception to log
     */
    public static void v(String key, String msg, Throwable tr) {
        v(key, msg + '\n' + Log.getStackTraceString(tr));
    }

    /**
     * Log a debug message.
     * @param key Loggly json field
     * @param msg The log message, either a JSONObject, String, Boolean, Integer, Long or Double
     */    
    public static void d(String key, Object msg) {
        log(key, msg, "debug", System.currentTimeMillis());
    }        

    /**
     * Log a debug message and an exception.
     * @param key Loggly json field
     * @param msg The log message
     * @param tr The exception to log
     */    
    public static void d(String key, String msg, Throwable tr) {
        d(key, msg + '\n' + Log.getStackTraceString(tr));
    }
    
    /**
     * Log an info message.
     * @param key Loggly json field
     * @param msg The log message, either a JSONObject, String, Boolean, Integer, Long or Double
     */    
    public static void i(String key, Object msg) {
        log(key, msg, "info", System.currentTimeMillis());
    }        
    
    /**
     * Log an info message and an exception.
     * @param key Loggly json field
     * @param msg The log message
     * @param tr The exception to log
     */        
    public static void i(String key, String msg, Throwable tr) {
        i(key, msg + '\n' + Log.getStackTraceString(tr));
    }

    /**
     * Log a warning message.
     * @param key Loggly json field
     * @param msg The log message, either a JSONObject, String, Boolean, Integer, Long or Double
     */        
    public static void w(String key, Object msg) {
        log(key, msg, "warning", System.currentTimeMillis());
    }        
    
    /**
     * Log a warning message and an exception.
     * @param key Loggly json field
     * @param msg The log message
     * @param tr The exception to log
     */            
    public static void w(String key, String msg, Throwable tr) {
        w(key, msg + '\n' + Log.getStackTraceString(tr));
    }
    
    /**
     * Log an error message.
     * @param key Loggly json field
     * @param msg The log message, either a JSONObject, String, Boolean, Integer, Long or Double
     */        
    public static void e(String key, Object msg) {
        log(key, msg, "error", System.currentTimeMillis());
    }

    /**
     * Log an error message and an exception.
     * @param key Loggly json field
     * @param msg The log message
     * @param tr The exception to log
     */            
    public static void e(String key, String msg, Throwable tr) {
        e(key, msg + '\n' + Log.getStackTraceString(tr));
    }
    
    /**
     * Force an upload of all log messages that have not yet been uploaded.
     * This will block the current thread, don't call from the UI thread.
     * <p>
     * Note that all log messages will be uploaded every time a new Loggly
     * instance is created, so this is just to provide a point of 
     * synchronization if necessary. 
     */
    public static synchronized void forceUpload() {
        mThread.interrupt();
        try {
            mThread.join();
        } catch (InterruptedException e) {}
    }
    
    /**
     * Set or reset a key-value pair that is appended to each Loggly json log message.
     * @param key The Loggly json field
     * @param msg The message 
     */
    public static void setStickyInfo(String key, String msg) {
        JSONObject json = new JSONObject();
        try {
            json.put(UPDATE_STICKY_INFO_MSG, true);
            json.put("key", key);
            json.put("msg", msg);
            log(json);
        } catch (JSONException e) {}
    }
    
    private static File recentLogFile() {
        File dir = mContext.getDir(LOG_FOLDER, Context.MODE_PRIVATE);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }

        File file = files[0];
        for (int i = 1; i < files.length; i++) {
            if (file.lastModified() < files[i].lastModified()) {
                file = files[i];
            }            
        }
        return file;

    }
    
    private static File oldestLogFile() {
        File dir = mContext.getDir(LOG_FOLDER, Context.MODE_PRIVATE);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }

        File file = files[0];
        for (int i = 1; i < files.length; i++) {
            if (file.lastModified() > files[i].lastModified()) {
                file = files[i];
            }            
        }
        return file;
    }
    
    private static File createLogFile() {
        File dir = mContext.getDir(LOG_FOLDER, Context.MODE_PRIVATE);
        File logFile = new File(dir, Long.toString(System.currentTimeMillis()));
        return logFile;
    }
    
    private static void logToFile(List<JSONObject> msgBatch) {
        if(msgBatch.isEmpty())
            return;
        
        try {
            
            File oldest = oldestLogFile();
            if (oldest != null) {
                // Size of logs on disk
                File dir = mContext.getDir(LOG_FOLDER, Context.MODE_PRIVATE);
                long totalSize = 0;
                File[] logFiles = dir.listFiles();
                for (File logFile : logFiles)
                    totalSize += logFile.length();

                // Check if size of logs on disk exceeds the limit, drop
                // oldest messages in this case
                if(totalSize > mMaxSizeOnDisk) {
                    int numFiles = logFiles.length;
                    if(numFiles <= 1)
                        return;

                    oldest.delete();
                }
            }
            
            // Create a new log file if necessary
            if(mRecentLogFile == null || mRecentLogFile.length() > LOGGLY_MAX_POST_SIZE)
                mRecentLogFile = createLogFile();
            
            PrintStream ps = new PrintStream(new FileOutputStream(mRecentLogFile, true));            
            for(JSONObject msg : msgBatch) {
                try {
                    if(msg.has(UPDATE_STICKY_INFO_MSG)) {
                        mStickyInfo.put(msg.getString("key"), msg.getString("msg"));
                        continue;
                    }
                    
                    // Reformat timestamp to ISO-8601 as expected by Loggly
                    long timestamp = msg.getLong("timestamp");
                    msg.remove("timestamp");
                    msg.put("timestamp", mDateFormat.format(timestamp));
                    
                    // Append default info
                    if(mAppendDefaultInfo) {
                        msg.put("appversionname", mAppVersionName);
                        msg.put("appversioncode", Integer.toString(mAppVersionCode));
                        msg.put("devicemodel", android.os.Build.MODEL);
                        msg.put("androidversioncode", Integer.toString(android.os.Build.VERSION.SDK_INT));
                    }
                    
                    // Append sticky info
                    if(!mStickyInfo.isEmpty()) {
                        for(String key : mStickyInfo.keySet())
                            msg.put(key, mStickyInfo.get(key));
                    }

                } catch (JSONException e) {}
                
                ps.println(msg.toString().replace("\n", "\\n"));
            }
            
            ps.close();
        } catch (FileNotFoundException e) {}
    }
    
    private interface IBulkLog {
        @Headers("content-type:application/json")
        @POST("/bulk/{token}/tag/{tag}")
        Response log(@Path("token") String token, @Path("tag") String logtag, @Body TypedInput body);
    }
    
    private static void postLogs() {
        mLastUpload = SystemClock.elapsedRealtime() / 1000;
        mLogCounter = 0;

        File dir = mContext.getDir(LOG_FOLDER, Context.MODE_PRIVATE);
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        for (File logFile : files) {
            StringBuilder builder = new StringBuilder();
            
            try {
                // Read log files and build body of newline-separated json log entries                
                FileInputStream fis = new FileInputStream(logFile);
                BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                    builder.append('\n');
                }
                reader.close();
            } catch (FileNotFoundException e) {
                return;
            } catch (IOException e) {
                return;
            }

            String json = builder.toString();
            if (json.isEmpty())
                return;
            
            try {
                // Blocking POST
                TypedInput body = new TypedByteArray("application/json", json.getBytes());
                Response answer = mBulkLogService.log(mToken, mLogglyTag, body);

                // Successful post
                if (answer.getStatus() == 200) {
                    logFile.delete();
                    mRecentLogFile = null;
                }
            } 
            
            // Post failed for some reason, keep log files and retry later
            catch (RetrofitError error) {}
        }
    }
}

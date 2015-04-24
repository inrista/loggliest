# Loggliest [ ![Download](https://api.bintray.com/packages/inrista/maven/loggliest/images/download.svg) ](https://bintray.com/inrista/maven/loggliest/_latestVersion)
Android [Loggly](https://www.loggly.com/) client. Loggliest provides asynchronous logging that queues log messages locally in the internal app storage and uploads logs to Loggly using the HTTP/S bulk api. 

## Setup
Add this to your Gradle configuration:
```gradle
dependencies {
    compile 'com.inrista.loggliest:loggliest:0.2'
}
```

Loggliest is available on JCenter which is the default repository, so no further configuration should be necessary. Loggliest depends on Retrofit 1.9.0 and requires API level 9 (Android 2.3). It requires the internet permission.

## Usage
First, before you log any messages, initialize the global singleton instance using the `Builder` provided by `with(android.content.Context, String)`. You must provide your Loggly token. A minimal setup looks like this:

```java
final String TOKEN = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
Loggly.with(this, TOKEN).init();
```

You typically init Loggliest in your MainActivity's `onCreate()` or in an Application subclass. Either way, Loggliest keeps a global application context and lives as long the app process lives so that you can log for instance activity state changes.

Log a message:
```java
Loggly.i("stringfield", "test"); 
Loggly.i("numberfield", 123);
```
Log messages are formatted as json messages as expected by Loggly. Along with the json field and value provided by calling one of the log functions, a "level" field is appended with the current log level (verbose, debug, info, warning, error), and a "timestamp" set according to when the message was logged. Also, a set of various default fields can be appended, including app version name and number.

The log messages are saved in the internal app storage and uploaded to Loggly in bulk when either one of these conditions are fulfilled:

1. The time since the last upload exceeds the `uploadIntervalSecs(int seconds)` setting
2. The number of log messages since the last upload exceeds the `uploadIntervalLogCount(int count)` setting
3. A new `Loggly` instance is created and there are (old) messages that have not previously been uploaded. This will happen for instance when the app is killed and the user restarts it some time later
4. `forceUpload()` is called

A typical configuration that sets upload intervals to 30mins, max number of messages between uploads to 1000 messages, limits the internal app storage log buffer to 500k and appends the default info fields along with a custom language field looks like this:

```java
Loggly.with(this, TOKEN)
    .appendDefaultInfo(true)
    .uploadIntervalLogCount(1000)
    .uploadIntervalSecs(1800)
    .maxSizeOnDisk(500000)
    .appendStickyInfo("language", currentLanguage)
    .init();
```

By default, each message gets the app's package name as its Loggly tag, but this can be changed by setting the `tag(String logglyTag)` on the `Builder`.

**NOTE:** Short upload intervals will have a negative effect on the battery life. You want to carefully configure the upload settings and limit the amount of data you log in production settings so as to limit the impact on battery life and network data usage your logging causes.
 
## License
```
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
# Loggliest [ ![Download](https://api.bintray.com/packages/inrista/maven/loggliest/images/download.svg) ](https://bintray.com/inrista/maven/loggliest/_latestVersion)
Android [Loggly](https://www.loggly.com/) client that uses the HTTP/S bulk api.

## Setup

TODO

Loggliest depends on Retrofit 1.9.0 and requires API level 9 (Android 2.3)

## Usage
First, initialize the global singleton instance using the `Builder` provided by `with(android.content.Context, String)`. You must provide your Loggly token. A minimal setup looks like this:

```java
final String TOKEN = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
Loggly.with(this, TOKEN).init();
```

Log a message:
```java
Loggly.i("stringfield", "test"); 
Loggly.i("numberfield", 123);
```
Log messages are formatted as json messages as expected by Loggly. Along with the json field and value provided by calling one of the log functions, a "level" field is appended with the current log level (verbose, debug, info, warning, error), and a "timestamp" set according to when the message was logged. Also, a set of various default fields can be appended, including app version name and number.

The log messages are saved in the internal app storage and uploaded to Loggly in bulk when either one of these conditions are fulfilled:

1. The time since the last upload exceeds the `uploadIntervalSecs(int seconds)` setting
2. The number of log messages since the last upload exceeds the `uploadIntervalLogCount(int count)` setting
3. A new `Loggly` instance is created and there are (old) messages that have not previously been uploaded
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

**NOTE:** Short upload intervals will have a negative effect on the battery life. 
 
 
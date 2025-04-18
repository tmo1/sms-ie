# Building SMS Import / Export from Source

The [SMS Import / Export Github repository](https://github.com/tmo1/sms-ie) can be cloned and the app built locally, from the command line or using [Android Studio](https://en.wikipedia.org/wiki/Android_Studio).

Following are instructions for building the app from the command line.

## Prerequisites

There are three primary prerquisities for building the app: the [Gradle Build Tool](https://gradle.org/), a [Java](https://en.wikipedia.org/wiki/Java_(software_platform)) runtime environment (JRE), and the [Android software development kit (SDK)](https://en.wikipedia.org/wiki/Android_SDK). (There are other dependencies as well, as specified in the `dependencies {}` stanza of the app's [`build.gradle`](https://github.com/tmo1/sms-ie/app/build.gradle) configuration file, but they are handled automatically by Gradle.)

### Gradle

The app has been designed to be built via the [Gradle Wrapper script](https://docs.gradle.org/current/userguide/gradle_wrapper.html) included in the repository, which will
download and invoke the version of Gradle specified in the project's [`build.gradle`](https://github.com/tmo1/sms-ie/build.gradle) configuration file. While it may be possible, and even desirable from certain perspectives, to [build the app via a system Gradle installation](https://wiki.debian.org/AndroidTools), this has not been tested and is not supported.

### JRE

To build the app, a JRE must be present and findable by Gradle; methods for arranging this vary by operating system. The app's Gradle configuration currently requires Java version 17; in Debian, just ensure that the package `openjdk-17-jre-headless` is installed.

### Android SDK

To build the app, the Android SDK must be installed and findable by Gradle. There are various methods for arranging this, as discussed in [the official Android documentation](https://developer.android.com/tools/sdkmanager), as well as [here](https://stackoverflow.com/questions/34556884/how-to-install-android-sdk-on-ubuntu), [here](https://stackoverflow.com/questions/4681697/is-there-a-way-to-automate-the-android-sdk-installation), [here](https://www.androidcentral.com/installing-android-sdk-windows-mac-and-linux-tutorial), and [here](https://proandroiddev.com/how-to-setup-android-sdk-without-android-studio-6d60d0f2812a).

As with Gradle, it may be possible, and even desirable from certain perspectives, to [build the app via a system SDK installation](https://wiki.debian.org/AndroidTools), but this has not been tested and is not supported.

Following is an example series of commands to install the SDK, largely following the official documentation:

```
~$ curl -O https://dl.google.com/android/repository/commandlinetools-linux-10406996_latest.zip
~$ unzip commandlinetools-linux-10406996_latest.zip -d ~/android_sdk
~$ mkdir ~/android_sdk/cmdline-tools/latest
~$ mv ~/android_sdk/cmdline-tools/* ~/android_sdk/cmdline-tools/latest
```

(Ignore the `mv: cannot move ... to a subdirectory of itself ...` error.)

#### Accept SDK Licenses

Although the Android SDK is open source, Google's packaging of the software require the acceptance of End User License Agreements (EULAs) before it can be used. The [android-rebuilds](https://gitlab.com/android-rebuilds) project apparently used to provide rebuilt versions of the SDK without the EULAs, but the project currently does not appear to be under active development.

To accept the licenses, run:

```
~$ ~/android_sdk/cmdline-tools/latest/bin/sdkmanager --licenses
```

The process can be automated [as follows](https://stackoverflow.com/a/52634944):

```
~$ yes | ~/android_sdk/cmdline-tools/latest/bin/sdkmanager --licenses
```

## Build the App

Get the source code:

```
~$ git clone https://github.com/tmo1/sms-ie.git
~$ cd sms-ie
```

Point Gradle to the installed SDK (see [here](https://stackoverflow.com/questions/27620262/sdk-location-not-found-define-location-with-sdk-dir-in-the-local-properties-fil)):

```
~$ echo "sdk.dir=/home/<user>/android_sdk" >> local.properties
```

**Note:** The full path to the SDK location must be provided - tilde expansion (`~/android_sdk`) will not work.

The app is now ready to be built. To build a debug build, run:

```
~$ ./gradlew assembleStandardDebug
```

(The [application level `build.gradle`](https://github.com/tmo1/sms-ie/blob/master/app/build.gradle) currently specifies four (theoretically) possible build variants: `legacyDebug`, `legacyRelease`, `standardDebug`, and `standardRelease`. `release` builds are [shrunken and minified](https://developer.android.com/build/shrink-code), while `debug` builds are not. For the difference between `legacy` and `standard` builds, see [here](https://github.com/tmo1/sms-ie#installation). The `legacyDebug` variant is not currently buildable from the command line, due to the [64K reference limit](https://developer.android.com/build/multidex).)

For more information, including instructions for building and signing release builds, see [the official Android documentation](https://developer.android.com/build/building-cmdline), as well as [here](https://stackoverflow.com/questions/24398041/build-android-studio-app-via-command-line).

#!/bin/bash

echo "Configurando entorno Android..."

# export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
# export ANDROID_HOME=$HOME/android-sdk
# export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH

# echo "Java:"
# java --version

echo "Gradle clean..."
./gradlew clean

echo "Compilando APK debug..."
./gradlew assembleDebug

if [ $? -eq 0 ]; then
    echo ""
    echo "OK: APK generado:"
    ls -lh app/build/outputs/apk/debug/app-debug.apk
else
    echo ""
    echo "ERROR en compilación"
fi

#!/bin/bash

set -e

echo "========================================="
echo "   Building Kid Tracker APK"
echo "========================================="
echo ""

if ! command -v java &> /dev/null; then
    echo "[!] Java not found. Please install JDK 17."
    echo "    sudo apt install openjdk-17-jdk"
    exit 1
fi

if [ ! -f "gradlew" ]; then
    echo "[!] gradlew not found. Generating wrapper..."
    cd kid-app
    gradle wrapper --gradle-version 8.0 2>/dev/null || {
        echo "[!] gradle not installed. Install it:"
        echo "    sudo apt install gradle"
        exit 1
    }
else
    cd kid-app
fi

echo "[1/3] Building release APK..."
chmod +x gradlew
./gradlew assembleRelease

APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"

if [ ! -f "$APK_PATH" ]; then
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
fi

if [ -f "$APK_PATH" ]; then
    cp "$APK_PATH" ../kid-tracker.apk
    echo ""
    echo "========================================="
    echo "BUILD SUCCESSFUL!"
    echo "APK: $(pwd)/../kid-tracker.apk"
    echo "========================================="
    echo ""
    echo "Transfer this APK to the kid's phone:"
    echo "  - USB cable"
    echo "  - Email it to yourself"
    echo "  - Or use: adb install ../kid-tracker.apk"
else
    echo "[!] APK not found. Check build output above."
    exit 1
fi

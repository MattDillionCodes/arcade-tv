#!/bin/zsh
# Build Arcade, install it, and make it the Home screen.
# Usage: ./install.sh               (first connected device / emulator)
#        TV=192.168.68.61:5555 ./install.sh
set -e
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
export JAVA_HOME=${JAVA_HOME:-"/Applications/Android Studio.app/Contents/jbr/Contents/Home"}

cd "${0:A:h}"
./gradlew assembleRelease --console=plain -q
if [[ -n $TV ]]; then
  [[ $TV == *:* ]] && $ADB connect "$TV" >/dev/null
  export ANDROID_SERIAL=$TV
fi
$ADB install -r app/build/outputs/apk/release/app-release.apk
$ADB shell cmd package set-home-activity com.matthew.arcade/.HomeActivity >/dev/null
$ADB shell input keyevent KEYCODE_HOME
echo "Arcade is Home."

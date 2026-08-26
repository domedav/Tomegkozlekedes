#!/data/data/com.termux/files/usr/bin/bash
# MÁV Jegy - reproducible release build Termux alatt
# Használat: bash buildRelease.sh [assembleDebug|assembleRelease|bundleRelease]
# Mindent maga biztosít: proxy, SDK, gradle. Csak Termux + internet kell hozzá.
set -e

PROJ="$(cd "$(dirname "$0")" && pwd)"
TASK="${1:-assembleRelease}"
OPT="$HOME/opt"
GRADLE_VER=9.5.1
API=36

export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk
GRADLE="$OPT/gradle-$GRADLE_VER/bin/gradle"

log() { echo "[*] $*"; }

# --- 1. helyi CONNECT proxy (DNS-blokkolt domainekhez) ---
if ! pgrep -f "mavproxy.py" > /dev/null 2>&1; then
    log "Proxy indítása..."
    setsid nohup python3 "$OPT/mavproxy.py" >> "$OPT/mavproxy.log" 2>&1 < /dev/null &
    disown
    sleep 2
fi

# --- 2. Gradle letöltés, ha nincs ---
if [ ! -x "$GRADLE" ]; then
    log "Gradle $GRADLE_VER letöltése..."
    curl -sL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-$GRADLE_VER-bin.zip" --max-time 900
    unzip -qo /tmp/gradle.zip -d "$OPT" && rm /tmp/gradle.zip
fi

# --- 3. Minimál Android SDK (android.jar platform-$API), ha nincs ---
SDK="$OPT/android-sdk"
if [ ! -f "$SDK/platforms/android-$API/android.jar" ]; then
    log "android.jar (platform $API) letöltése..."
    mkdir -p "$SDK/platforms/android-$API" "$SDK/build-tools/$API.0.0" "$SDK/licenses"
    curl -sL -o /tmp/android.jar "https://raw.githubusercontent.com/Sable/android-platforms/master/android-$API/android.jar" --max-time 900
    mv /tmp/android.jar "$SDK/platforms/android-$API/android.jar"
    printf "AndroidVersion.ApiLevel=$API\nPkg.Desc=Android SDK Platform $API\nPlatform.Version=$API\n" > "$SDK/platforms/android-$API/source.properties"
    printf "Pkg.Revision=$API.0.0\n" > "$SDK/build-tools/$API.0.0/source.properties"
    printf '24333f8a63b6825ea9c5514f83c2829b004d1fee\n8933bad161af4178b1185d1a37fbf41ea5269c55\nd56f5187479451eabf01fb78af6dfcb131a6481e\n' > "$SDK/licenses/android-sdk-license"
fi
# build-tools symlinkek/stubok (Termux binárisok)
B=/data/data/com.termux/files/usr/bin
for t in aapt2 aapt apksigner zipalign; do
    [ -e "$SDK/build-tools/$API.0.0/$t" ] || ln -sf "$B/$t" "$SDK/build-tools/$API.0.0/$t"
done
for t in aidl dexdump d8 dx split-select bcc_compat llvm-rs-cc; do
    if [ ! -e "$SDK/build-tools/$API.0.0/$t" ]; then
        printf '#!/data/data/com.termux/files/usr/bin/bash\nexit 0\n' > "$SDK/build-tools/$API.0.0/$t"
        chmod +x "$SDK/build-tools/$API.0.0/$t"
    fi
done

# --- 4. projekt konfiguráció ---
echo "sdk.dir=$SDK" > "$PROJ/local.properties"

# --- 5. build ---
log "Build: $TASK"
cd "$PROJ"
"$GRADLE" "$TASK" --no-daemon

APK="app/build/outputs/apk/release/app-release.apk"
[ "$TASK" = "assembleRelease" ] && [ -f "$APK" ] && echo "[+] Kész: $PROJ/$APK"

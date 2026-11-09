#!/bin/sh
# Сборка aemu-stubs.jar — гостевых служб-заглушек на Java (dex для Dalvik 4.2+).
# Нужны: JDK (javac), Android SDK (android.jar и d8 из build-tools).
set -e
# The compile lines below name the sources bare (hidden/, src/), so the script has to work from
# its own directory: CI calls it as `sh native/gueststubs/build.sh` from the repository root.
SELF_DIR=$(cd "$(dirname "$0")" && pwd)
cd "$SELF_DIR"
SDK=${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}
JAR="$SDK/platforms/android-36/android.jar"
D8=$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)d8
[ -x "$D8" ] || D8="$D8.bat"
# javac splits -cp on the platform separator: ';' on Windows, ':' everywhere else. The dashboard
# builds this jar on Linux (see .github/workflows/build.yml), so a hard-coded ';' silently makes
# the second javac see one nonsense path and every android.* import fails to resolve.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) PS=';' ;;
  *)                    PS=':' ;;
esac
OUT=${1:-$SELF_DIR/../../app/src/main/assets/engines/kk/aemu-stubs.jar}
rm -rf build && mkdir -p build/classes
mkdir -p build/hidden
# -encoding UTF-8 is not optional: the sources carry Russian and typographic punctuation in
# comments, and javac defaults to the platform code page (GBK on this workstation), which fails
# the build outright with "unmappable character" errors.
javac -encoding UTF-8 --release 8 -cp "$JAR" -d build/hidden $(find hidden -name '*.java')
javac -encoding UTF-8 --release 8 -cp "build/hidden${PS}$JAR" -d build/classes $(find src -name '*.java')
"$D8" --min-api 17 --lib "$JAR" --output build $(find build/classes -name '*.class')
# One entries: classes.dex. Not jar(1): it also writes META-INF/MANIFEST.MF, and a jar whose
# first entry is not the dex is a different artifact from what was shipped.
# Probe, do not assume: on Windows a `python3` on PATH is often the Microsoft Store app alias,
# which is not an interpreter at all and exits 49 -- no error message, just a failed build.
PY=
for c in python3 python py; do
  if command -v "$c" > /dev/null 2>&1 && "$c" -c "import zipfile" > /dev/null 2>&1; then PY="$c"; break; fi
done
if [ -z "$PY" ]; then echo "no working python with zipfile" >&2; exit 1; fi
"$PY" -c "import zipfile;z=zipfile.ZipFile('build/stubs.jar','w',zipfile.ZIP_DEFLATED);z.write('build/classes.dex','classes.dex');z.close()"
mkdir -p "$(dirname "$OUT")"
cp build/stubs.jar "$OUT"
echo "готово: $OUT"

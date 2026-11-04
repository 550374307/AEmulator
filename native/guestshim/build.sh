#!/bin/sh
# Сборка гостевой прослойки: ARMv7, без libc, с классической хэш-таблицей для линкера bionic 2.3
#
#   sh build.sh [OUT]           — ARMv7 libaemushim.so + aemu_true.so + librecshim.so (needs NDK)
#   sh build.sh hosttest        — only the bproto2 client/mock pair, built with the host compiler
#
# The host build exists because the bridge framing is the part that is miserable to debug on a
# device: it lets `ab_call()` talk to a mock server on a workstation, with libc instead of raw
# syscalls, and no NDK present.

set -e

SELF_DIR=$(cd "$(dirname "$0")" && pwd)
# The compile lines below name their sources without a path, and the default OUT is relative
# to this directory. cd here so `sh native/guestshim/build.sh` from the repo root behaves the
# same as `sh build.sh` from inside it — which is what CI does.
cd "$SELF_DIR"

if [ "$1" = "hosttest" ]; then
  HOSTCC=${HOSTCC:-cc}
  WINSOCK=""
  # an `if`, not `[ ... ] && X=...`: under `set -e` a failing test in an AND-list aborts the script,
  # which would break `hosttest` on every non-MinGW host.
  if [ "$(uname -s 2>/dev/null | cut -c1-5)" = "MINGW" ] || [ -n "$MSYSTEM" ]; then
    WINSOCK="-lws2_32"
  fi
  "$HOSTCC" -DAB_HOST_TEST -D_GNU_SOURCE -O1 -g -Wall -Wextra -std=c11 \
    -o "$SELF_DIR/ab_host_test" "$SELF_DIR/bridge.c" "$SELF_DIR/ab_host_test.c" $WINSOCK
  echo "built $SELF_DIR/ab_host_test"
  exit 0
fi

NDK=${NDK:-$ANDROID_NDK_HOME}
CC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/clang"
[ -x "$CC" ] || CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
BIN="$(dirname "$CC")"
OUT=${1:-../../app/src/main/assets/engines/common/libaemushim.so}
mkdir -p "$(dirname "$OUT")"
"$CC" --target=armv7a-linux-androideabi21 -march=armv7-a -mthumb -Os -fPIC -shared -nostdlib \
  -fvisibility=hidden -fno-stack-protector -ffreestanding -fno-builtin -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--no-undefined \
  -Wl,-soname,libaemushim.so -Wl,--build-id=none -o "$OUT" aemushim.c crash.c bridge.c -lc
"$BIN/llvm-strip" "$OUT" 2>/dev/null || true
# пустая программа вместо iptables/ip6tables
"$CC" --target=armv7a-linux-androideabi21 -nostdlib -static -Wl,--build-id=none \
  -o "$(dirname "$OUT")/aemu_true.so" true.S
"$BIN/llvm-strip" "$(dirname "$OUT")/aemu_true.so" 2>/dev/null || true
# прослойка для динамического recovery (TWRP): «смонтированные» разделы и перезагрузка через хост
"$CC" --target=armv7a-linux-androideabi21 -march=armv7-a -mthumb -Os -fPIC -shared -nostdlib \
  -fvisibility=hidden -fno-stack-protector -ffreestanding -fno-builtin -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--no-undefined \
  -Wl,-soname,librecshim.so -Wl,--build-id=none -o "$(dirname "$OUT")/librecshim.so" recshim.c -lc
"$BIN/llvm-strip" "$(dirname "$OUT")/librecshim.so" 2>/dev/null || true

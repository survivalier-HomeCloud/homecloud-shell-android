#!/bin/sh
# End-to-end test: the Java client (the code the app uses) against a REAL homecloud-shelld.
# Needs: a JDK, python3, a built homecloud-shelld, and root (the default "ns" sandbox backend needs it;
# HC_BACKEND=bwrap works too where bubblewrap is installed).
#   HOMECLOUD_SHELLD=/path/to/homecloud-shelld tests/e2e/run.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$HERE/../../app/src/main/java/com/homecloud/shell"
SHELLD=${HOMECLOUD_SHELLD:-homecloud-shelld}
T=$(mktemp -d)
FAKE=""; SHD=""
trap 'kill $FAKE $SHD 2>/dev/null; rm -rf "$T"' EXIT
mkdir -p "$T/data/disks/d1" "$T/out"
printf 'port=18680\ndata_dir=%s/data\n' "$T" > "$T/homecloud.conf"
printf 'kind=lite\nhc_conf=%s/homecloud.conf\nbackend=%s\nnetwork=1\nmem_mb=1024\npids=512\nautostart=0\n' "$T" "${HC_BACKEND:-ns}" > "$T/shell.conf"
python3 "$HERE/fake_hc.py" "$T/shell.sock" > "$T/fake.log" 2>&1 &
FAKE=$!
HC_HOME="$T" "$SHELLD" run > "$T/shelld.log" 2>&1 &
SHD=$!
sleep 2
javac -d "$T/out" "$SRC/Protocol.java" "$SRC/ShellConnection.java" "$SRC/TerminalEmulator.java" "$HERE/../java/com/homecloud/shell/E2E.java"
echo "== wrong password";   java -cp "$T/out" com.homecloud.shell.E2E "mauvais" | head -3
echo "== correct password"; java -cp "$T/out" com.homecloud.shell.E2E
echo "== shelld log";       cat "$T/shelld.log"

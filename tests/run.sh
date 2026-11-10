#!/bin/sh
# Unit tests of the protocol, the connection and the terminal emulator (plain Java, no Android needed).
# Usage: tests/run.sh        (needs a JDK 17+)
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$HERE/../app/src/main/java/com/homecloud/shell"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
javac -d "$OUT" "$SRC/Protocol.java" "$SRC/ShellConnection.java" "$SRC/TerminalEmulator.java" \
    "$HERE/java/com/homecloud/shell/EmuTest.java" "$HERE/java/com/homecloud/shell/ConnTest.java"
echo "== TerminalEmulator"; java -cp "$OUT" com.homecloud.shell.EmuTest | grep -v '^ok'
echo "== ShellConnection (fake server)"; java -cp "$OUT" com.homecloud.shell.ConnTest | grep -v '^ok'

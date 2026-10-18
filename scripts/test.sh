#!/bin/sh
# Compiles the sources and the JUnit tests in test/ and runs them with the
# JUnit platform console launcher from lib/test (no Maven/Gradle needed).
#
# Usage: scripts/test.sh [additional console launcher options]
set -eu

cd "$(dirname "$0")/.."
WORK="build/test"

rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/test-classes"

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; *) SEP=":" ;; esac
CP=""
for j in lib/*.jar; do
	CP="$CP${CP:+$SEP}$j"
done
JUNIT=$(ls lib/test/junit-platform-console-standalone-*.jar | head -n 1)

if javac --help 2>/dev/null | grep -q -- '--release'; then
	TARGET="--release 8"
else
	TARGET="-source 8 -target 8"
fi

find src -name '*.java' > "$WORK/src-files.txt"
# shellcheck disable=SC2086
javac -encoding Cp1252 $TARGET -nowarn -d "$WORK/classes" -cp "$CP" @"$WORK/src-files.txt"
(cd rsc && find . -type f ! -name '*.java' | while read -r f; do
	mkdir -p "../$WORK/classes/$(dirname "$f")"
	cp "$f" "../$WORK/classes/$f"
done)

find test -name '*.java' > "$WORK/test-files.txt"
# shellcheck disable=SC2086
javac -encoding UTF-8 $TARGET -nowarn -d "$WORK/test-classes" -cp "$WORK/classes$SEP$CP$SEP$JUNIT" @"$WORK/test-files.txt"

java -jar "$JUNIT" execute --class-path "$WORK/test-classes$SEP$WORK/classes$SEP$CP" --scan-class-path \
	--fail-if-no-tests --details=tree --disable-banner "$@"

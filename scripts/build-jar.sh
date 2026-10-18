#!/bin/sh
# Builds solvis2mqtt.jar from src/, rsc/ and lib/ without Ant or Maven.
#
# Usage: scripts/build-jar.sh [output-jar]      (default: ./solvis2mqtt.jar)
#
# Requirements: JDK 8 or newer (javac, jar). The sources are Cp1252-encoded;
# the class files target Java 8 so that the jar also runs on an old JRE.
# Used by developers, by the GitHub Actions workflow and by the Dockerfile.
set -eu

cd "$(dirname "$0")/.."
OUT="${1:-solvis2mqtt.jar}"
WORK="build/jar"

rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/stage"

find src -name '*.java' > "$WORK/files.txt"

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; *) SEP=":" ;; esac
CP=""
for j in lib/*.jar; do
	CP="$CP${CP:+$SEP}$j"
done

# javac of JDK 8 has no --release
if javac --help 2>/dev/null | grep -q -- '--release'; then
	TARGET="--release 8"
else
	TARGET="-source 8 -target 8"
fi
# shellcheck disable=SC2086
javac -encoding Cp1252 $TARGET -nowarn -d "$WORK/classes" -cp "$CP" @"$WORK/files.txt"

# classes + resources
cp -R "$WORK/classes/." "$WORK/stage/"
(cd rsc && find . -type f ! -name '*.java' | while read -r f; do
	mkdir -p "../$WORK/stage/$(dirname "$f")"
	cp "$f" "../$WORK/stage/$f"
done)

# bundle the libraries (fat jar), keep META-INF subdirectories such as the
# log4j plugin registry, drop the libraries' own manifests and licence files
for j in lib/*.jar; do
	(cd "$WORK/stage" && jar xf "../../../$j")
done
find "$WORK/stage/META-INF" -maxdepth 1 -type f -delete 2>/dev/null || true

BUILT="${SOURCE_DATE:-$(date -u '+%Y-%m-%d %H:%M:%S')}"
printf 'Manifest-Version: 1.0\nMain-Class: de.sgollmer.solvismax.Main\nMulti-Release: true\nBuilt-Date: %s\n' "$BUILT" > "$WORK/MANIFEST.MF"

rm -f "$OUT"
jar --create --file "$OUT" --manifest "$WORK/MANIFEST.MF" -C "$WORK/stage" . 2>/dev/null \
	|| jar cfm "$OUT" "$WORK/MANIFEST.MF" -C "$WORK/stage" .

echo "built $OUT ($(wc -c < "$OUT") bytes, $BUILT)"

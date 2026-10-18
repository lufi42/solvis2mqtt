#!/bin/sh
# Assembles the Linux release package solvis2mqtt.linux.<version>.zip
# (same layout as the "publish Linux" target of build-user.xml).
#
# Usage: scripts/make-release-zip.sh <version> [jar]   e.g. 1.0.0 solvis2mqtt.jar
set -eu

cd "$(dirname "$0")/.."
VERSION="${1:?version missing, e.g. 1.0.0}"
JAR="${2:-solvis2mqtt.jar}"
NAME="solvis2mqtt.linux.$VERSION"
DIR="build/release/$NAME"

rm -rf "$DIR"
mkdir -p "$DIR"
cp "$JAR" "$DIR/solvis2mqtt.jar"
cp rsc/de/sgollmer/solvismax/data/base.xml "$DIR/base.xml.new"
cp rsc/de/sgollmer/solvismax/data/base.xsd "$DIR/"
cp docu/SolvisSmartHomeServer.pdf "$DIR/"
for f in SmartHome/FHEM/73_SolvisClient.pm SmartHome/Linux/solvis2mqtt.service \
	SmartHome/Linux/solvis2mqtt-debug.service SmartHome/Linux/Makefile docu/CHANGES.txt; do
	tr -d '\r' < "$f" > "$DIR/$(basename "$f")"
done

rm -f "build/release/$NAME.zip"
(cd build/release && zip -qr "$NAME.zip" "$NAME")
echo "build/release/$NAME.zip"

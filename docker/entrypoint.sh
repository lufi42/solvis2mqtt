#!/bin/sh
# solvis2mqtt container entrypoint.
#
# Starts the server with the base.xml from the data volume. Additional
# arguments are passed to the server, e.g.
#   docker run --rm -v ./data:/data ghcr.io/lufi42/solvis2mqtt --server-learn
#   docker run --rm -v ./data:/data ghcr.io/lufi42/solvis2mqtt --string-to-crypt=secret
set -eu

BASE_XML="${SOLVIS2MQTT_BASE_XML:-/data/base.xml}"

if [ ! -f "$BASE_XML" ]; then
	echo "solvis2mqtt: $BASE_XML not found." >&2
	echo "Mount a volume on /data that contains base.xml with writablePathLinux=\"/data\"." >&2
	echo "A template is in the image: docker run --rm --entrypoint cat <image> /app/base.xml.new > base.xml" >&2
	exit 2
fi

# shellcheck disable=SC2086
exec java $JAVA_OPTS -Djava.awt.headless=true -jar /app/solvis2mqtt.jar --base-xml="$BASE_XML" "$@"

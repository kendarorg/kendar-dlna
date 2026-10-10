#!/bin/sh
# Container entrypoint: fixes ownership of the writable volumes, then drops privileges to PUID:PGID.
# Works with su-exec (Alpine) or gosu (Ubuntu-based images); numeric ids need no passwd entry.
set -eu

PUID="${PUID:-1000}"
PGID="${PGID:-1000}"
JAVA_OPTS="${JAVA_OPTS:-}"
# Defaults first, so that JAVA_OPTS (for example -Xmx1g or a different -XX:MaxRAMPercentage) wins.
DEFAULT_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true -Dfile.encoding=UTF-8 -Duser.home=/tmp"

CONFIG_DIR="${KENDAR_CONFIG_DIR:-/config}"
DATA_DIR="${KENDAR_DATA_DIR:-/data}"

if [ "$(id -u)" = "0" ]; then
    mkdir -p "$CONFIG_DIR" "$DATA_DIR" /playlists
    # config and data are owned by the application (the music library is never touched: it can be huge/read-only).
    chown -R "$PUID:$PGID" "$CONFIG_DIR" "$DATA_DIR" 2>/dev/null || echo "WARN: cannot chown $CONFIG_DIR $DATA_DIR" >&2
    chown "$PUID:$PGID" /playlists 2>/dev/null || true
    if command -v su-exec >/dev/null 2>&1; then
        DROP="su-exec $PUID:$PGID"
    elif command -v gosu >/dev/null 2>&1; then
        DROP="gosu $PUID:$PGID"
    else
        echo "ERROR: neither su-exec nor gosu found" >&2
        exit 1
    fi
else
    DROP=""   # started with --user: nothing to drop
fi

# shellcheck disable=SC2086  # JAVA_OPTS is intentionally word-split
exec $DROP java $DEFAULT_OPTS $JAVA_OPTS -jar /app/kendar-dlna.jar "$@"

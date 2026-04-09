#!/bin/bash
# Start the MCP Gateway as a background process.
# Place this script next to quarkus-mcp-gateway-*-runner.jar and servers.yaml.

# Load SDKMAN so the correct Java version is used regardless of login shell
export SDKMAN_DIR="$HOME/.sdkman"
# shellcheck disable=SC1091
[[ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]] && source "$SDKMAN_DIR/bin/sdkman-init.sh"

# Load nvm so npx/node are available for stdio MCP servers
export NVM_DIR="$HOME/.nvm"
# shellcheck disable=SC1091
[[ -s "$NVM_DIR/nvm.sh" ]] && source "$NVM_DIR/nvm.sh"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/quarkus-mcp-gateway-1.0.0-runner.jar"
LOG="$SCRIPT_DIR/mcp-gateway.log"
PID_FILE="$SCRIPT_DIR/mcp-gateway.pid"

if [ ! -f "$JAR" ]; then
    echo "ERROR: jar not found: $JAR"
    exit 1
fi

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "Already running (PID $(cat "$PID_FILE"))"
    exit 0
fi

cd "$SCRIPT_DIR"
nohup java -Xms128m -Xmx256m \
    -jar "$JAR" \
    >> "$LOG" 2>&1 &

echo $! > "$PID_FILE"
echo "Started MCP Gateway (PID $!) — log: $LOG"

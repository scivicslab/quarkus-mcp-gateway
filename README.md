# Quarkus MCP Gateway

A lightweight MCP (Model Context Protocol) endpoint gateway built with Java and Quarkus. Register, discover, and proxy MCP servers by name — no need to track individual ports.

## Features

- **Name-based routing**: Send MCP requests to `/mcp/{serverName}` instead of remembering port numbers
- **Server registry**: Register/unregister MCP servers via REST API or YAML config
- **Service discovery**: Scan port ranges to automatically find MCP servers
- **Health checking**: Periodic health checks (every 30s) with automatic removal of unreachable discovered servers after 1 hour
- **Session management**: Automatically tracks backend `Mcp-Session-Id` per client
- **Auto-registration**: Pre-configure servers in `servers.yaml`
- **HTML dashboard**: View all registered servers at `/`
- **Pure Java**: No Docker, no Python — just a single Quarkus JAR

## Quick Start

```bash
# Build
mvn package -DskipTests

# Run
java -jar target/quarkus-app/quarkus-run.jar

# Or dev mode
mvn quarkus:dev
```

The gateway starts on port **8888** by default.

## Configuration

Edit `servers.yaml` to pre-register MCP servers:

```yaml
servers:
  - name: chat-ui
    url: http://localhost:8090
    description: Quarkus Coder Agent (Claude AI)

  - name: workflow-editor
    url: http://localhost:8081
    description: Turing Workflow Editor
```

The gateway looks for `./servers.yaml` in the current directory first, then falls back to the bundled default.

## Usage

### Register a server

```bash
curl -X POST http://localhost:8888/api/servers \
  -H 'Content-Type: application/json' \
  -d '{"name": "my-agent", "url": "http://localhost:9090", "description": "My MCP agent"}'
```

### List registered servers

```bash
curl http://localhost:8888/api/servers
```

### Proxy MCP requests by name

```bash
# Instead of: POST http://localhost:8090/mcp
# Use:        POST http://localhost:8888/mcp/chat-ui
curl -X POST http://localhost:8888/mcp/chat-ui \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {...}}'
```

### Service discovery

Scan a port range to find MCP servers automatically:

```bash
# Scan using discovery.yaml defaults
curl -X POST http://localhost:8888/api/discover

# Scan with explicit host/ports
curl -X POST http://localhost:8888/api/discover \
  -H 'Content-Type: application/json' \
  -d '{"host": "localhost", "ports": "28000-29000"}'

# Discover and register in one step
curl -X POST http://localhost:8888/api/discover/register
```

Configure scan targets in `discovery.yaml`:

```yaml
discovery:
  targets:
    - host: localhost
      ports: "28000-29000"
```

### Health checking

Health checks run every 30 seconds against all registered servers. Discovered servers (registered via service discovery) that remain unhealthy for over **1 hour** are automatically removed from the registry. Servers registered via `servers.yaml` or REST API are never auto-removed.

### Unregister a server

```bash
curl -X DELETE http://localhost:8888/api/servers/my-agent
```

## API Reference

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/mcp/{serverName}` | Proxy MCP JSON-RPC to named server |
| `POST` | `/api/servers` | Register a server (`{name, url, description}`) |
| `GET` | `/api/servers` | List all registered servers |
| `GET` | `/api/servers/{name}` | Look up a server by name |
| `DELETE` | `/api/servers/{name}` | Unregister a server |
| `POST` | `/api/discover` | Scan for MCP servers |
| `POST` | `/api/discover/register` | Discover and register servers |
| `GET` | `/api/sessions/{sessionId}` | Fetch session metadata |
| `GET` | `/api/history?limit=50` | Aggregate history from all servers |
| `GET` | `/` | HTML dashboard |

## Built-in Agent Tools

The gateway exposes several built-in MCP tools that any connected agent can call.

### Agent-to-Agent Communication

Two tools handle inter-agent messaging.  Both look up the target by name from the registry, so neither side needs to hard-code ports.

#### `call_agent` (recommended)

Sends a prompt to a named agent and **blocks until the reply arrives** (up to 5 minutes).  The caller gets the reply directly — no polling required.

```
call_agent(agent, prompt, [model], [caller]) → reply text
```

Use this when you need the other agent's answer inline in your conversation.

#### `submit_to_agent` (fire-and-forget)

Submits a prompt and returns immediately with a UUID.  The caller is then responsible for polling `getPromptStatus(UUID)` on the target until done, then calling `getPromptResult(UUID)`.

```
submit_to_agent(agent, prompt, [model], [caller]) → UUID
```

Use this only when you want to do other work while the target agent is thinking, or when you need fine-grained control over the polling loop.

> **Design note**: the submit/poll/get split exists because `submitPrompt`, `getPromptStatus`, and `getPromptResult` are the low-level MCP tools that `quarkus-chat-ui` exposes.  `call_agent` wraps all three in a single synchronous call inside the gateway, so the LLM never has to manage the polling loop itself.  This is important for weaker models (e.g., Qwen3) that may not reliably chain three tool calls in the correct order.

#### `list_agents`

Lists all registered agents (name, URL, health status).  Use the name with `call_agent` or `submit_to_agent`.

### Typical multi-agent workflow

```
Agent A (Claude, :28100)          MCP Gateway (:28081)        Agent B (Qwen3, :28102)
         │                                │                              │
         │── call_agent("qwen3", ...) ───▶│                              │
         │                                │── submitPrompt ─────────────▶│
         │                                │                   (thinking) │
         │                                │◀─────────────── UUID ────────│
         │                                │── getPromptStatus (polling)  │
         │                                │── getPromptStatus (polling)  │
         │                                │── getPromptResult ──────────▶│
         │                                │◀────────────── reply ────────│
         │◀──────────── reply ────────────│                              │
```

## Architecture

```
MCP Client (workflow-editor, CLI, etc.)
    │
    │  POST /mcp/chat-ui
    ▼
┌──────────────────────┐
│   MCP Gateway :8888  │
│                      │
│  Registry            │
│   chat-ui → :8090│
│   workflow    → :8081│
│                      │
│  Proxy               │
│   forward JSON-RPC   │
│   track sessions     │
└──────┬───────┬───────┘
       │       │
       ▼       ▼
   :8090    :8081
  (coder)  (workflow)
```

## Requirements

- Java 21+
- Maven 3.9+

## License

Apache License 2.0

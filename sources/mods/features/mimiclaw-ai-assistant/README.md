# OpenClaw(Mini)

Android port of [MimiClaw](https://github.com/memovai/mimiclaw). This mod adapts the original ESP32-S3 project into Ava's Java-based mod runtime and is intended to integrate deeply with Ava, Home Assistant, and Android platform capabilities.

## Current Scope

- Multi-provider LLM support: Anthropic Claude and OpenAI GPT
- ReAct-style agent loop with iterative tool use
- Persistent session memory
- Tool registry and cron scheduler
- Android-native Java implementation
- Ava mod-store packaging and manager integration

## Port Status

Already ported:

- `message_bus`
- `agent_loop`
- `llm_proxy`
- `session_mgr`
- `memory_store`
- `cron_service`
- Telegram and QQ channel scaffolding

Still to improve toward full parity:

- `context_builder`
- `skill_loader`
- richer tool set parity such as file/gpio policy alignment
- Android/Ava/Home Assistant deep integration points
- service lifecycle hardening and state/reporting

## Architecture

```
MessageBus
    ↓
AgentLoop
    ↓
LlmProxy
    ↓
ToolRegistry
    ↓
SessionManager / MemoryStore
    ↓
Ava / Home Assistant / Android integrations
```

## Core Modules

### MessageBus
- Inbound/outbound queues for async message processing
- Thread-safe blocking queues
- Ported from `main/bus/message_bus.c`

### LlmProxy
- HTTP client for Anthropic and OpenAI APIs
- Automatic message format conversion between providers
- Tool calling support for both APIs
- Ported from `main/llm/llm_proxy.c`

### AgentLoop
- ReAct pattern implementation
- Iterative tool calling loop
- System prompt building
- Ported from `main/agent/agent_loop.c`

### ToolRegistry
- Dynamic tool registration
- Built-in tools: `get_current_time`, `web_search`
- JSON schema validation
- Ported from `main/tools/tool_registry.c`

### SessionManager
- JSONL-based conversation history
- Per-chat session isolation
- Automatic history trimming
- Ported from `main/memory/session_mgr.c`

## Configuration

- **provider**: `anthropic` or `openai`
- **api_key**: Your API key from console.anthropic.com or platform.openai.com
- **model**: Model name (e.g. `claude-opus-4-5` or `gpt-4o`)
- **max_tokens**: Maximum tokens per response (512-8192)
- **max_tool_iterations**: ReAct loop limit (1-20)

## Building

```bash
cd sources/features/mimiclaw-ai-assistant
./build.sh
```

Requirements:
- Android SDK platform 34+
- Java 11+
- d8 tool (Android build-tools)

## Usage

1. Install the mod in Ava
2. Configure API key and provider
3. Enable the mod
4. Send messages via Home Assistant button entity

## Original Project

This mod is based on [MimiClaw](https://github.com/memovai/mimiclaw), now branded as OpenClaw(Mini). The long-term goal here is not just a source translation, but a version that is fully integrated with Ava, Home Assistant, and Android system capabilities.

Original features preserved:
- ✅ Message bus architecture
- ✅ LLM proxy (Claude + GPT)
- ✅ ReAct agent loop
- ✅ Tool registry
- ✅ Session management
- ✅ Multi-provider support

ESP32-specific features adapted:
- FreeRTOS → Java threads
- SPIFFS → Android internal storage
- NVS → Ava config system
- ESP HTTP client → HttpURLConnection

## License

MIT - Same as original MimiClaw/OpenClaw project

# OpenClaw (Mini)

A pure-Java Android AI assistant module for [Ava](https://github.com/knoop7/Ava). Runs as a mod — no separate APK, no framework dependency. Connects any OpenAI/Anthropic-compatible LLM to device-level tools via an agentic loop.

## Architecture

```
User ──► Channel ──► AgentLoop ──► LLM (OpenAI/Anthropic API)
                        │
                   ToolRegistry ◄── SkillLoader
                        │
              ContextBuilder + SessionManager + MemoryStore
```

- **AgentLoop** — iterative tool-use loop (up to 100 rounds per turn)
- **ContextBuilder** — assembles system prompt from SOUL.md, USER.md, AGENTS.md, device state
- **SessionManager** — per-channel conversation history with intelligent compression
- **MemoryStore** — persistent file-based memory (long-term notes, daily logs, skills)
- **SkillLoader** — YAML-frontmatter markdown skills with execution stats and evolution tracking

## Channels

| Channel | Description |
|---------|-------------|
| **Android** | Native voice satellite integration via Ava |
| **WebConsole** | Local HTTP server with SSE streaming, tool call rendering |
| **Telegram** | Bot channel with long-polling |
| **QQ** | QQ Bot channel (sandbox supported) |

## Tools

### System
- `get_current_time` — current date/time
- `get_device_info` — model, version, memory, storage, permissions

### Shell & Terminal
- `android_shell_exec` — whitelisted Android shell (getprop, dumpsys, logcat, pm, etc.)
- `terminal_exec` — unrestricted Termux PTY execution for complex scripts

### UI Automation (Accessibility)
- `accessibility_status` / `accessibility_open_settings`
- `ui_tree_dump` — structured JSON UI tree
- `ui_find_text` / `ui_click_text` / `ui_click` / `ui_set_text` / `ui_scroll`
- `ui_screenshot` — smart capture (root → Shizuku → accessibility fallback)

### Browser
- `open_browser_url` — Ava internal overlay browser
- `ava_browser_hide` / `ava_browser_refresh` / `ava_browser_read_text`

### Memory & Files
- `read_agents` / `update_agents` — operating instructions (AGENTS.md)
- `read_soul` / `update_soul` — personality definition (SOUL.md)
- `read_user` / `update_user` — user profile (USER.md)
- `read_memory` / `update_memory` — long-term notes
- `append_daily_note` — daily log entries
- `read_file` / `write_file` / `edit_file` / `delete_file` / `list_dir`

### Skills
- `list_skills` / `read_skill` / `describe_skill`
- `install_skill_from_text` / `install_skill_from_url` / `delete_skill`

### Scheduling
- `cron_add` / `cron_list` / `cron_remove` — recurring and one-shot tasks

### Web Search
- `web_search` — Tavily API (default) with 17 fallback engines (Baidu, Bing, Google, DuckDuckGo, etc.)

## Configuration

All settings are managed through Ava's Mod Store UI:

| Key | Description |
|-----|-------------|
| `provider` | API protocol: `openai` or `anthropic` |
| `api_key` | LLM API key |
| `model` | Model ID (e.g. `stepfun/step-3.5-flash`) |
| `custom_api_url` | API endpoint (default: OpenRouter) |
| `max_tokens` | Max reply tokens (512–8192) |
| `max_tool_iterations` | Max tool rounds per turn (1–100) |
| `tavily_key` | Optional Tavily web search API key |
| `telegram_token` | Optional Telegram bot token |
| `qq_app_id` / `qq_client_secret` | Optional QQ bot credentials |
| `qq_sandbox` | QQ sandbox mode toggle |
| `web_console_enabled` | Enable local web console |
| `web_console_password` | Web console password (default: `openclaw`) |

## Requirements

- Android 9+ (API 28)
- Ava app installed
- Internet permission (for LLM API calls)
- Optional: Shizuku or root for shell commands
- Optional: Accessibility service for UI automation

## Build

```bash
cd sources/features/mimiclaw-ai-assistant
./build.sh
```

Output: `libs/mimiclaw-manager.jar`

## License

Part of the [ava-mods](https://github.com/knoop7/ava-mods) project.

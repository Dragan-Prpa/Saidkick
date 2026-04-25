# Saidkick

IntelliJ plugin prototype for a simple LLM chatbot.

## Features

- Basic chat UI in the tool window
- Sends user messages to an OpenAI-compatible Chat Completions API
- Uses `.env` values for LLM configuration
- Keeps in-session chat history while the tool window is open

## Environment configuration

Saidkick uses two env files:

- Plugin runtime API config from this plugin project's root `.env`
- Optional identity/tone values from the opened user's project `.env`

### Plugin project `.env` (required for LLM calls)

```dotenv
LLM_BASE_URL=https://api.openai.com/v1
LLM_API_KEY=
LLM_MODEL=gpt-4o-mini
LLM_TIMEOUT_SECONDS=20
```

### Opened user's project `.env` (optional identity values)

```dotenv
ASSISTANT_NAME=Saidkick
DEVELOPER_NAME=Developer
ASSISTANT_PERSONALITY=coach

IDLE_THRESHOLD_SECONDS=300
CHANGE_BURST_THRESHOLD=20
CHANGE_BURST_WINDOW_SECONDS=60
```

If values are missing, safe defaults are used.
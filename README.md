# SpigotLLM

SpigotLLM is a Java 8 Bukkit plugin for private Codex and Claude prompts from
the Minecraft console or in game. It is compiled against the 1.8.8 API, does
not declare a modern `api-version`, and uses no NMS, so the same JAR is intended
for Spigot-compatible servers from Minecraft 1.8.8 onward.

## Install

Build with `mvn clean package`, then copy
`target/SpigotLLM-1.2.0-SNAPSHOT.jar` into the server's `plugins` directory.
After the first start, use the real server console:

```text
sllm runtime install all
sllm access add <online-player|previously-seen-player|uuid>
```

Provider executables are installed only inside
`plugins/SpigotLLM/runtimes`. Codex assets are checked against OpenAI's
published SHA-256 digest. Claude's stable manifest is checked against
Anthropic's hard-coded release-key fingerprint and detached OpenPGP signature,
then the binary is checked against the signed SHA-256 value. Runtime updates
are manual and keep a rollback release:

```text
sllm runtime update all
sllm runtime rollback claude confirm
sllm runtime status all
```

An absolute executable can be supplied instead in `config.yml`. Automatic CLI
self-updates are disabled because the plugin manages runtime versions.

## Access control

The console is always allowed. A player must be both:

1. a current Minecraft operator; and
2. explicitly allowlisted by UUID in
   `plugins/SpigotLLM/authorized-operators.json`.

Only a `ConsoleCommandSender` can add, remove, or list authorized operators.
An OP—even an authorized one—cannot grant access:

```text
sllm access add <player|uuid>
sllm access remove <player|uuid>
sllm access list
```

No player is allowlisted by default. Command blocks, RCON-like non-console
senders, and non-player plugin senders are denied.

## Accounts and prompts

Every player UUID has separate accounts and session files. The console has its
own independent `server` identity.

```text
sllm account connect codex
sllm account connect claude
sllm account status <codex|claude>
sllm account disconnect <codex|claude> confirm

codex [chat|agent] <prompt>
claude [chat|agent] <prompt>
sllm prompt <codex|claude> <chat|agent> <prompt>
sllm cancel [codex|claude|all]
sllm more [page]
```

Codex uses device-code login. Claude uses `setup-token`; when it requests a
code, a player runs `/sllm account code claude` with no code in the command,
then sends the code as their next chat message. SpigotLLM cancels that message
before normal chat broadcast. The server console instead uses
`sllm account code claude <code>` directly.

Claude tokens are encrypted at rest with a plugin-local AES-GCM key. Codex is
forced to use its documented file credential store under that identity's
private `CODEX_HOME`, because the Codex CLI must be able to refresh its tokens.
Protect the whole Minecraft server directory and backups accordingly.

Responses and progress go only to the invoking player (or console). Prompt and
response bodies are not written to the plugin log. Provider CLIs retain their
own conversation transcripts in the identity directory.

## Reasoning effort

SpigotLLM does not force an effort level by default, so existing threads use
the selected provider/model default. Inspect or change the active named
thread's override with:

```text
sllm effort codex chat
sllm effort codex chat high
sllm effort claude agent xhigh
sllm effort claude agent default
codex effort high
claude effort agent xhigh
```

`level` is an alias for `effort`. Codex accepts `default`, `minimal`, `low`,
`medium`, `high`, and `xhigh`. Claude accepts `default`, `low`, `medium`,
`high`, `xhigh`, `max`, and `ultracode`; the active Claude model and CLI
version ultimately determine which levels are available. The override is
stored separately on every named chat/agent thread and is shown by
`sllm threads` and when a prompt starts. `default` (or `auto`) removes the
SpigotLLM override.

## Conversation threads

```text
codex threads [chat|agent]
codex thread current [chat|agent]
codex thread new [chat|agent] <name>
codex thread switch [chat|agent] <name>
codex thread rename [chat|agent] [old-name] <new-name>
codex thread delete [chat|agent] <name> confirm
```

The same commands work with `/claude`. Omitting the mode means `chat`.
`/sllm threads [codex|claude] [chat|agent]` lists across providers, and
`/sllm thread <provider> ...` provides the same management commands from the
main command. The older `/sllm session ...` syntax remains as a compatibility
alias.

Names contain 1–32 letters, numbers, underscores, or dashes. Chat and agent
threads are separate and persist across restarts. The active thread is marked
with `*` in listings. Deleting a name forgets the plugin's association;
provider CLI transcript retention still follows the provider's local storage
behavior.

## Agent-mode warning

`agent` mode deliberately launches the provider with approval prompts and
sandboxing bypassed. It can read, change, or delete anything accessible to the
Minecraft server operating-system account and can execute commands with that
account's privileges. It also receives a Minecraft console bridge with the
authority of the real server console. Only authorize operators who should
already have both levels of server access. `chat` mode disables Claude tools,
does not receive the console bridge, and gives Codex a read-only sandbox rooted
in a plugin-managed empty workspace.

## Agent console bridge

Codex and Claude automatically receive console access during an authorized
`agent` prompt. For example:

```text
codex agent Read the last 100 console lines and explain the newest error.
codex agent Run the list command, then tell me how many players are online.
claude agent Check the console for startup errors and run plugins if needed.
```

The agent reads the live log configured by `agent-console.log-file` (the
default is `logs/latest.log`). To send a command, it uses a private request and
response directory under `plugins/SpigotLLM/agent-console`. The plugin polls
only bridges belonging to currently active agent prompts and dispatches each
request synchronously through Bukkit's real `ConsoleCommandSender`. Requests
cannot be submitted through `chat` mode or after the agent prompt finishes.

Every dispatched command is written to the server log with the invoking
identity, provider, and named thread. The bridge intentionally does not add a
separate in-game console command: access is exercised by the provider agent and
still requires the invoking player to be both an OP and present in the
console-managed SpigotLLM allowlist.

## Tests

Run unit tests with `mvn test`. The optional live Anthropic signing-key check is:

```bash
mvn -Dspigotllm.integration=true -Dtest=RuntimeInstallerIntegrationTest test
```

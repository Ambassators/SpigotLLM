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

codex <prompt>
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
sllm effort codex agent
sllm effort codex agent high
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
codex threads
codex thread current
codex thread new
codex thread switch <name>
codex thread rename [old-name] <new-name>
codex thread delete <name> confirm
```

`/codex <prompt>` always runs in agent mode and keeps using the active thread.
`/codex thread new` switches to a fresh thread; its first prompt becomes a
Codex-style display title. A filesystem-safe ID derived from the same prompt is
shown by `/codex threads` for management commands. The selected title is shown
whenever a prompt starts. Claude keeps separate `chat` and `agent` modes and
requires a name when creating a thread; omitting its mode means `chat`.
In-game thread lists are clickable: switch and new-thread actions run directly,
while rename and confirmed-delete actions are placed in the chat input for
review. `/sllm` shows short hints for `/codex <message>`, `/codex threads`, and
`/codex thread new`, followed by this thread menu. `/sllm help` shows the full
command reference.
`/sllm threads [codex|claude] [chat|agent]` lists across providers, and
`/sllm thread <provider> ...` provides the same management commands from the
main command. The older `/sllm session ...` syntax remains as a compatibility
alias.

Names contain 1–32 letters, numbers, underscores, or dashes. Claude chat and
agent threads are separate; Codex exposes agent threads only. Threads persist
across restarts. The active thread is marked
with `*` in listings. Deleting a name forgets the plugin's association;
provider CLI transcript retention still follows the provider's local storage
behavior.

## Agent-mode warning

Codex always uses `agent` mode. Claude `agent` mode deliberately launches the
provider with approval prompts and sandboxing bypassed. It can read, change, or
delete anything accessible to the
Minecraft server operating-system account and can execute commands with that
account's privileges. It also receives a Minecraft console bridge with the
authority of the real server console. Only authorize operators who should
already have both levels of server access. Claude `chat` mode disables tools and
does not receive the console bridge.

Agent mode starts in the persistent `plugins/SpigotLLM/workspace` source
workspace. Codex and Claude can clone public GitHub repositories into separate
directories there, inspect and edit them, and use the console bridge to compare
plugin source with the plugins running on the server. Git must be installed and
available to the Minecraft server process. Private repositories require Git
credentials configured for that operating-system account; SpigotLLM does not
store GitHub credentials. Set `execution.agent-working-directory` to another
relative directory inside `plugins/SpigotLLM`, or to an explicit absolute path,
to override the location. The legacy default value `.` is migrated to the
plugin-local `workspace` directory.

When `agent-tools.enabled` is true, agent mode can additionally compile and run
arbitrary Java on Bukkit's primary thread and use deep reflection against live
server objects. This is intentionally **not a sandbox or security boundary**.
Bad generated code can disclose secrets, corrupt live state, deadlock the main
thread, crash the JVM, or make permanent filesystem and server changes. Java
code running on the primary thread cannot be forcibly interrupted. Use this
feature only with providers and operators that are trusted as fully as the
Minecraft server operating-system account.

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

### JSON tool protocol

Each active agent prompt receives a private, owner-scoped lease directory. A
client writes an entire request to a temporary file and atomically renames it
to:

```text
<lease>/requests/<id>.json
```

SpigotLLM writes the matching response atomically to
`<lease>/responses/<id>.json`. Asynchronous hook, command, task, and module
output is appended to the bounded `<lease>/events.jsonl` stream. The legacy
`command.request` console-command protocol remains available for compatibility.
Requests use this envelope:

```json
{
  "id": "server-state-1",
  "operation": "snapshot.server",
  "arguments": {},
  "lifecycle": "prompt"
}
```

Responses contain the same `id`, a `status` of `success` or `error`, and either
`result` or a structured `error`. Files larger than
`agent-tools.max-request-bytes`, malformed
JSON, duplicate IDs, unknown operations, and resources over the configured
owner limits are rejected without executing them. Runtime objects that cannot
be represented as JSON are returned as opaque handles usable only by the same
lease owner.

Available operation families are:

- `snapshot.server`, `snapshot.players`, `snapshot.worlds`, `snapshot.plugins`,
  `snapshot.memory`, `snapshot.scheduler`, and `snapshot.ticks`; plus
  `log.search`, `console.execute`, and `message.send`.
- `code.compile`, `code.run`, `code.runLater`, `code.runTimer`, and
  `code.cancel`: compile Java snippets away from the server thread, then run
  them on the primary thread through `BukkitRunnable`.
- `reflect.root`, `reflect.class`, `reflect.describe`, `reflect.get`,
  `reflect.set`, `reflect.invoke`, `reflect.construct`, `reflect.indexGet`,
  `reflect.indexSet`, and `reflect.release`: inspect or modify live objects,
  including inherited/private members, arrays, lists, and maps.
- `event.watch` and `event.await`: observe Bukkit events with priority,
  cancelled-event handling, property filters, selected captured fields, match
  limits, and timeouts. Event mutation belongs in a Java mini-module.
- `command.create` and related `command.*` operations: compile temporary Bukkit
  command callbacks with the live sender, label, arguments, aliases, usage, tab
  completions, invocation events, and an explicit sender-access policy.
- `resource.list`, `resource.inspect`, `resource.extend`, `resource.enable`,
  `resource.disable`, and `resource.remove`: manage owned hooks, commands,
  scheduled work, snippets, handles, and modules.
- `module.compile`, `module.install`, `module.enable`, `module.disable`, and
  `module.remove`: validate and manage isolated Java mini-modules. Persistent
  modules store their source and are recompiled after a restart.

All operations that touch Bukkit state, including reflection, are marshalled
onto the primary server thread. Compilation is performed on the configured
compiler worker. Main-thread executions taking longer than
`agent-tools.slow-main-thread-millis` are audited as slow.

### Java snippets

A one-shot request supplies a Java method body. The body receives
`MiniContext context`, Bukkit `Server server`, and `emit(Object)` for writing
JSON-safe values or handles to the response/event stream:

```json
{
  "id": "online-count",
  "operation": "code.run",
  "arguments": {
    "source": "emit(server.getOnlinePlayers().size()); return server.getOnlinePlayers().size();"
  },
  "lifecycle": "prompt"
}
```

Use `code.runLater` with a tick delay or `code.runTimer` with a delay, period,
and maximum run count for scheduled snippets. Use `code.cancel` with the
returned resource ID to stop scheduled work. One-shot snippets never survive a
restart. Behavior that must be restored after restart must be installed as a
persistent mini-module.

`command.create` uses the same compiled method-body model, but its trigger is a
real Bukkit command invocation instead of an immediate or scheduled run. Its
body additionally receives the live `CommandSender sender`, `String label`, and
`String[] args`:

```json
{
  "id": "move-command",
  "operation": "command.create",
  "arguments": {
    "resourceId": "move-premium-lb",
    "name": "movepremiumlb",
    "access": "authorized",
    "source": "context.sendMessage(sender, \"Running for \" + sender.getName()); return Boolean.TRUE;"
  },
  "lifecycle": "reboot"
}
```

The callback runs synchronously on Bukkit's primary thread. It can inspect or
cast `sender`, send a response with `context.sendMessage(sender, ...)`, and run
another command as that player with `context.dispatchCommand(sender, ...)`.
Passing `null` to `dispatchCommand` uses the server console instead. Returning
`Boolean.FALSE` tells Bukkit the command was not handled; every other value
marks it handled and is emitted to the resource's event stream. `source` is
required; `command.create` does not create static reply/console templates.

Reflection values can be ordinary JSON primitives, enums, UUIDs, arrays, or
opaque handles returned by another operation. Supplying exact parameter type
names makes overloaded method and constructor selection unambiguous. The
bridge walks inherited members and attempts private access, but JVM module
boundaries can still reject access. It does not use `Unsafe`, native memory,
instrumentation, or automatic `--add-opens` changes, and final/static-final
writes are not guaranteed to be supported by the running JVM.

### Java mini-modules

Mini-modules are complete Java compilation units compiled by the bundled
Janino compiler in isolated classloaders. The declared entry class implements
`MiniModule`; startup and shutdown run on Bukkit's primary thread. A minimal
module looks like:

```java
import dev.foreground.spigotllm.agent.code.MiniContext;
import dev.foreground.spigotllm.agent.code.MiniModule;
import org.bukkit.scheduler.BukkitRunnable;

public final class HeartbeatModule implements MiniModule {
    private MiniContext context;

    public void onEnable(MiniContext context) {
        this.context = context;
        context.runTimer(new BukkitRunnable() {
            public void run() {
                context.emit("online=" + context.server().getOnlinePlayers().size());
            }
        }, 0L, 200L);
    }

    public void onDisable() {
        context.emit("heartbeat stopped");
    }
}
```

`MiniContext` also provides tracked event-listener and temporary-command
registration, synchronous/delayed/repeating tasks, logging, messaging, command
dispatch, reflection handles, output emission, and direct Bukkit `Server`
access. Resources registered through the context are unregistered when the
module stops. An uncaught callback failure disables that module and emits an
error. A persistent module that cannot compile or start during boot is
quarantined without preventing SpigotLLM from enabling.

### Lifecycles and operator controls

The default lifecycle is `prompt`, which cleans resources when the originating
prompt succeeds, fails, is cancelled, or times out. `ttl` keeps a resource for
a requested duration up to `agent-tools.max-ttl-seconds`; `reboot` keeps it
until plugin/server shutdown; and `persistent` stores restorable mini-module
source across restarts. Reflection handles are always memory-only and never
survive plugin disable. Event output is capped by
`agent-tools.max-queued-events` and `agent-tools.max-event-stream-bytes`.
Resource ownership is scoped to the authorized identity plus provider thread,
so concurrent Codex and Claude threads cannot collide with or manage one
another's resources even when they choose the same resource ID.
Use a string for the non-TTL lifecycles. A TTL request uses, for example,
`"lifecycle": {"type": "ttl", "ttlSeconds": 300}`.

The console and authorized operators can inspect and manage runtime resources:

```text
sllm tools list
sllm tools inspect <owner> <resource-id>
sllm tools disable <owner> <resource-id>
sllm tools enable <owner> <resource-id>
sllm tools remove <owner> <resource-id>
sllm tools purge <transient|all> confirm
```

`purge` is console-only. Temporary command labels and aliases may not replace
existing commands. Their sender policy may allow console, players, OPs,
authorized SpigotLLM operators, a Bukkit permission, or everyone; the default
allows only the console and authorized operators. Creation, persistence,
registration, reflected member names, failures, and removal are audited, while
argument values, field values, prompt bodies, and responses are not logged.

## Tests

Run unit tests with `mvn test`. The optional live Anthropic signing-key check is:

```bash
mvn -Dspigotllm.integration=true -Dtest=RuntimeInstallerIntegrationTest test
```

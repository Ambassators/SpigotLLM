# SpigotLLM Agent Runtime API

Write each complete UTF-8 request atomically as `requests/<id>.json`. Read its
response from `responses/<id>.json`; asynchronous output is JSONL in
`events.jsonl`. IDs match `[A-Za-z0-9_-]{1,64}` and may be used once per lease.
The paths in the generated lease `README.txt` are generation-specific; never
reuse a request, response, or event path from a closed lease.

```json
{"id":"req-1","operation":"snapshot.server","arguments":{},"lifecycle":"prompt"}
```

Lifecycle is `prompt` (default), `reboot`, `persistent`, or
`{"type":"ttl","ttlSeconds":300}`. Persistent executable behavior must be a
module. Runtime object handles are valid only for their owning live lease.
Resource ownership includes the authorized identity and provider thread, so
parallel Codex and Claude threads cannot access one another's resources.

## Diagnostics

- `snapshot.server|players|worlds|plugins|memory|scheduler|ticks`: `{}`.
- `log.search`: `{"query":"error","maxLines":100,"maxBytes":1048576}`.
- `console.execute`: `{"command":"list"}`.
- `message.send`: `{"target":"console|broadcast|PlayerName","message":"text"}`.

## Java code

- `code.compile` / `code.run`: `{"source":"return server.getOnlinePlayers().size();"}`.
- `code.runLater`: source plus `resourceId` and `delayTicks`.
- `code.runTimer`: source plus `resourceId`, `delayTicks`, `periodTicks`, and
  optional `maxRuns` (`0` means unlimited).
- `code.cancel`: `{"resourceId":"..."}`.

Snippet source is a Java method body and must return an Object-compatible value.
It receives `MiniContext context`, Bukkit `Server server`, and `emit(value)`.
Compilation occurs off-thread; execution always uses a synchronous
`BukkitRunnable`. A main-thread hang cannot be forcibly interrupted.

## Reflection

Non-JSON objects return as `{"$handle":"h_...","type":"...","display":"..."}`.

- `reflect.root`: `{"root":"server"}`. Roots: `server`, `host`, `console`,
  `scheduler`, `pluginManager`, `runtime`, `thread`, or selector roots
  `{"root":"plugin","name":"Name"}`, `{"root":"player","player":"Name|UUID"}`,
  and `{"root":"world","world":"Name|UUID"}`.
- `reflect.class`: `{"name":"fully.qualified.Class","loader":"context|host|server|bootstrap|plugin","plugin":"Name"}`.
- `reflect.describe`: `{"target":{"$handle":"h_..."},"kind":"all|field|method|constructor","filter":"text","inherited":true,"offset":0,"limit":100}`.
- `reflect.get`: `{"target":{"$handle":"h_..."},"field":"fieldName"}`.
- `reflect.set`: previous fields plus `"value":...`; static members use a Class handle.
- `reflect.invoke`: `{"target":{"$handle":"h_..."},"method":"methodName","arguments":[],"parameterTypes":["java.lang.String"]}`. Omit `parameterTypes` for deterministic overload selection.
- `reflect.construct`: `{"target":{"$handle":"class-handle"},"arguments":[],"parameterTypes":[]}`.
- `reflect.indexGet`: target plus `index` for arrays/lists or `key` for maps.
- `reflect.indexSet`: target plus `index`/`key` and `value`.
- `reflect.release`: `{"target":{"$handle":"h_..."}}`.

Private/inherited/static members are supported where JVM access rules permit.
There is no Unsafe, instrumentation, native memory access, or automatic
`--add-opens` behavior.

## Event watches

`event.watch` and `event.await` accept:

```json
{
  "resourceId":"joins",
  "eventClass":"org.bukkit.event.player.PlayerJoinEvent",
  "priority":"MONITOR",
  "ignoreCancelled":true,
  "capture":["player.name","joinMessage"],
  "filters":[{"path":"player.name","operator":"equalsIgnoreCase","value":"Steve"}],
  "maxMatches":10,
  "timeoutTicks":200
}
```

Filter operators: `equals`, `equalsIgnoreCase`, `contains`, `startsWith`,
`endsWith`, `regex`, `exists`, and `missing`. `event.await` responds only on its
first match or timeout. Remove with `event.unwatch {"resourceId":"..."}`.

## Temporary commands and schedules

`command.create` accepts `resourceId`, `name`, `aliases`, `description`,
`usage`, `access`, `permission`, `permissionMessage`, `reply`,
`consoleCommands`, and static `tabCompletions`. Access is `authorized`
(default), `console`, `players`, `ops`, `permission`, or `everyone`. Reply and
command templates support `%sender%`, `%label%`, `%args%`, and `%arg0%` etc.
Remove with `command.remove`.

`schedule.once` / `schedule.repeat` accept `resourceId`, `delayTicks`,
`periodTicks`, `maxRuns`, and an action such as
`{"type":"command","command":"list"}` or
`{"type":"message","target":"broadcast","message":"Hi"}`. Cancel with
`schedule.cancel`.

## Mini-modules

- `module.compile` / `module.install`:
  `{"resourceId":"heartbeat","entryClass":"HeartbeatModule","source":"complete Java compilation unit"}`.
- `module.list`: `{}`.
- `module.enable|disable|remove`: `{"resourceId":"heartbeat"}`.

The entry class implements `dev.foreground.spigotllm.agent.code.MiniModule`.
`MiniContext` exposes `server`, `emit`, `listen`, `command`, `runSync`,
`runLater`, `runTimer`, `reflection`, handle retention, logging, command
dispatch, and messaging. Context-created resources are tracked and cleaned.
For a persistent module, `resource.inspect` exposes its stable `eventFile`
attribute; that JSONL stream remains the same before and after a restart.

## Resource management

- `resource.list`: optional `{"type":"module"}`.
- `resource.inspect|enable|disable|remove`: `{"resourceId":"..."}`.
- `resource.extend`: `{"resourceId":"...","ttlSeconds":300}` for TTL resources.

All code and reflection is unsandboxed and has the authority of the server
process. It can corrupt state, leak secrets, freeze the primary thread, crash
the JVM, or stop the server.

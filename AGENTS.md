# Gallery repo notes

## Repo boundary
- This repo owns the Android Gallery app, including the phone-hosted HTTP server UI and `OpenAiHttpServer.kt`.
- The HTTP server code should live here only. Do not recreate a second copy in `~/src/LiteRT-LM`.
- `~/src/LiteRT-LM` owns the runtime / engine / session implementation only.

## LiteRT-LM dependency
- Gallery consumes LiteRT-LM as an artifact, not by compiling directly against the source tree.
- If LiteRT-LM runtime changes are needed on the phone, rebuild and publish/install the artifact that Gallery resolves.
- If the phone still shows old behavior, check which LiteRT-LM artifact is actually being consumed before changing Gallery code again.

## Practical rule
- Runtime fixes go in LiteRT-LM.
- HTTP server UI/menus and request handling go in Gallery.
- Do not keep duplicate HTTP server implementations in both repos.

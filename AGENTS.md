# Gallery repo notes

## Repo split
- Gallery owns Android app, HTTP server UI, and `OpenAiHttpServer.kt`.
- LiteRT-LM owns runtime, engine, session, model execution.
- Do not copy HTTP server code into LiteRT-LM.

## LiteRT-LM on phone
- Gallery uses LiteRT-LM as artifact, not source tree.
- Use Android AAR / Android arm64 output, not host JVM jar.
- Local phone build needs both:
  - `bazel-bin/kotlin/java/com/google/ai/edge/litertlm/litertlm-jvm.jar`
  - `bazel-bin/kotlin/java/com/google/ai/edge/litertlm/jni/liblitertlm_jni.so`
- If phone shows old behavior, check which LiteRT-LM artifact Gallery really got.
- For phone rebuild/install, use `scripts/build_and_install_phone.sh`.

## Rules
- Runtime fix -> LiteRT-LM.
- App UI / HTTP handling -> Gallery.
- No duplicate HTTP server copies.
- Do not assume user wants less feature just because it is annoying.
- If you find bad assumption or build trap, update AGENTS.md right away.
- When a build is running, poll it no more often than every 90 seconds.
- Write notes short. Caveman short.

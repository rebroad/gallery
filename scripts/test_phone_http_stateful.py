#!/usr/bin/env python3
"""Regression test for the phone-hosted OpenAI-compatible HTTP server.

This talks to the live server on the phone and verifies:
- /health exposes the stateful HTTP response configuration
- multiple stateful response sessions can coexist
- multimodal requests are accepted over the stateful HTTP path
- the configured max cached session count is enforced
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import secrets
import subprocess
import sys
import tempfile
import time
import wave
from io import BytesIO
import xml.etree.ElementTree as ET
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any


DEFAULT_URL = "http://192.168.192.7:11435"
DEFAULT_EXPECTED_MAX_SESSIONS = 4
DEFAULT_SERVER_START_TIMEOUT_SECONDS = 180
GALLERY_MAIN_ACTIVITY = "com.google.aiedge.gallery/com.google.ai.edge.gallery.MainActivity"


@dataclass(frozen=True)
class HttpResult:
    status: int
    body: dict[str, Any]


def request_json(url: str, payload: dict[str, Any] | None = None, timeout: int = 30) -> HttpResult:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"},
        method="GET" if payload is None else "POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            status = int(getattr(resp, "status", 200))
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8")
        status = int(exc.code)
    try:
        body = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"non-JSON response from {url}: {raw!r}") from exc
    return HttpResult(status=status, body=body)


def run_adb(*args: str, timeout: int = 30) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["adb", *args],
        check=True,
        timeout=timeout,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def adb_shell(*args: str, timeout: int = 30) -> str:
    return run_adb("shell", *args, timeout=timeout).stdout.strip()


def dump_ui_tree() -> ET.Element:
    adb_shell("uiautomator", "dump", "/sdcard/window_dump.xml", timeout=30)
    xml_text = adb_shell("cat", "/sdcard/window_dump.xml", timeout=30)
    try:
        return ET.fromstring(xml_text)
    except ET.ParseError as exc:
        raise RuntimeError(f"failed to parse UI dump: {xml_text[:2000]!r}") from exc


def parse_bounds(bounds: str) -> tuple[int, int, int, int]:
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds.strip())
    if not match:
      raise ValueError(f"invalid bounds: {bounds!r}")
    return tuple(int(part) for part in match.groups())  # type: ignore[return-value]


def tap_bounds(bounds: str) -> None:
    x1, y1, x2, y2 = parse_bounds(bounds)
    adb_shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2), timeout=10)


def find_node(
    root: ET.Element,
    *,
    text: str | None = None,
    content_desc: str | None = None,
) -> ET.Element | None:
    parent_map = {child: parent for parent in root.iter() for child in parent}
    for node in root.iter("node"):
        if text is not None and node.attrib.get("text") != text:
            continue
        if content_desc is not None and node.attrib.get("content-desc") != content_desc:
            continue
        current = node
        while current is not None:
            if current.attrib.get("clickable") == "true":
                return current
            current = parent_map.get(current)
        return node
    return None


def tap_node(root: ET.Element, *, text: str | None = None, content_desc: str | None = None) -> bool:
    node = find_node(root, text=text, content_desc=content_desc)
    if node is None:
        return False
    bounds = node.attrib.get("bounds")
    if not bounds:
        return False
    tap_bounds(bounds)
    return True


def start_gallery_app() -> None:
    adb_shell("am", "start", "-W", "-n", GALLERY_MAIN_ACTIVITY, timeout=60)


def ensure_phone_server_started(url: str, timeout_seconds: int) -> None:
    start_gallery_app()
    deadline = time.monotonic() + timeout_seconds
    clicked_start = False
    while True:
        try:
            health = request_json(f"{url}/health", timeout=5)
            if health.status == 200 and health.body.get("status") == "ok":
                return
        except Exception:
            pass

        if time.monotonic() >= deadline:
            raise TimeoutError(f"server did not come up within {timeout_seconds} seconds")

        try:
            ui = dump_ui_tree()
            if not clicked_start:
                if tap_node(ui, content_desc="Menu"):
                    time.sleep(1.5)
                    continue
                if tap_node(ui, text="HTTP Server"):
                    time.sleep(2.5)
                    continue
                if tap_node(ui, text="Start server"):
                    clicked_start = True
                    time.sleep(3)
                    continue
                if tap_node(ui, text="Stop server"):
                    return
        except Exception:
            pass

        time.sleep(2)


def extract_response_text(payload: dict[str, Any]) -> str:
    output = payload.get("output")
    if not isinstance(output, list):
        return ""
    chunks: list[str] = []
    for item in output:
        if not isinstance(item, dict):
            continue
        content = item.get("content")
        if not isinstance(content, list):
            continue
        for chunk in content:
            if isinstance(chunk, dict) and chunk.get("type") == "output_text":
                text = chunk.get("text")
                if isinstance(text, str):
                    chunks.append(text)
    return "".join(chunks).strip()


def first_response_id(payload: dict[str, Any]) -> str:
    response_id = payload.get("id")
    if not isinstance(response_id, str) or not response_id.strip():
        raise RuntimeError(f"response had no id: {payload}")
    return response_id.strip()


def first_conversation_id(payload: dict[str, Any]) -> str:
    conversation = payload.get("conversation")
    if isinstance(conversation, str):
        conversation_id = conversation.strip()
        if conversation_id:
            return conversation_id
    elif isinstance(conversation, dict):
        conversation_id = conversation.get("id")
        if isinstance(conversation_id, str) and conversation_id.strip():
            return conversation_id.strip()
    raise RuntimeError(f"response had no conversation id: {payload}")


def make_memory_prompt(word: str) -> str:
    return (
        f"Remember this exact secret word for this response chain: {word}. "
        "Reply with exactly: stored."
    )


def make_recall_prompt() -> str:
    return "What exact secret word did I ask you to remember? Reply with only the word."


def make_silence_wav_base64(duration_ms: int = 250, sample_rate: int = 16000) -> str:
    frame_count = max(1, int(sample_rate * duration_ms / 1000.0))
    buffer = BytesIO()
    with wave.open(buffer, "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(b"\x00\x00" * frame_count)
    return base64.b64encode(buffer.getvalue()).decode("ascii")


def make_speech_wav_base64(text: str) -> str:
    with tempfile.NamedTemporaryFile(prefix="phone-http-", suffix=".wav", dir="/var/tmp", delete=False) as tmp:
        path = tmp.name
    try:
        subprocess.run(
            [
                "ffmpeg",
                "-hide_banner",
                "-loglevel",
                "error",
                "-f",
                "lavfi",
                "-i",
                f"flite=text={text}",
                "-y",
                path,
            ],
            check=True,
        )
        with open(path, "rb") as f:
            return base64.b64encode(f.read()).decode("ascii")
    finally:
        try:
            os.unlink(path)
        except FileNotFoundError:
            pass


def make_multimodal_prompt(word: str) -> str:
    return (
        f"Remember this exact secret word for this response chain: {word}. "
        "Reply with exactly: stored. The request also includes audio, so this is a multimodal turn."
    )


def send_stateful_response(
    url: str,
    model: str | None,
    prompt: str,
    conversation_id: str | None = None,
    audio_base64: str | None = None,
) -> tuple[str, str]:
    payload: dict[str, Any] = {
        "input": prompt,
        "stream": False,
        "temperature": 0,
        "max_tokens": 32,
    }
    if model:
        payload["model"] = model
    if conversation_id:
        payload["conversation"] = {"id": conversation_id}
    if audio_base64:
        payload["audio_base64"] = audio_base64
    result = request_json(f"{url}/v1/responses", payload)
    if result.status != 200:
        raise RuntimeError(f"responses request failed with {result.status}: {result.body}")
    response_id = first_response_id(result.body)
    conversation_id = first_conversation_id(result.body)
    text = extract_response_text(result.body)
    if not text:
        raise RuntimeError(f"responses request returned no text: {result.body}")
    return conversation_id, text


def send_audio_transcription(url: str, model: str | None, prompt: str, audio_base64: str) -> str:
    payload: dict[str, Any] = {
        "prompt": prompt,
        "audio_base64": audio_base64,
    }
    if model:
        payload["model"] = model
    result = request_json(f"{url}/v1/audio/transcriptions", payload)
    if result.status != 200:
        raise RuntimeError(f"audio transcription request failed with {result.status}: {result.body}")
    transcript = result.body.get("text")
    if not isinstance(transcript, str) or not transcript.strip():
        raise RuntimeError(f"audio transcription returned no text: {result.body}")
    return transcript.strip()


def assert_contains(actual: str, expected: str, context: str) -> None:
    if expected not in actual:
        raise AssertionError(f"{context}: expected to find {expected!r} in {actual!r}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default=DEFAULT_URL)
    parser.add_argument("--expected-max-sessions", type=int, default=DEFAULT_EXPECTED_MAX_SESSIONS)
    parser.add_argument(
        "--server-start-timeout-seconds",
        type=int,
        default=DEFAULT_SERVER_START_TIMEOUT_SECONDS,
    )
    args = parser.parse_args()

    ensure_phone_server_started(args.url, args.server_start_timeout_seconds)
    health = request_json(f"{args.url}/health")
    if health.status != 200:
        raise SystemExit(f"/health failed with {health.status}: {health.body}")

    health_body = health.body
    if health_body.get("status") != "ok":
        raise SystemExit(f"unexpected health status: {health_body}")
    if not bool(health_body.get("stateful_http_responses")):
        raise SystemExit(f"stateful HTTP responses are disabled: {health_body}")

    reported_max = int(health_body.get("max_cached_http_sessions") or 0)
    if reported_max < 1:
        raise SystemExit(f"invalid max_cached_http_sessions value: {health_body}")
    if reported_max != args.expected_max_sessions:
        raise SystemExit(
            f"unexpected max_cached_http_sessions={reported_max}; expected {args.expected_max_sessions}"
        )

    model = str(health_body.get("model") or "").strip() or None
    print(f"health ok: model={model or 'unknown'} max_cached_http_sessions={reported_max}")

    # Prove that two live sessions can coexist and be recalled independently.
    word_a = f"alpha-{secrets.token_hex(4)}"
    word_b = f"beta-{secrets.token_hex(4)}"
    resp_a_1, text_a_1 = send_stateful_response(args.url, model, make_memory_prompt(word_a))
    resp_b_1, text_b_1 = send_stateful_response(args.url, model, make_memory_prompt(word_b))
    print(f"session A initial response: conversation={resp_a_1} text={text_a_1!r}")
    print(f"session B initial response: conversation={resp_b_1} text={text_b_1!r}")

    resp_a_2, text_a_2 = send_stateful_response(args.url, model, make_recall_prompt(), resp_a_1)
    resp_b_2, text_b_2 = send_stateful_response(args.url, model, make_recall_prompt(), resp_b_1)
    print(f"session A recall response: conversation={resp_a_2} text={text_a_2!r}")
    print(f"session B recall response: conversation={resp_b_2} text={text_b_2!r}")
    assert_contains(text_a_2, word_a, "session A recall")
    assert_contains(text_b_2, word_b, "session B recall")

    # Prove that the stateful responses path handles multimodal input too.
    multimodal_word = f"gamma-{secrets.token_hex(4)}"
    multimodal_audio = make_speech_wav_base64(multimodal_word)
    resp_m_1, text_m_1 = send_stateful_response(
        args.url,
        model,
        make_multimodal_prompt(multimodal_word),
        audio_base64=multimodal_audio,
    )
    print(f"multimodal initial response: conversation={resp_m_1} text={text_m_1!r}")
    assert_contains(text_m_1.lower(), "stored", "multimodal initial response")

    # Prove the configured cache limit by filling it and then ensuring the oldest
    # retained response is no longer valid.
    retained_ids: list[str] = []
    retained_words: list[str] = []
    for i in range(reported_max + 1):
        word = f"slot-{i}-{secrets.token_hex(3)}"
        response_id, text = send_stateful_response(args.url, model, make_memory_prompt(word))
        retained_ids.append(response_id)
        retained_words.append(word)
        print(f"slot {i + 1} initial response: conversation={response_id} text={text!r}")

    oldest_id = retained_ids[0]
    oldest_word = retained_words[0]
    eviction_result = request_json(
        f"{args.url}/v1/responses",
        {
            "model": model,
            "input": make_recall_prompt(),
            "conversation": {"id": oldest_id},
            "stream": False,
        },
    )
    if eviction_result.status not in (404, 410):
        raise SystemExit(
            f"expected oldest response id to be evicted, got {eviction_result.status}: {eviction_result.body}"
        )
    print(f"oldest cached response correctly evicted after {reported_max + 1} sessions: {oldest_id} ({oldest_word!r})")

    # Prove the separate audio transcription path works against the live phone model.
    speech_sample = make_speech_wav_base64("hello audio smoke test")
    transcript = send_audio_transcription(
        args.url,
        model,
        "Please transcribe the audio message verbatim.",
        speech_sample,
    )
    print(f"audio transcription smoke transcript: {transcript!r}")

    print("stateful HTTP regression test passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

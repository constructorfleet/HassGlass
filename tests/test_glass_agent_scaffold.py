"""Tests for the Glass Agent project scaffold."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AGENT = ROOT / "apps" / "glass_agent"


def test_glass_agent_gradle_project_exists() -> None:
    assert (ROOT / "settings.gradle.kts").read_text().count(":apps:glass_agent") == 1
    assert (ROOT / "build.gradle.kts").exists()
    assert (AGENT / "build.gradle.kts").exists()
    assert (AGENT / "src" / "main" / "AndroidManifest.xml").exists()


def test_glass_agent_protocol_codec_sources_exist() -> None:
    base = AGENT / "src" / "main" / "java" / "dev" / "hassglass" / "agent"
    assert (base / "protocol" / "ProtocolCodec.kt").exists()
    assert (base / "protocol" / "ProtocolModels.kt").exists()
    assert (base / "HassGlassAgentService.kt").exists()


def test_glass_agent_protocol_codec_tests_exist() -> None:
    test_file = (
        AGENT
        / "src"
        / "test"
        / "java"
        / "dev"
        / "hassglass"
        / "agent"
        / "protocol"
        / "ProtocolCodecTest.kt"
    )
    text = test_file.read_text()
    assert "encodesAndDecodesMicAudioFrame" in text
    assert "encodesAndDecodesHelloMessage" in text

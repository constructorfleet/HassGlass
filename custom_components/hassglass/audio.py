"""Audio stream adapters for Assist pipeline integration."""

from __future__ import annotations

from collections.abc import AsyncGenerator
from importlib import import_module
from typing import TYPE_CHECKING, Any

from homeassistant.components import stt
from homeassistant.core import Context

from .const import CONF_PIPELINE_ID
from .device import DeviceBus, IncomingFrame
from .protocol import AudioChannel, MessageType

if TYPE_CHECKING:
    from homeassistant.core import HomeAssistant

    from .device import GlassesRuntime
    from .hub import HassGlassHub
    from .pipeline_bridge import PipelineBridge


class _FallbackPipelineStage:
    """Test-only stand-in used when the pipeline function is monkeypatched."""

    STT = "stt"
    TTS = "tts"


PipelineStage: Any = _FallbackPipelineStage
async_pipeline_from_audio_stream: Any | None = None


class MicAudioStream:
    """Expose inbound mic frames from a device bus as an async byte stream."""

    def __init__(self, bus: DeviceBus) -> None:
        self._bus = bus

    async def chunks(self) -> AsyncGenerator[bytes]:
        """Yield mic-up payloads until the agent sends ``audio.stop``."""
        queue = self._bus.subscribe()
        try:
            while True:
                frame: IncomingFrame = await queue.get()
                if frame.audio is not None and frame.audio.channel is AudioChannel.MIC_UP:
                    yield frame.audio.payload
                elif frame.message is not None and frame.message.type is MessageType.AUDIO_STOP:
                    return
        finally:
            self._bus.unsubscribe(queue)


async def run_assist_pipeline(
    hass: HomeAssistant,
    hub: HassGlassHub,
    bridge: PipelineBridge,
    runtime: GlassesRuntime,
    *,
    wake_word_phrase: str | None = None,
) -> None:
    """Feed one glasses mic stream into Home Assistant's Assist pipeline."""
    device_id = runtime.record.device_id
    options = hub.resolved_options_for(device_id)
    mic_stream = MicAudioStream(hub.bus_for(device_id))
    pipeline_func = _get_async_pipeline_from_audio_stream()

    def _event_callback(event: object) -> None:
        hass.async_create_task(bridge.handle_event(device_id, event))

    await pipeline_func(
        hass,
        context=Context(),
        event_callback=_event_callback,
        stt_metadata=stt.SpeechMetadata(
            language=hass.config.language,
            format=stt.AudioFormats.WAV,
            codec=stt.AudioCodecs.PCM,
            bit_rate=stt.AudioBitRates.BITRATE_16,
            sample_rate=stt.AudioSampleRates.SAMPLERATE_16000,
            channel=stt.AudioChannels.CHANNEL_MONO,
        ),
        stt_stream=mic_stream.chunks(),
        wake_word_phrase=wake_word_phrase,
        pipeline_id=options.get(CONF_PIPELINE_ID),
        device_id=device_id,
        start_stage=PipelineStage.STT,
        end_stage=PipelineStage.TTS,
    )


def _get_async_pipeline_from_audio_stream() -> Any:
    """Lazy-load Assist so HassGlass can import without optional voice deps."""
    global PipelineStage, async_pipeline_from_audio_stream  # noqa: PLW0603

    if async_pipeline_from_audio_stream is None:
        assist_pipeline = import_module("homeassistant.components.assist_pipeline")
        PipelineStage = assist_pipeline.PipelineStage
        async_pipeline_from_audio_stream = assist_pipeline.async_pipeline_from_audio_stream

    return async_pipeline_from_audio_stream

"""Tests for mapping Assist replies into HUD cards."""

from __future__ import annotations

from custom_components.hassglass.card_mapping import card_from_pipeline_reply


def test_weather_reply_maps_to_icon_text_card() -> None:
    card = card_from_pipeline_reply("The weather is 62°F and rainy.")

    assert card == {
        "kind": "icon_text",
        "title": "Weather",
        "body": "The weather is 62°F and rainy.",
        "icon": "weather-partly-cloudy",
    }


def test_multiline_reply_maps_to_list_card() -> None:
    card = card_from_pipeline_reply("Shopping list:\n- milk\n- eggs\n- bread\n- coffee\n- tea")

    assert card == {
        "kind": "list",
        "title": "Shopping list",
        "items": ["milk", "eggs", "bread", "coffee"],
    }


def test_music_reply_maps_to_media_card() -> None:
    card = card_from_pipeline_reply("Playing Kind of Blue by Miles Davis.")

    assert card == {
        "kind": "media",
        "title": "Kind of Blue",
        "subtitle": "Miles Davis",
    }


def test_generic_reply_maps_to_toast_card() -> None:
    card = card_from_pipeline_reply("Done.")

    assert card == {"kind": "toast", "text": "Done.", "severity": "info"}

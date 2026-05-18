"""Shared pytest fixtures for HassGlass tests."""

from __future__ import annotations

import pytest


@pytest.fixture(autouse=True)
def auto_enable_custom_integrations(enable_custom_integrations: object) -> None:
    """Enable loading of the custom_components folder under test."""
    return

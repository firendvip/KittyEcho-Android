from __future__ import annotations

from typing import Any, Mapping

from .contracts import ContractError


def reveal_winner(
    selection: Mapping[str, Any],
    winner_report: Mapping[str, Any],
    private_map: Mapping[str, Any],
    public_plan: Mapping[str, Any],
    registry: Mapping[str, Any],
) -> dict[str, Any]:
    """Fail closed until a Phase B trusted Android runner exists.

    The arguments intentionally remain part of the CLI contract so Phase B can
    add attestation-backed validation without changing operator-facing inputs.
    Host-authored JSON cannot establish runner, build, artifact, or telemetry
    trust, so Phase A must not inspect flags and accidentally reveal a model.
    """
    del selection, winner_report, private_map, public_plan, registry
    raise ContractError(
        "Phase A reveal is disabled: Phase B must verify the trusted Android "
        "runner implementation, build, artifacts, and telemetry attestation"
    )

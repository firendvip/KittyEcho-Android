from __future__ import annotations

import hashlib
import json
import unicodedata
from dataclasses import asdict, dataclass


@dataclass(frozen=True)
class NormalizationConfig:
    profile_id: str = "zh-normalization-v1"
    unicode_form: str = "NFKC"
    whitespace: str = "remove"
    punctuation: str = "remove"
    latin_case: str = "lower"
    numbers: str = "unicode_decimal_to_ascii"

    def __post_init__(self) -> None:
        allowed = {
            "unicode_form": {"NONE", "NFC", "NFKC"},
            "whitespace": {"remove", "collapse", "preserve"},
            "punctuation": {"remove", "preserve"},
            "latin_case": {"lower", "preserve"},
            "numbers": {"unicode_decimal_to_ascii", "preserve"},
        }
        for field, choices in allowed.items():
            value = getattr(self, field)
            if value not in choices:
                raise ValueError(f"{field} must be one of {sorted(choices)}")
        if self.profile_id != "zh-normalization-v1":
            raise ValueError("profile_id must be zh-normalization-v1")

    @classmethod
    def from_dict(cls, value: dict[str, str]) -> "NormalizationConfig":
        return cls(**value)

    def to_dict(self) -> dict[str, str]:
        return asdict(self)

    def fingerprint(self) -> str:
        encoded = json.dumps(
            self.to_dict(),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        return hashlib.sha256(encoded).hexdigest()


def _normalize_decimal_digits(text: str) -> str:
    output = []
    for character in text:
        try:
            output.append(str(unicodedata.decimal(character)))
        except (TypeError, ValueError):
            output.append(character)
    return "".join(output)


def normalize_text(text: str, config: NormalizationConfig) -> str:
    if not isinstance(text, str):
        raise TypeError("text must be a string")
    normalized = text
    if config.unicode_form != "NONE":
        normalized = unicodedata.normalize(config.unicode_form, normalized)
    if config.numbers == "unicode_decimal_to_ascii":
        normalized = _normalize_decimal_digits(normalized)
    if config.latin_case == "lower":
        normalized = normalized.lower()
    if config.punctuation == "remove":
        normalized = "".join(
            character
            for character in normalized
            if not unicodedata.category(character).startswith("P")
        )
    if config.whitespace == "remove":
        normalized = "".join(normalized.split())
    elif config.whitespace == "collapse":
        normalized = " ".join(normalized.split())
    return normalized

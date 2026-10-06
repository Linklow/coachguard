"""Feature extraction for the host-based phishing link model.

This module is the specification of the model's input. The Kotlin implementation in
coachguard-links (HostFeatures.kt) must produce exactly the same features for every URL;
a golden-file test in that module checks this.

Design choices that keep the two implementations identical:
- only ASCII letters are lower-cased, so Unicode case rules cannot differ between languages;
- strings are processed as Unicode code points;
- hashed features use CRC-32 of the UTF-8 key, available as zlib.crc32 and java.util.zip.CRC32.

Only the host is used. Scheme, "www.", path, query and fragment are ignored on purpose:
in the training data they encode how each source formatted its URLs rather than whether a
site is malicious (see reports/phishing-model-report.md).
"""

from __future__ import annotations

import math
import re
import zlib
from dataclasses import dataclass

FEATURE_SPEC_VERSION = 1
NGRAM_SIZES = (3, 4, 5)
BUCKETS = 1 << 18

NUMERIC_FEATURES = (
    "host_length",
    "label_count",
    "hyphen_count",
    "digit_count",
    "max_label_length",
    "entropy",
    "is_ip",
    "has_punycode",
    "has_userinfo",
    "has_port",
)

_IPV4 = re.compile(r"[0-9]{1,3}(\.[0-9]{1,3}){3}")


@dataclass(frozen=True)
class ParsedHost:
    host: str
    has_userinfo: bool
    has_port: bool
    is_ip: bool


def lower_ascii(text: str) -> str:
    return "".join(chr(ord(c) + 32) if "A" <= c <= "Z" else c for c in text)


def parse_host(url: str) -> ParsedHost | None:
    """Extracts the canonical host from a URL, or None if there is no host."""
    text = url.strip()
    scheme_end = text.find("://")
    if scheme_end >= 0:
        text = text[scheme_end + 3:]

    end = len(text)
    for delimiter in ("/", "?", "#", "\\"):
        index = text.find(delimiter)
        if 0 <= index < end:
            end = index
    authority = text[:end]

    has_userinfo = "@" in authority
    if has_userinfo:
        authority = authority[authority.rfind("@") + 1:]

    if authority.startswith("["):
        close = authority.find("]")
        host = authority[1:close] if close > 0 else authority[1:]
        rest = authority[close + 1:] if close > 0 else ""
        has_port = rest.startswith(":") and len(rest) > 1
        is_ip = True
    else:
        colon = authority.rfind(":")
        if colon >= 0:
            has_port = len(authority) > colon + 1
            host = authority[:colon]
        else:
            has_port = False
            host = authority
        host = lower_ascii(host)
        while host.endswith("."):
            host = host[:-1]
        is_ip = _IPV4.fullmatch(host) is not None

    host = lower_ascii(host)
    if host.startswith("www."):
        host = host[4:]
    if not host:
        return None
    return ParsedHost(host=host, has_userinfo=has_userinfo, has_port=has_port, is_ip=is_ip)


def entropy(text: str) -> float:
    counts: dict[str, int] = {}
    for c in text:
        counts[c] = counts.get(c, 0) + 1
    total = len(text)
    return -sum((n / total) * math.log2(n / total) for n in counts.values())


def numeric_features(parsed: ParsedHost) -> list[float]:
    host = parsed.host
    labels = host.split(".")
    return [
        float(len(host)),
        float(len(labels)),
        float(host.count("-")),
        float(sum(1 for c in host if "0" <= c <= "9")),
        float(max(len(label) for label in labels)),
        entropy(host),
        1.0 if parsed.is_ip else 0.0,
        1.0 if "xn--" in host else 0.0,
        1.0 if parsed.has_userinfo else 0.0,
        1.0 if parsed.has_port else 0.0,
    ]


def token_keys(parsed: ParsedHost) -> list[str]:
    """Hashed-feature keys: character n-grams of the host plus whole-label tokens. Sorted, no duplicates."""
    host = parsed.host
    keys: set[str] = set()
    marked = "^" + host + "$"
    for n in NGRAM_SIZES:
        for start in range(len(marked) - n + 1):
            keys.add("c:" + marked[start:start + n])
    labels = host.split(".")
    if len(labels) >= 2:
        keys.add("t:" + labels[-1])
        keys.add("s:" + labels[-2] + "." + labels[-1])
    for label in labels[:-1]:
        keys.add("l:" + label)
    return sorted(keys)


def bucket(key: str) -> int:
    return zlib.crc32(key.encode("utf-8")) % BUCKETS


def is_popular(
    parsed: ParsedHost,
    popular_domains: set[str],
    abused_hosts: set[str],
    boundaries: set[str],
) -> bool:
    """True if the host is, or is under, a popular registered domain and was not itself seen hosting phishing.

    Matching walks the host's label-boundary suffixes from longest to shortest, so "mail.google.com"
    matches "google.com" while "google.com.evil.example" does not. The walk stops at a boundary: a
    public suffix inside a popular domain (such as web.core.windows.net inside windows.net), below
    which names belong to the platform's customers rather than to the popular domain's owner.
    """
    if parsed.is_ip or parsed.host in abused_hosts:
        return False
    labels = parsed.host.split(".")
    for i in range(len(labels) - 1):
        suffix = ".".join(labels[i:])
        if suffix in boundaries:
            return False
        if suffix in popular_domains:
            return True
    return False

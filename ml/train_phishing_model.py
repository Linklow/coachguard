"""Trains, evaluates and exports the host-based phishing link model.

Usage (from the ml/ directory, with the virtual environment):
    python train_phishing_model.py

Inputs in data/ (not committed; see README.md for sources):
    PhiUSIIL_Phishing_URL_Dataset.csv   UCI, CC BY 4.0
    public_suffix_list.dat              publicsuffix.org, MPL 2.0
    umbrella/top-1m.csv                 Cisco Umbrella popularity list
    openphish-*.txt                     OpenPhish community feed (evaluation only)

Outputs:
    ../coachguard-links/src/main/resources/.../host-model.bin   model used on the device
    ../coachguard-links/src/test/resources/golden-predictions.tsv  parity data for Kotlin tests
    reports/phishing-model-report.md and reports/metrics.json
"""

from __future__ import annotations

import glob
import json
from array import array
import random
import struct
import time
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

import numpy as np
import pandas as pd
from scipy.sparse import csr_matrix, hstack
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, average_precision_score, roc_auc_score
from sklearn.model_selection import GroupShuffleSplit, train_test_split

import url_features as uf

SEED = 42
UMBRELLA_LIMIT = 60_000
UMBRELLA_PER_DOMAIN = 20
C_GRID = (0.003, 0.01, 0.03, 0.1, 0.3)
FPR_HIGH = 0.01
FPR_MEDIUM = 0.05
POPULAR_LIMIT = 10_000
# A registered domain with this many distinct phishing hosts in the training data behaves like a
# platform where anyone can create subdomains, so it is never treated as popular.
PLATFORM_PHISHING_HOSTS = 10
# Link shorteners rank high in link-based popularity lists, but a short link can lead anywhere,
# so it must never be vouched for as popular.
URL_SHORTENERS = frozenset({
    "bit.ly", "bitly.com", "t.co", "goo.gl", "tinyurl.com", "ow.ly", "is.gd", "v.gd", "buff.ly",
    "rebrand.ly", "cutt.ly", "shorturl.at", "rb.gy", "t.ly", "tiny.cc", "lnkd.in", "s.id", "bit.do",
    "shorte.st", "adf.ly", "soo.gd", "clck.ru", "u.to", "qr.net", "short.io", "bl.ink", "tr.im",
    "x.co", "trib.al", "dlvr.it", "fb.me", "wp.me", "amzn.to", "youtu.be", "t.me", "wa.me",
    "linktr.ee", "lnk.to", "smarturl.it", "spoti.fi", "apple.co", "g.co", "forms.gle", "msft.it",
})

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
REPORTS = ROOT / "reports"
MODEL_PATH = ROOT.parent / "coachguard-links/src/main/resources/io/github/linklow/coachguard/links/host-model.bin"
POPULAR_PATH = MODEL_PATH.parent / "popular-domains.txt"
GOLDEN_PATH = ROOT.parent / "coachguard-links/src/test/resources/golden-predictions.tsv"

_HOST_CHARS = set("abcdefghijklmnopqrstuvwxyz0123456789.-")


def log(message: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


# ---------------------------------------------------------------- public suffix list

def load_psl(path: Path) -> tuple[set[str], set[str], set[str], set[str]]:
    """Rules, exceptions, wildcard parents, and the subset of rules and wildcard parents from the
    PSL's private section (suffixes run by companies such as cloud platforms, not registries)."""
    rules, exceptions, wildcards, private = set(), set(), set(), set()
    in_private = False
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if "===BEGIN PRIVATE DOMAINS===" in line:
            in_private = True
        if not line or line.startswith("//"):
            continue
        rule = line.split()[0].lower()
        if rule.startswith("!"):
            exceptions.add(rule[1:])
        elif rule.startswith("*."):
            wildcards.add(rule[2:])
            if in_private:
                private.add(rule)
        else:
            rules.add(rule)
            if in_private:
                private.add(rule)
    return rules, exceptions, wildcards, private


def is_public_suffix(host: str, psl) -> bool:
    """True for hosts that are themselves public suffixes, such as co.uk or appspot.com, where anyone can register names."""
    rules, exceptions, wildcards, _ = psl
    if host in exceptions:
        return False
    parent = host.split(".", 1)[1] if "." in host else ""
    return host in rules or parent in wildcards


def is_platform_name(domain: str, psl) -> bool:
    """True if a registered domain sits directly under a private-section suffix, i.e. it is an
    account on a platform (z1.web.core.windows.net, foo.github.io) rather than a registered name."""
    private = psl[3]
    if "." not in domain:
        return False
    suffix = domain.split(".", 1)[1]
    parent = suffix.split(".", 1)[1] if "." in suffix else ""
    return suffix in private or ("*." + parent) in private


def registered_domain(host: str, psl) -> str:
    """eTLD+1 of host per the Public Suffix List algorithm; the host itself for IPs and suffixes."""
    rules, exceptions, wildcards, _ = psl
    labels = host.split(".")
    suffix_start = len(labels) - 1  # the implicit "*" rule
    for i in range(len(labels)):
        candidate = ".".join(labels[i:])
        if candidate in exceptions:
            suffix_start = i + 1
            break
        if candidate in rules or (i + 1 < len(labels) and ".".join(labels[i + 1:]) in wildcards):
            suffix_start = i
            break
    if suffix_start == 0:
        return host
    return ".".join(labels[suffix_start - 1:])


# ---------------------------------------------------------------- data

@dataclass
class Example:
    url: str
    parsed: uf.ParsedHost
    label: int  # 1 = phishing
    source: str
    group: str


def build_dataset(psl) -> list[Example]:
    log("loading PhiUSIIL")
    raw = pd.read_csv(DATA / "PhiUSIIL_Phishing_URL_Dataset.csv", usecols=["URL", "label"])
    by_host: dict[str, tuple[str, uf.ParsedHost, int]] = {}
    conflicting: set[str] = set()
    for url, label in zip(raw.URL, raw.label):
        parsed = uf.parse_host(url)
        if parsed is None:
            continue
        phishing = 1 if label == 0 else 0  # PhiUSIIL: 1 = legitimate, 0 = phishing
        previous = by_host.get(parsed.host)
        if previous is None:
            by_host[parsed.host] = (url, parsed, phishing)
        elif previous[2] != phishing:
            conflicting.add(parsed.host)
    for host in conflicting:
        del by_host[host]
    log(f"  {len(by_host)} unique hosts, {len(conflicting)} dropped as labelled both ways")

    examples = [
        Example(url, parsed, label, "phiusiil", ip_or_domain(parsed, psl))
        for url, parsed, label in by_host.values()
    ]
    phishing_hosts = {e.parsed.host for e in examples if e.label == 1}
    legit_hosts = {e.parsed.host for e in examples if e.label == 0}
    legit_domains = {e.group for e in examples if e.label == 0}
    phishing_domains = {e.group for e in examples if e.label == 1}

    log("selecting legitimate subdomain hosts from Umbrella")
    umbrella = pd.read_csv(DATA / "umbrella" / "top-1m.csv", header=None, names=["rank", "host"])
    candidates = []
    for host in umbrella.host.astype(str):
        parsed = uf.parse_host(host)
        if parsed is None or parsed.is_ip or not set(parsed.host) <= _HOST_CHARS:
            continue
        # DNS lookups such as blocklist queries (4.3.2.1.zen.spamhaus.org) are popular names but not links.
        if sum(label.isdigit() for label in parsed.host.split(".")) >= 2:
            continue
        domain = registered_domain(parsed.host, psl)
        if parsed.host == domain or domain not in legit_domains or domain in phishing_domains:
            continue
        if parsed.host in phishing_hosts or parsed.host in legit_hosts:
            continue
        candidates.append(Example(parsed.host, parsed, 0, "umbrella", domain))
    random.Random(SEED).shuffle(candidates)
    # A few services contribute thousands of machine-generated subdomains (meeting rooms, sync
    # servers); capping each registered domain keeps them from dominating the legitimate set.
    per_domain: dict[str, int] = {}
    selected = []
    for example in candidates:
        if per_domain.get(example.group, 0) < UMBRELLA_PER_DOMAIN and len(selected) < UMBRELLA_LIMIT:
            per_domain[example.group] = per_domain.get(example.group, 0) + 1
            selected.append(example)
    log(f"  {len(candidates)} eligible, {len(selected)} used from {len(per_domain)} registered domains")
    return examples + selected


def ip_or_domain(parsed: uf.ParsedHost, psl) -> str:
    return parsed.host if parsed.is_ip else registered_domain(parsed.host, psl)


@dataclass
class PopularList:
    domains: set[str]
    abused_hosts: set[str]
    boundaries: set[str]
    excluded_platforms: list[tuple[str, int]]

    def contains(self, parsed: uf.ParsedHost) -> bool:
        return uf.is_popular(parsed, self.domains, self.abused_hosts, self.boundaries)

    def mask(self, parsed_hosts: list[uf.ParsedHost]) -> np.ndarray:
        return np.array([self.contains(p) for p in parsed_hosts])


def build_popular_list(psl, training_phishing_hosts: set[str]) -> PopularList:
    """The most popular registered domains in the Majestic Million (CC BY 3.0), minus link
    shorteners and platforms that host phishing.

    The phishing-based exclusions use training data only, so the test split stays unseen.
    """
    phishing_per_domain: dict[str, int] = {}
    for host in training_phishing_hosts:
        domain = registered_domain(host, psl)
        phishing_per_domain[domain] = phishing_per_domain.get(domain, 0) + 1

    majestic = pd.read_csv(DATA / "majestic_million.csv", usecols=["GlobalRank", "Domain"])
    domains: list[str] = []
    seen: set[str] = set()
    excluded: list[tuple[str, int]] = []
    for host in majestic.sort_values("GlobalRank").Domain.astype(str):
        parsed = uf.parse_host(host)
        if parsed is None or parsed.is_ip or not set(parsed.host) <= _HOST_CHARS:
            continue
        domain = registered_domain(parsed.host, psl)
        # A public suffix (appspot.com, s3.amazonaws.com, ...) is a platform, never a popular domain:
        # matching it would vouch for every name anyone registers under it.
        if domain in seen or "." not in domain or is_public_suffix(domain, psl) or is_platform_name(domain, psl):
            continue
        if domain in URL_SHORTENERS:
            seen.add(domain)
            continue
        seen.add(domain)
        if phishing_per_domain.get(domain, 0) >= PLATFORM_PHISHING_HOSTS:
            excluded.append((domain, phishing_per_domain[domain]))
            continue
        domains.append(domain)
        if len(domains) == POPULAR_LIMIT:
            break
    popular = set(domains)
    abused = {h for h in training_phishing_hosts if registered_domain(h, psl) in popular}

    # Public suffixes nested inside popular domains (web.core.windows.net in windows.net,
    # s3.amazonaws.com in amazonaws.com): names below them belong to the platform's customers.
    rules, _, wildcards, _ = psl
    boundaries = set()
    for suffix in rules | wildcards:
        labels = suffix.split(".")
        if any(".".join(labels[i:]) in popular for i in range(1, len(labels))):
            boundaries.add(suffix)
    return PopularList(popular, abused, boundaries, excluded)


def export_popular_list(popular: PopularList) -> None:
    lines = [
        "# Popular registered domains (+), hosts under them seen hosting phishing (-), and public suffixes",
        "# inside popular domains (!) below which names belong to other parties.",
        f"# Built by ml/train_phishing_model.py on {time.strftime('%Y-%m-%d')}.",
        "# Popular domains: Majestic Million by Majestic (https://majestic.com/reports/majestic-million),",
        "#   licensed under CC BY 3.0 (https://creativecommons.org/licenses/by/3.0/); top entries only.",
        "# Boundaries: Public Suffix List (https://publicsuffix.org), MPL 2.0 (https://mozilla.org/MPL/2.0/).",
        "# Abused hosts: derived from PhiUSIIL (Prasad and Chandra, 2024), CC BY 4.0.",
    ]
    lines += [f"+{d}" for d in sorted(popular.domains)]
    lines += [f"-{h}" for h in sorted(popular.abused_hosts)]
    lines += [f"!{b}" for b in sorted(popular.boundaries)]
    POPULAR_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


# ---------------------------------------------------------------- features and model

def vectorize(parsed_hosts: list[uf.ParsedHost]) -> tuple[csr_matrix, np.ndarray]:
    """Binary hashed features (a bucket hit by several keys still counts once) and raw numeric features."""
    indptr, indices = array("q", [0]), array("i")
    numeric = np.empty((len(parsed_hosts), len(uf.NUMERIC_FEATURES)))
    for row, parsed in enumerate(parsed_hosts):
        indices.extend(sorted({uf.bucket(key) for key in uf.token_keys(parsed)}))
        indptr.append(len(indices))
        numeric[row] = uf.numeric_features(parsed)
    index_array = np.frombuffer(indices, dtype=np.int32)
    hashed = csr_matrix(
        (np.ones(len(index_array)), index_array, np.frombuffer(indptr, dtype=np.int64)),
        shape=(len(parsed_hosts), uf.BUCKETS),
    )
    return hashed, numeric


@dataclass
class HostModel:
    bias: float
    hashed_weights: np.ndarray
    numeric_mean: np.ndarray
    numeric_std: np.ndarray
    numeric_weights: np.ndarray
    threshold_medium: float = 0.5
    threshold_high: float = 0.5

    def scores(self, hashed: csr_matrix, numeric: np.ndarray) -> np.ndarray:
        z = self.bias + hashed @ self.hashed_weights + ((numeric - self.numeric_mean) / self.numeric_std) @ self.numeric_weights
        return 1.0 / (1.0 + np.exp(-z))


def fit(hashed: csr_matrix, numeric: np.ndarray, labels: np.ndarray, c: float) -> HostModel:
    mean = numeric.mean(axis=0)
    std = numeric.std(axis=0)
    std[std == 0] = 1.0
    features = hstack([hashed, csr_matrix((numeric - mean) / std)]).tocsr()
    model = LogisticRegression(C=c, solver="liblinear", class_weight="balanced", max_iter=2000)
    model.fit(features, labels)
    weights = model.coef_.ravel()
    return HostModel(
        bias=float(model.intercept_[0]),
        hashed_weights=weights[: uf.BUCKETS].copy(),
        numeric_mean=mean,
        numeric_std=std,
        numeric_weights=weights[uf.BUCKETS:].copy(),
    )


def threshold_at_fpr(legit_scores: np.ndarray, fpr: float) -> float:
    """Smallest threshold whose false positive rate on these legitimate scores is at most fpr."""
    return float(np.quantile(legit_scores, 1.0 - fpr, method="higher")) + 1e-12


def quantize(model: HostModel, bits: int) -> tuple[HostModel, float]:
    levels = (1 << (bits - 1)) - 1
    scale = float(np.abs(model.hashed_weights).max()) / levels
    quantized = np.clip(np.round(model.hashed_weights / scale), -levels, levels)
    return HostModel(
        model.bias, quantized * scale, model.numeric_mean, model.numeric_std, model.numeric_weights,
        model.threshold_medium, model.threshold_high,
    ), scale


# ---------------------------------------------------------------- evaluation helpers

def rate(mask: np.ndarray) -> float:
    return float(mask.mean()) if len(mask) else float("nan")


def evaluate(model: HostModel, hashed, numeric, labels, sources, popular: np.ndarray | None = None) -> dict:
    """Threshold metrics; hosts in `popular` are never flagged. ROC AUC and AP are for the model alone."""
    scores = model.scores(hashed, numeric)
    legit = labels == 0
    phishing = labels == 1
    not_popular = np.ones(len(labels), dtype=bool) if popular is None else ~popular
    result = {
        "roc_auc": float(roc_auc_score(labels, scores)),
        "average_precision": float(average_precision_score(labels, scores)),
        "phishing": int(phishing.sum()),
        "legitimate": int(legit.sum()),
        "phishing_on_popular_domains": int((phishing & ~not_popular).sum()),
        "legitimate_on_popular_domains": int((legit & ~not_popular).sum()),
    }
    for name, threshold in (("high", model.threshold_high), ("medium", model.threshold_medium)):
        flagged = (scores >= threshold) & not_popular
        result[f"tpr_{name}"] = rate(flagged[phishing])
        result[f"fpr_{name}"] = rate(flagged[legit])
        result[f"fpr_{name}_phiusiil_legit"] = rate(flagged[legit & (sources == "phiusiil")])
        result[f"fpr_{name}_umbrella_legit"] = rate(flagged[legit & (sources == "umbrella")])
    return result


def format_only_baseline() -> dict:
    """A model that sees only how a URL is written, not what it points to."""
    raw = pd.read_csv(DATA / "PhiUSIIL_Phishing_URL_Dataset.csv", usecols=["URL", "label"]).drop_duplicates("URL")
    labels = (raw.label == 0).astype(int).values
    parts = raw.URL.map(lambda u: urlsplit(u if "://" in u else "http://" + u))
    features = np.column_stack([
        parts.map(lambda p: p.scheme == "https"),
        parts.map(lambda p: (p.hostname or "").startswith("www.")),
        raw.URL.str.endswith("/"),
        parts.map(lambda p: len(p.path) > 1),
        parts.map(lambda p: p.query != ""),
    ]).astype(float)
    x_train, x_test, y_train, y_test = train_test_split(features, labels, test_size=0.2, random_state=SEED, stratify=labels)
    model = LogisticRegression().fit(x_train, y_train)
    return {
        "accuracy": float(accuracy_score(y_test, model.predict(x_test))),
        "roc_auc": float(roc_auc_score(y_test, model.predict_proba(x_test)[:, 1])),
    }


def openphish_evaluation(model: HostModel, training_phishing_hosts: set[str], popular: PopularList) -> dict:
    hosts: dict[str, uf.ParsedHost] = {}
    files = sorted(glob.glob(str(DATA / "openphish-*.txt")))
    for file in files:
        for line in Path(file).read_text(encoding="utf-8").splitlines():
            parsed = uf.parse_host(line)
            if parsed is not None:
                hosts.setdefault(parsed.host, parsed)
    parsed_hosts = list(hosts.values())
    hashed, numeric = vectorize(parsed_hosts)
    scores = model.scores(hashed, numeric)
    unseen = np.array([p.host not in training_phishing_hosts for p in parsed_hosts])
    not_popular = ~popular.mask(parsed_hosts)
    high = scores >= model.threshold_high
    medium = scores >= model.threshold_medium
    return {
        "files": [Path(f).name for f in files],
        "unique_hosts": len(parsed_hosts),
        "unseen_hosts": int(unseen.sum()),
        "on_popular_domains": int((~not_popular).sum()),
        "tpr_high_all": rate(high),
        "tpr_medium_all": rate(medium),
        "tpr_high_unseen": rate(high[unseen]),
        "tpr_medium_unseen": rate(medium[unseen]),
        "tpr_high_unseen_with_popular": rate((high & not_popular)[unseen]),
        "tpr_medium_unseen_with_popular": rate((medium & not_popular)[unseen]),
    }


# ---------------------------------------------------------------- export

def export_model(model: HostModel, bits: int, scale: float) -> None:
    levels = (1 << (bits - 1)) - 1
    quantized = np.clip(np.round(model.hashed_weights / scale), -levels, levels)
    MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
    with MODEL_PATH.open("wb") as out:
        out.write(b"CGHM")
        out.write(struct.pack("<iiiii", 1, uf.FEATURE_SPEC_VERSION, uf.BUCKETS, len(uf.NUMERIC_FEATURES), bits))
        out.write(struct.pack("<dddd", model.bias, scale, model.threshold_medium, model.threshold_high))
        for mean, std, weight in zip(model.numeric_mean, model.numeric_std, model.numeric_weights):
            out.write(struct.pack("<ddd", mean, std, weight))
        dtype = "<i1" if bits == 8 else "<i2"
        out.write(quantized.astype(dtype).tobytes())


def reference_probability(model: HostModel, url: str) -> float | None:
    """Scores one URL exactly the way the Kotlin implementation does."""
    parsed = uf.parse_host(url)
    if parsed is None:
        return None
    z = model.bias
    for bucket in sorted({uf.bucket(key) for key in uf.token_keys(parsed)}):
        z += model.hashed_weights[bucket]
    for value, mean, std, weight in zip(uf.numeric_features(parsed), model.numeric_mean, model.numeric_std, model.numeric_weights):
        z += weight * (value - mean) / std
    return 1.0 / (1.0 + float(np.exp(-z)))


EDGE_CASES = [
    "https://www.example.com/login",
    "http://paypal.com@evil-login.example/verify",
    "http://192.168.10.5:8080/bank/login.php",
    "https://xn--pple-43d.com",
    "HTTPS://Secure-Login.Bank.Example./account",
    "secure-update-account-verify.example-billing.top",
    "https://online.citi.com/US/login.do",
    "https://mail.google.com/mail/u/0/",
    "https://sites.google.com/view/account-recovery",
    "https://google.com.account-verify.example/",
    "https://appleid.apple.com.verify-account-session.xyz/",
    "https://metamask-wallet-restore.web.app/",
    "https:///no-host",
]


def write_golden(model: HostModel, popular: PopularList, urls: list[str]) -> int:
    GOLDEN_PATH.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# url\tprobability\tpopular  (reference scorer with quantized weights and the exported popular-domain list)",
        "# URLs from PhiUSIIL (Prasad and Chandra, 2024; CC BY 4.0) plus hand-written cases.",
    ]
    for url in urls:
        if "\t" in url or "\n" in url:
            continue
        parsed = uf.parse_host(url)
        if parsed is None:
            lines.append(f"{url}\tnone\tfalse")
            continue
        is_popular = popular.contains(parsed)
        lines.append(f"{url}\t{reference_probability(model, url)!r}\t{str(is_popular).lower()}")
    GOLDEN_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    return len(lines) - 2


# ---------------------------------------------------------------- main

def main() -> None:
    np.random.seed(SEED)
    psl = load_psl(DATA / "public_suffix_list.dat")
    examples = build_dataset(psl)
    labels = np.array([e.label for e in examples])
    sources = np.array([e.source for e in examples])
    groups = np.array([e.group for e in examples])
    log(f"dataset: {len(examples)} hosts, {labels.sum()} phishing, {(labels == 0).sum()} legitimate")

    splitter = GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=SEED)
    train_index, holdout_index = next(splitter.split(np.zeros(len(examples)), labels, groups))
    second = GroupShuffleSplit(n_splits=1, test_size=0.5, random_state=SEED)
    val_part, test_part = next(second.split(holdout_index, labels[holdout_index], groups[holdout_index]))
    val_index, test_index = holdout_index[val_part], holdout_index[test_part]
    assert not set(groups[train_index]) & set(groups[test_index]), "registered domains leak between train and test"
    log(f"split by registered domain: train {len(train_index)}, validation {len(val_index)}, test {len(test_index)}")

    log("vectorizing")
    hashed, numeric = vectorize([e.parsed for e in examples])

    def part(index):
        return hashed[index], numeric[index], labels[index], sources[index]

    # Model selection on validation ROC AUC.
    best = None
    selection = {}
    for c in C_GRID:
        log(f"training C={c}")
        model = fit(hashed[train_index], numeric[train_index], labels[train_index], c)
        auc = roc_auc_score(labels[val_index], model.scores(hashed[val_index], numeric[val_index]))
        selection[str(c)] = float(auc)
        log(f"  validation ROC AUC {auc:.5f}")
        if best is None or auc > best[0]:
            best = (auc, c, model)
    _, best_c, model = best

    val_scores = model.scores(hashed[val_index], numeric[val_index])
    val_legit = val_scores[labels[val_index] == 0]
    model.threshold_high = threshold_at_fpr(val_legit, FPR_HIGH)
    model.threshold_medium = threshold_at_fpr(val_legit, FPR_MEDIUM)
    log(f"chosen C={best_c}, thresholds medium={model.threshold_medium:.4f} high={model.threshold_high:.4f}")

    test_full = evaluate(model, *part(test_index))
    log(f"test: {json.dumps(test_full)}")

    # Ablation: same features, trained and calibrated without the Umbrella subdomain hosts.
    log("ablation: training without legitimate subdomain hosts")
    phiusiil_train = train_index[sources[train_index] == "phiusiil"]
    phiusiil_val = val_index[sources[val_index] == "phiusiil"]
    no_aug = fit(hashed[phiusiil_train], numeric[phiusiil_train], labels[phiusiil_train], best_c)
    no_aug_val = no_aug.scores(hashed[phiusiil_val], numeric[phiusiil_val])[labels[phiusiil_val] == 0]
    no_aug.threshold_high = threshold_at_fpr(no_aug_val, FPR_HIGH)
    no_aug.threshold_medium = threshold_at_fpr(no_aug_val, FPR_MEDIUM)
    test_no_aug = evaluate(no_aug, *part(test_index))

    log("format-only baseline")
    baseline = format_only_baseline()

    # Quantization for the device.
    quantized_results = {}
    for bits in (8, 16):
        quantized, scale = quantize(model, bits)
        quantized_results[bits] = (quantized, scale, evaluate(quantized, *part(test_index)))
    loss_8 = test_full["roc_auc"] - quantized_results[8][2]["roc_auc"]
    bits = 8 if loss_8 < 1e-3 and abs(test_full["tpr_high"] - quantized_results[8][2]["tpr_high"]) < 5e-3 else 16
    device_model, scale, test_device = quantized_results[bits]
    log(f"exporting {bits}-bit weights (AUC loss with 8 bits: {loss_8:.6f})")

    training_phishing = {examples[i].parsed.host for i in train_index if labels[i] == 1}
    popular = build_popular_list(psl, training_phishing)
    log(f"popular list: {len(popular.domains)} domains, {len(popular.excluded_platforms)} platforms excluded, "
        f"{len(popular.abused_hosts)} abused hosts")
    test_popular_mask = popular.mask([examples[i].parsed for i in test_index])
    test_device_popular = evaluate(device_model, *part(test_index), popular=test_popular_mask)
    log(f"test with popular list: {json.dumps(test_device_popular)}")

    fresh = openphish_evaluation(device_model, training_phishing, popular)
    log(f"OpenPhish: {json.dumps(fresh)}")

    export_model(device_model, bits, scale)
    export_popular_list(popular)

    # The reference scorer must agree with the matrix computation before it defines the golden file.
    rng = np.random.default_rng(SEED)
    phiusiil_test = [i for i in test_index if sources[i] == "phiusiil"]
    sample = [examples[i] for i in rng.choice(phiusiil_test, size=200, replace=False)]
    matrix_scores = device_model.scores(*vectorize([e.parsed for e in sample]))
    reference_scores = np.array([reference_probability(device_model, e.url) for e in sample])
    max_difference = float(np.abs(matrix_scores - reference_scores).max())
    assert max_difference < 1e-9, f"reference scorer differs by {max_difference}"
    golden_urls = [e.url for e in sample] + EDGE_CASES
    golden_urls += [h for h in sorted(popular.domains)[:: max(1, len(popular.domains) // 30)]]  # popular hosts
    golden_urls += sorted(popular.abused_hosts)[:10]
    golden_count = write_golden(device_model, popular, golden_urls)

    metrics = {
        "data": {
            "hosts": len(examples),
            "phishing": int(labels.sum()),
            "legitimate_phiusiil": int(((labels == 0) & (sources == "phiusiil")).sum()),
            "legitimate_umbrella": int(((labels == 0) & (sources == "umbrella")).sum()),
            "train": len(train_index),
            "validation": len(val_index),
            "test": len(test_index),
        },
        "selection": {"validation_roc_auc_by_C": selection, "chosen_C": best_c},
        "thresholds": {"medium": model.threshold_medium, "high": model.threshold_high},
        "format_only_baseline": baseline,
        "test_float": test_full,
        "test_without_subdomain_augmentation": test_no_aug,
        "test_device": test_device,
        "test_device_with_popular_list": test_device_popular,
        "device_weights_bits": bits,
        "model_bytes": MODEL_PATH.stat().st_size,
        "popular_list": {
            "domains": len(popular.domains),
            "abused_hosts": len(popular.abused_hosts),
            "boundaries": len(popular.boundaries),
            "excluded_platforms": len(popular.excluded_platforms),
            "largest_excluded_platforms": sorted(popular.excluded_platforms, key=lambda kv: -kv[1])[:10],
            "file_bytes": POPULAR_PATH.stat().st_size,
        },
        "openphish": fresh,
        "golden": {"rows": golden_count, "matrix_vs_reference_max_difference": max_difference},
    }
    REPORTS.mkdir(exist_ok=True)
    (REPORTS / "metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8", newline="\n")
    examples_table = explain_examples(device_model, popular)
    write_report(metrics, examples_table)
    log("done")


def explain_examples(model: HostModel, popular: PopularList) -> list[tuple[str, float, str, str]]:
    rows = []
    for url in EDGE_CASES[:-1]:
        parsed = uf.parse_host(url)
        probability = reference_probability(model, url)
        if popular.contains(parsed):
            level = "LOW (popular domain)"
        elif probability >= model.threshold_high:
            level = "HIGH"
        elif probability >= model.threshold_medium:
            level = "MEDIUM"
        else:
            level = "LOW"
        contributions = {}
        for key in uf.token_keys(parsed):
            contributions[key] = model.hashed_weights[uf.bucket(key)]
        top = sorted(contributions.items(), key=lambda kv: -kv[1])[:3]
        rows.append((url, probability, level, ", ".join(f"`{k}` {v:+.2f}" for k, v in top)))
    return rows


def write_report(m: dict, examples_table) -> None:
    def pct(value: float) -> str:
        return f"{100 * value:.2f}%"

    t, n, d, b, o = m["test_float"], m["test_without_subdomain_augmentation"], m["test_device"], m["format_only_baseline"], m["openphish"]
    dp, pl = m["test_device_with_popular_list"], m["popular_list"]
    lines = [
        "# Phishing link model: training report",
        "",
        "Generated by `train_phishing_model.py`. All numbers come from `metrics.json` of the same run.",
        "",
        "## Data",
        "",
        f"- {m['data']['phishing']:,} phishing hosts and {m['data']['legitimate_phiusiil']:,} legitimate hosts from PhiUSIIL "
        "(Prasad and Chandra, Computers & Security, 2024; CC BY 4.0), deduplicated by host.",
        f"- {m['data']['legitimate_umbrella']:,} legitimate hosts with subdomains from the Cisco Umbrella popularity list, "
        f"restricted to registered domains that PhiUSIIL lists as legitimate, at most {UMBRELLA_PER_DOMAIN} per registered "
        "domain, and excluding DNS lookup names with several numeric labels (such as blocklist queries).",
        f"- Split by registered domain (Public Suffix List): {m['data']['train']:,} train, {m['data']['validation']:,} validation, "
        f"{m['data']['test']:,} test. No registered domain appears in more than one split.",
        "",
        "## Why only the host",
        "",
        "In PhiUSIIL every legitimate URL has the form `https://www.<domain>` with no path, while phishing URLs vary. "
        f"A logistic regression that sees only *how the URL is written* (https, www, trailing slash, path, query) reaches "
        f"**{pct(b['accuracy'])} accuracy** (ROC AUC {b['roc_auc']:.4f}) without any information about the site. "
        "Models trained on full URLs from this dataset can therefore score highly by learning formatting. "
        "This model uses the host only, so these artifacts cannot influence it.",
        "",
        "The same issue affects subdomains: most legitimate PhiUSIIL hosts are bare registered domains. "
        "Without legitimate subdomain examples the model learns that any subdomain is suspicious, as the ablation below shows.",
        "",
        "## Results on the test split",
        "",
        f"Thresholds are set on the validation split: **high** at {pct(FPR_HIGH)} and **medium** at {pct(FPR_MEDIUM)} "
        "false positive rate on legitimate hosts.",
        "",
        "| | Without subdomain hosts | Full model (float) | On-device model | On-device + popular list |",
        "|---|---|---|---|---|",
        f"| ROC AUC (model score) | {n['roc_auc']:.4f} | {t['roc_auc']:.4f} | {d['roc_auc']:.4f} | n/a |",
        f"| Average precision (model score) | {n['average_precision']:.4f} | {t['average_precision']:.4f} | {d['average_precision']:.4f} | n/a |",
        f"| Phishing detected at high | {pct(n['tpr_high'])} | {pct(t['tpr_high'])} | {pct(d['tpr_high'])} | {pct(dp['tpr_high'])} |",
        f"| Phishing detected at medium | {pct(n['tpr_medium'])} | {pct(t['tpr_medium'])} | {pct(d['tpr_medium'])} | {pct(dp['tpr_medium'])} |",
        f"| False alarms at high, bare legitimate domains | {pct(n['fpr_high_phiusiil_legit'])} | {pct(t['fpr_high_phiusiil_legit'])} | {pct(d['fpr_high_phiusiil_legit'])} | {pct(dp['fpr_high_phiusiil_legit'])} |",
        f"| False alarms at high, legitimate subdomains | {pct(n['fpr_high_umbrella_legit'])} | {pct(t['fpr_high_umbrella_legit'])} | {pct(d['fpr_high_umbrella_legit'])} | {pct(dp['fpr_high_umbrella_legit'])} |",
        f"| False alarms at medium, legitimate subdomains | {pct(n['fpr_medium_umbrella_legit'])} | {pct(t['fpr_medium_umbrella_legit'])} | {pct(d['fpr_medium_umbrella_legit'])} | {pct(dp['fpr_medium_umbrella_legit'])} |",
        "",
        f"The on-device model stores {m['device_weights_bits']}-bit weights ({m['model_bytes']:,} bytes).",
        "",
        "## Popular domains",
        "",
        "The model judges how a host is spelled and knows nothing about reputation, so it can flag well-known "
        "hosts whose names look like phishing (for example `mail.` subdomains). The library therefore reports hosts "
        f"under the {pl['domains']:,} most popular registered domains of the Majestic Million (CC BY 3.0) as low risk. "
        f"{len(URL_SHORTENERS)} well-known link shorteners are never treated as popular, since a short link can lead anywhere. "
        "Public suffixes and accounts on platforms listed in the private section of the Public Suffix List "
        "(such as `appspot.com` or `foo.github.io`) are never treated as popular, and matching stops at the "
        f"{pl['boundaries']:,} public suffixes nested inside popular domains (such as `web.core.windows.net`). "
        f"{pl['excluded_platforms']} further registered domains with at least {PLATFORM_PHISHING_HOSTS} distinct phishing hosts "
        "in the training data are left out, since anyone can create subdomains on them "
        f"(largest: {', '.join(f'{d} ({c:,})' for d, c in pl['largest_excluded_platforms'][:5])}). "
        f"{pl['abused_hosts']:,} individual hosts under popular domains that appear in the training phishing data "
        "(for example shared document or site hosts) are also left to the model. "
        f"The list file is {pl['file_bytes']:,} bytes.",
        "",
        f"In the test split, {dp['legitimate_on_popular_domains']:,} legitimate and {dp['phishing_on_popular_domains']:,} "
        "phishing hosts fall under popular domains. The phishing ones are missed by design: a phishing page on a "
        "reputable host cannot be recognized from the host name.",
        "",
        "## Fresh phishing from a different source",
        "",
        f"OpenPhish community feed ({', '.join(o['files'])}): {o['unique_hosts']} unique hosts, "
        f"{o['unseen_hosts']} of them not in the training data.",
        "",
        f"- Detected at high: {pct(o['tpr_high_unseen'])} of unseen hosts by the model alone, "
        f"{pct(o['tpr_high_unseen_with_popular'])} with the popular list.",
        f"- Detected at medium: {pct(o['tpr_medium_unseen'])} of unseen hosts by the model alone, "
        f"{pct(o['tpr_medium_unseen_with_popular'])} with the popular list.",
        f"- {o['on_popular_domains']} hosts fall under popular domains.",
        "",
        "This feed contains phishing only, so it measures detection, not false alarms. It is small and from a single day.",
        "",
        "## Example explanations",
        "",
        "| URL | Probability | Level | Strongest phishing evidence |",
        "|---|---|---|---|",
    ]
    lines += [f"| `{url}` | {p:.3f} | {level} | {why} |" for url, p, level, why in examples_table]
    lines += [
        "",
        "## Limitations",
        "",
        "- The model judges hosts, not pages. A phishing page on a reputable host (a compromised site or a "
        "shared platform) is out of its reach, as is a legitimate site on an unusual-looking host.",
        "- Legitimate subdomain examples come from DNS popularity, which includes some infrastructure names that "
        "users never see in links.",
        "- Phishing changes quickly; the model should be retrained on fresh data regularly.",
    ]
    (REPORTS / "phishing-model-report.md").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()

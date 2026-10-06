"""Checks of the feature specification. Run: python test_url_features.py

The same cases are asserted in the Kotlin tests of coachguard-links.
"""

from url_features import bucket, entropy, is_popular, numeric_features, parse_host, token_keys


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def test_parse() -> None:
    cases = {
        "https://www.Example.COM/login?x=1": ("example.com", False, False, False),
        "http://paypal.com@evil.example/": ("evil.example", True, False, False),
        "evil.example:8080/path": ("evil.example", False, True, False),
        "http://192.168.1.10/admin": ("192.168.1.10", False, False, True),
        "http://[2001:db8::1]:443/": ("2001:db8::1", False, True, True),
        "HTTPS://Secure-Login.Bank.Example./": ("secure-login.bank.example", False, False, False),
        "www.example.com": ("example.com", False, False, False),
        "https://xn--pple-43d.com": ("xn--pple-43d.com", False, False, False),
        "https://example.com#frag": ("example.com", False, False, False),
        "https://example.com\\@evil.com": ("example.com", False, False, False),
    }
    for url, (host, userinfo, port, ip) in cases.items():
        parsed = parse_host(url)
        check(parsed is not None, f"{url}: no host")
        actual = (parsed.host, parsed.has_userinfo, parsed.has_port, parsed.is_ip)
        check(actual == (host, userinfo, port, ip), f"{url}: {actual}")
    check(parse_host("https:///path") is None, "empty host")
    check(parse_host("") is None, "empty url")


def test_features() -> None:
    parsed = parse_host("http://secure-login.bank1.example")
    values = numeric_features(parsed)
    check(values[:5] == [26.0, 3.0, 1.0, 1.0, 12.0], f"numeric {values}")
    check(abs(entropy("aabb") - 1.0) < 1e-12, "entropy")
    keys = token_keys(parse_host("ab.cd"))
    expected = {
        "c:^ab", "c:ab.", "c:b.c", "c:.cd", "c:cd$",
        "c:^ab.", "c:ab.c", "c:b.cd", "c:.cd$",
        "c:^ab.c", "c:ab.cd", "c:b.cd$",
        "t:cd", "s:ab.cd", "l:ab",
    }
    check(set(keys) == expected, f"keys {keys}")
    check(keys == sorted(keys), "keys sorted")
    # 0xCBF43926 is the standard CRC-32 check value of "123456789"; the Kotlin test asserts the same.
    check(bucket("123456789") == 0xCBF43926 % (1 << 18), "bucket uses standard CRC-32")


def test_popular() -> None:
    popular = {"google.com", "example.co.uk", "windows.net"}
    abused = {"sites.google.com"}
    boundaries = {"web.core.windows.net"}
    cases = {
        "https://google.com": True,
        "https://mail.google.com/x": True,
        "https://sites.google.com/view/x": False,
        "https://google.com.evil.example": False,
        "https://notgoogle.com": False,
        "https://shop.example.co.uk": True,
        "https://co.uk": False,
        "http://8.8.8.8": False,
        "https://login.windows.net": True,
        "https://evil.z1.web.core.windows.net": False,
        "https://web.core.windows.net": False,
    }
    for url, expected in cases.items():
        check(is_popular(parse_host(url), popular, abused, boundaries) == expected, f"popular {url}")


if __name__ == "__main__":
    test_parse()
    test_features()
    test_popular()
    print("url_features: all checks passed")

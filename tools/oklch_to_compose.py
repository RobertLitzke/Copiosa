"""Convert the theme spec's oklch country palettes to Compose Color hex literals.

The persona/country theme spec defines each country palette in oklch. Compose wants sRGB.
Naive per-channel clamping shifts hue on the saturated flag accents, so out-of-gamut colors
are gamut-mapped by binary-searching the largest chroma that fits sRGB at constant L and H.

Run:  python tools/oklch_to_compose.py
Prints ready-to-paste Kotlin `CountryPalette(...)` blocks for CountryThemes.kt.
"""

import math

# --- oklch -> sRGB ---------------------------------------------------------


def oklch_to_linear_srgb(L, C, H):
    a = C * math.cos(math.radians(H))
    b = C * math.sin(math.radians(H))
    l_ = L + 0.3963377774 * a + 0.2158037573 * b
    m_ = L - 0.1055613458 * a - 0.0638541728 * b
    s_ = L - 0.0894841775 * a - 1.2914855480 * b
    l, m, s = l_ ** 3, m_ ** 3, s_ ** 3
    return (
        +4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
    )


def in_gamut(rgb, eps=1e-6):
    return all(-eps <= c <= 1 + eps for c in rgb)


def gamma(c):
    c = min(1.0, max(0.0, c))
    return 12.92 * c if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055


def oklch_to_hex(L, C, H):
    rgb = oklch_to_linear_srgb(L, C, H)
    if not in_gamut(rgb):
        # Hue-preserving gamut map: shrink chroma until sRGB can express the color.
        lo, hi = 0.0, C
        for _ in range(48):
            mid = (lo + hi) / 2
            if in_gamut(oklch_to_linear_srgb(L, mid, H)):
                lo = mid
            else:
                hi = mid
        rgb = oklch_to_linear_srgb(L, lo, H)
    r, g, b = (round(gamma(c) * 255) for c in rgb)
    return f"0xFF{r:02X}{g:02X}{b:02X}"


# --- Spec tables (verbatim from the theme spec) -----------------------------

TOKENS = [
    "bg", "surface", "surface2", "line", "text", "textDim", "textMute",
    "accent", "accentInk", "accentSoft", "accentSoftInk", "accent2", "accent2Ink",
]

# country -> mode -> token -> (L, C, H)
PALETTES = {
    "Spain": {
        "light": {
            "bg": (0.975, 0.016, 82), "surface": (0.995, 0.006, 82), "surface2": (0.95, 0.02, 78),
            "line": (0.9, 0.022, 76), "text": (0.29, 0.03, 42), "textDim": (0.47, 0.03, 44),
            "textMute": (0.62, 0.02, 52), "accent": (0.53, 0.2, 26), "accentInk": (0.99, 0.01, 80),
            "accentSoft": (0.9, 0.11, 84), "accentSoftInk": (0.45, 0.14, 44),
            "accent2": (0.74, 0.15, 84), "accent2Ink": (0.3, 0.07, 60),
        },
        "dark": {
            "bg": (0.205, 0.024, 34), "surface": (0.248, 0.026, 34), "surface2": (0.292, 0.028, 33),
            "line": (0.35, 0.026, 33), "text": (0.94, 0.016, 72), "textDim": (0.76, 0.018, 60),
            "textMute": (0.61, 0.016, 55), "accent": (0.64, 0.2, 30), "accentInk": (0.15, 0.02, 40),
            "accentSoft": (0.4, 0.1, 60), "accentSoftInk": (0.9, 0.11, 84),
            "accent2": (0.8, 0.15, 86), "accent2Ink": (0.24, 0.06, 55),
        },
    },
    "Portugal": {
        "light": {
            "bg": (0.972, 0.013, 132), "surface": (0.995, 0.005, 132), "surface2": (0.95, 0.016, 128),
            "line": (0.9, 0.017, 126), "text": (0.27, 0.025, 150), "textDim": (0.46, 0.025, 150),
            "textMute": (0.62, 0.018, 140), "accent": (0.46, 0.13, 156), "accentInk": (0.99, 0.01, 150),
            "accentSoft": (0.9, 0.09, 68), "accentSoftInk": (0.44, 0.16, 40),
            "accent2": (0.53, 0.2, 27), "accent2Ink": (0.99, 0.01, 40),
        },
        "dark": {
            "bg": (0.2, 0.02, 152), "surface": (0.245, 0.022, 152), "surface2": (0.29, 0.024, 150),
            "line": (0.35, 0.022, 150), "text": (0.93, 0.012, 140), "textDim": (0.74, 0.014, 140),
            "textMute": (0.6, 0.014, 145), "accent": (0.6, 0.13, 156), "accentInk": (0.14, 0.02, 156),
            "accentSoft": (0.38, 0.09, 55), "accentSoftInk": (0.88, 0.11, 66),
            "accent2": (0.64, 0.2, 28), "accent2Ink": (0.14, 0.02, 30),
        },
    },
    "Mexico": {
        "light": {
            "bg": (0.975, 0.01, 132), "surface": (0.996, 0.004, 132), "surface2": (0.95, 0.013, 132),
            "line": (0.9, 0.014, 132), "text": (0.27, 0.016, 150), "textDim": (0.47, 0.015, 150),
            "textMute": (0.63, 0.013, 140), "accent": (0.5, 0.15, 150), "accentInk": (0.99, 0.012, 150),
            "accentSoft": (0.93, 0.06, 150), "accentSoftInk": (0.37, 0.09, 150),
            "accent2": (0.55, 0.2, 27), "accent2Ink": (0.99, 0.01, 40),
        },
        "dark": {
            "bg": (0.205, 0.01, 150), "surface": (0.247, 0.011, 150), "surface2": (0.29, 0.013, 150),
            "line": (0.345, 0.013, 150), "text": (0.93, 0.01, 130), "textDim": (0.74, 0.012, 130),
            "textMute": (0.6, 0.012, 135), "accent": (0.63, 0.15, 150), "accentInk": (0.16, 0.02, 150),
            "accentSoft": (0.34, 0.06, 152), "accentSoftInk": (0.88, 0.1, 150),
            "accent2": (0.64, 0.2, 28), "accent2Ink": (0.14, 0.02, 30),
        },
    },
    "Germany": {
        "light": {
            "bg": (0.975, 0.004, 250), "surface": (0.999, 0.002, 250), "surface2": (0.952, 0.005, 250),
            "line": (0.9, 0.006, 250), "text": (0.22, 0.008, 260), "textDim": (0.44, 0.009, 258),
            "textMute": (0.61, 0.008, 255), "accent": (0.53, 0.21, 28), "accentInk": (0.99, 0.008, 90),
            "accentSoft": (0.89, 0.1, 88), "accentSoftInk": (0.4, 0.1, 66),
            "accent2": (0.74, 0.15, 86), "accent2Ink": (0.25, 0.05, 60),
        },
        "dark": {
            "bg": (0.185, 0.006, 260), "surface": (0.232, 0.007, 260), "surface2": (0.278, 0.008, 258),
            "line": (0.34, 0.008, 258), "text": (0.95, 0.005, 250), "textDim": (0.76, 0.007, 250),
            "textMute": (0.6, 0.007, 252), "accent": (0.63, 0.21, 28), "accentInk": (0.14, 0.02, 40),
            "accentSoft": (0.37, 0.07, 82), "accentSoftInk": (0.88, 0.1, 88),
            "accent2": (0.8, 0.15, 88), "accent2Ink": (0.22, 0.05, 60),
        },
    },
    "Brazil": {
        "light": {
            "bg": (0.975, 0.012, 135), "surface": (0.996, 0.005, 135), "surface2": (0.95, 0.015, 135),
            "line": (0.9, 0.016, 134), "text": (0.25, 0.03, 255), "textDim": (0.44, 0.03, 255),
            "textMute": (0.6, 0.02, 220), "accent": (0.54, 0.17, 152), "accentInk": (0.99, 0.012, 150),
            "accentSoft": (0.89, 0.14, 98), "accentSoftInk": (0.4, 0.16, 258),
            "accent2": (0.44, 0.18, 260), "accent2Ink": (0.99, 0.01, 250),
        },
        "dark": {
            "bg": (0.2, 0.024, 258), "surface": (0.245, 0.026, 258), "surface2": (0.29, 0.028, 258),
            "line": (0.35, 0.024, 258), "text": (0.94, 0.012, 250), "textDim": (0.75, 0.016, 250),
            "textMute": (0.61, 0.016, 240), "accent": (0.66, 0.18, 152), "accentInk": (0.14, 0.02, 152),
            "accentSoft": (0.42, 0.13, 98), "accentSoftInk": (0.9, 0.13, 100),
            "accent2": (0.62, 0.19, 262), "accent2Ink": (0.99, 0.01, 250),
        },
    },
}

# Elevation shadow color per country x mode (from the spec's CSS box-shadows; strongest layer).
SHADOWS = {
    "Spain": {"light": "0x14783214", "dark": "0x66000000"},
    "Portugal": {"light": "0x1214461E", "dark": "0x61000000"},
    "Mexico": {"light": "0x1214462D", "dark": "0x5C000000"},
    "Germany": {"light": "0x1A0F0F19", "dark": "0x6B000000"},
    "Brazil": {"light": "0x14143C5A", "dark": "0x66000000"},
}

if __name__ == "__main__":
    for country, modes in PALETTES.items():
        for mode, tokens in modes.items():
            name = f"{country}{mode.capitalize()}"
            print(f"private val {name} = CountryPalette(")
            for token in TOKENS:
                print(f"    {token} = Color({oklch_to_hex(*tokens[token])}),")
            print(f"    shadow = Color({SHADOWS[country][mode]}),")
            print(")")
            print()

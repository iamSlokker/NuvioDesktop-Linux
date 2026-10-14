# TMDB attribution logo (vendored, not MIT)

`blue_square_2.svg` is TMDB's official **"Primary full (blue)"** logo, copied
byte-for-byte from TMDB. It is a trademark/logo of TMDB (The Movie Database),
**not** NuvioDesktop-Linux artwork and **not** licensed under this repository's
MIT license. It is used only to attribute TMDB as the source of data and images,
as TMDB's terms require, and implies no endorsement or certification by TMDB.

| Field | Value |
| --- | --- |
| Page | <https://www.themoviedb.org/about/logos-attribution> |
| SVG | <https://www.themoviedb.org/assets/v4/logos/v2/blue_square_2-d537fb228cf3ded904ef09b136fe3fec72548ebc1fea3fbbd1ad9e36364db38b.svg> |
| SHA-256 | `d537fb228cf3ded904ef09b136fe3fec72548ebc1fea3fbbd1ad9e36364db38b` (2577 bytes; equals the hash in TMDB's asset URL) |
| Retrieved | 2026-10-14 |
| Page text | "As per our terms of use, every application that uses our data or images is required to properly attribute TMDB as the source. Below you will find some logos you can use within your application." |

The file must stay unmodified; `GenerateReleaseArtwork.java` and `generate.py`
refuse any other bytes. The recipe renders it to the Linux overlay
`rating_tmdb.png` (67×48 RGBA) by uniform scaling and centering only: no
recolor, stretch, crop or other change. The variant was chosen because its
185.04×133.4 viewBox (1.387:1) matches the 67×48 slot (1.396:1).

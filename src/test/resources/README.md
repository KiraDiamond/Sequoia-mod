# Media regression fixtures

klipy-report.mp4 is the 808,711-byte H.264 animation from
https://static.klipy.com/ii/d7aec6f6f171607374b2065c836f92f4/83/10/ghpFIE4WlSQS.mp4. Earlier URL
handling returned Klipy's "content no longer available" card instead; the tests pin its 44 frames,
1,760 ms and first-frame hash. It is third-party media used only by tests and is not in the mod JAR.

The `webp-*.webp` fixtures are generated with Pillow 12.0.0: a 2x2 lossless RGBA image
(red, half-transparent green, blue, transparent black), an 8x8 lossy red image, an 8x8
lossless animation (red, right half blue, bottom half green; 100/200/300 ms), and a
4097x1 red image that must be rejected by the existing dimension limit.
`webp-detail.webp` is a 768x2 lossless RGBA image of alternating opaque black and
half-transparent white columns, generated with Pillow 12.0.0 using `lossless=True, exact=True`.
It verifies fine detail and alpha survive above the animation resolution.

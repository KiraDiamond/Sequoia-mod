# Klipy regression fixture

klipy-report.mp4 is the 808,711-byte H.264 animation downloaded from:
https://static.klipy.com/ii/d7aec6f6f171607374b2065c836f92f4/83/10/ghpFIE4WlSQS.mp4

It is kept as a regression fixture because earlier URL handling returned Klipy's
purple "content no longer available" card instead of the intended animation.
The test checks 44 frames, 1,760 ms, dimensions, and a visually checked first-frame hash.

This is third-party media. No ownership or MIT licensing of the clip is claimed.
It is used only by tests and is not included in the runtime mod JAR.

# Near-limit cache fixture

noise-183.mp4 (2,726,074 bytes) is generated, not downloaded:

    ffmpeg -f lavfi -i color=c=gray:s=256x256:r=25 \
      -vf noise=alls=100:allf=t+u:all_seed=7,format=yuv420p -frames:v 183 \
      -c:v libx264 -preset veryfast -crf 30 -threads 1 -an noise-183.mp4

It decodes to 183 frames of 256 by 256 pixels (47,972,352 bytes of pixels), which is near
the decoder's pixel limit and nearly incompressible. The previous cache kept both its pixels
and a compressed copy (81,606,949 bytes) against a 64 MiB budget and evicted the preview
while it was on screen. A different FFmpeg build may encode different bytes; this exact file
is the regression input.

# Klipy regression fixture

klipy-report.mp4 is the 808,711-byte H.264 animation downloaded from:
https://static.klipy.com/ii/d7aec6f6f171607374b2065c836f92f4/83/10/ghpFIE4WlSQS.mp4

It is kept as a regression fixture because earlier URL handling returned Klipy's
purple "content no longer available" card instead of the intended animation.
The test checks 44 frames, 1,760 ms, dimensions, and a visually checked first-frame hash.

This is third-party media. No ownership or MIT licensing of the clip is claimed.
It is used only by tests and is not included in the runtime mod JAR.

# Klipy regression fixture

klipy-report.mp4 is the 808,711-byte H.264 animation from
https://static.klipy.com/ii/d7aec6f6f171607374b2065c836f92f4/83/10/ghpFIE4WlSQS.mp4. Earlier URL
handling returned Klipy's "content no longer available" card instead; the tests pin its 44 frames,
1,760 ms and first-frame hash. It is third-party media used only by tests and is not in the mod JAR.

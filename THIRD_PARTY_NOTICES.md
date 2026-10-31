# Third-party notices

## OpenCV

The Android implementation depends on OpenCV 4.12.0 through the official Maven artifact `org.opencv:opencv`.

Copyright OpenCV contributors. Licensed under the Apache License 2.0. See <https://opencv.org/license/>.

## Algorithm references

The Android detector and temporal capture pipeline were designed with reference to these open-source projects. No UI or unrelated application module is included.

- **DocShot**, Copyright 2026 Artem Shamsuarov, MIT License: <https://github.com/Artemarius/DocShot>
- **Open Android Doc Scanner**, Apache License 2.0: <https://github.com/phillip9933/open-android-doc-scanner>

The implementation adapts general techniques including multi-strategy contour proposals, quadrilateral ranking, temporal smoothing, focus coordination, and final-photo quality validation. The plugin's adapted source is distributed under its MIT License; OpenCV remains under Apache-2.0.

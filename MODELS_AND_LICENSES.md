# Models and licenses

## Bundled on-device models

- **ML Kit Image Labeling** — `com.google.mlkit:image-labeling:17.0.9`. Used in AI mode to infer broad labels and choose conservative scene-tuning profiles. Bundled model path; available without downloading at runtime.
- **ML Kit Selfie Segmentation** — `com.google.mlkit:segmentation-selfie:16.0.0-beta6`. Used in Portrait mode to estimate human foreground confidence and composite a blurred background. Bundled model path; available without downloading at runtime.

Google ML Kit SDK terms and applicable Google developer terms apply. The selfie-segmentation API is beta and may change incompatibly. See the official Android guides:
- https://developers.google.com/ml-kit/vision/image-labeling/android
- https://developers.google.com/ml-kit/vision/selfie-segmentation/android
- https://developers.google.com/ml-kit/tips/installation-paths

## Other components

- CameraX and AndroidX dependencies are distributed under their respective open-source licenses.
- The color-processing and mask-compositing Kotlin code in this repository is project source code.
- No cloud inference, Firebase-hosted model, runtime model download, or user photo upload is configured.

## Limitations

The image-labeling base model is a general-purpose classifier, not a dedicated photographic scene model. Selfie segmentation detects people, not arbitrary foreground objects or depth. No dedicated denoising, super-resolution, deblurring, or generative restoration model is bundled.

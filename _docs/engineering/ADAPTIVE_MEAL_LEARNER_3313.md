# AAPS 3.3.1.3 adaptive meal learner research preview

Base: official AndroidAPS 3.3.1.3 (release commit 688ad1c3ed8dd62a48b71418664bf5005eb181fd).

The user's installed string is reported as 3.3.1.3-2. The official upstream repository contains 3.3.1.3, not a -2 tag. If -2 contains third-party patches, its APK/source commit is needed for byte-level compatibility.

## Why a small online neural model

A personal AAPS dataset is small, non-stationary and strongly confounded. A large deep network would overfit and be hard to validate. The preview therefore uses a tiny deterministic MLP:
- 10 normalized inputs
- 8 tanh hidden units
- one bounded output
- online SGD only after completed meal episodes

Inputs:
- time of day (sin/cos)
- carbs
- protein
- fat
- pre-meal glucose
- pre-meal trend
- IOB
- profile I:C
- AutoISF ratio
- recent TDD ratio

The output is a bounded shadow I:C multiplier. It is NOT passed to pump dosing.

## Learning gate

An episode is trainable only when marked clean. Future integration should mark an episode unclean when any of these occurs:
- rescue carbs
- exercise during the learning window
- sensor gaps/noisy CGM
- infusion-site/pump delivery anomaly
- overlapping meal
- manual correction that cannot be separated from meal response
- prolonged pre-meal excursion

If hypoglycaemia occurs, that episode can never teach the model to become more aggressive.

## Confidence

The model is not advisory-ready before at least 20 clean observations. Confidence combines sample count and exponential moving prediction error.

## Bounds

The neural output is mapped to a log I:C multiplier and limited to approximately +/-15% around profile I:C in preview mode. This prevents one badly-labelled meal from producing a large change.

## Relationship with AutoISF

AutoISF remains an input/context signal, not a duplicate controller. The learner is deliberately prevented from directly mirroring instantaneous AutoISF into I:C. That avoids double counting the same postprandial excursion through both ISF and I:C.

## Next integration steps

1. Port protein/fat fixed coefficient UI and delayed kinetic model to 3.3.1.3.
2. Create meal episode recorder.
3. Build 0-2 h and 2-6 h outcome labels from CGM and insulin history.
4. Persist neural state per profile/time context.
5. Add shadow display to Wizard and Overview.
6. Historical replay before any advisory use.
7. Keep supportsDynamicIc() false until a separate safety/validation review.

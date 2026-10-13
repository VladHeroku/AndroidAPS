# Dynamic I:C / AutoISF shadow architecture

Branch: `feature/dynamic-icr-autoisf-shadow`

## Status

This branch is intentionally **non-actuating**. It does not change bolus, basal, SMB, COB or pump commands.
It adds a pure shadow estimator and tests so candidate Dynamic I:C logic can be replayed and validated before any dosing integration.

## Why Dynamic I:C must not simply copy AutoISF

AutoISF reacts to glucose level, acceleration/deceleration, postprandial behaviour and prolonged high glucose.
If every instantaneous AutoISF change is mirrored directly into I:C, the same post-meal rise can influence both:

1. correction/SMB behaviour through AutoISF; and
2. meal insulin through a stronger I:C.

That creates a double-counting path.

The current AAPS architecture already anticipates a dynamic I:C through `APS.supportsDynamicIc()` and
`APS.getIc(...)`, but no production APS plugin currently exposes a dynamic I:C.

AAPS issue #3359 also documents a related problem: changing ISF while holding I:C fixed changes
`CSF = ISF / ICR`, which can alter calculated carb absorption and COB decay even when the physiology of
meal absorption did not change.

## Proposed split

### 1. Dosing I:C

Dosing I:C should adapt to insulin requirement, but on a slower and more conservative time scale than
instantaneous AutoISF.

Shadow formula:

`ICR_dose = ICR_profile × sensitivity_ratio^coupling × learned_meal_factor`

where:

- `sensitivity_ratio = dynamic_ISF / profile_ISF`;
- the ratio is clipped before use;
- default coupling is partial (0.35), not full;
- the learned factor is blended by confidence in log space;
- the final multiplier is bounded;
- a low/falling/exercise context is not allowed to make I:C more aggressive.

The sensitivity input should eventually be a robust **pre-meal** estimate (for example a median over a clean
pre-meal window), not a postprandial AutoISF value.

### 2. Absorption / COB I:C

This is a separate variable.

For a rolling ISF used by the absorption model, the shadow estimator scales I:C proportionally:

`ICR_absorption = ICR_profile × ISF_absorption / ISF_profile`

which preserves the profile carb sensitivity factor:

`CSF_profile = ISF_profile / ICR_profile = ISF_absorption / ICR_absorption`

This avoids a change in dynamic ISF alone producing a spurious change in carb-decay speed.

### 3. Fat and protein

Fat/protein are recorded as **delayed-meal context**, not converted immediately into insulin units.

Evidence is strong that high-fat/high-protein meals can increase and delay postprandial insulin need, but
the magnitude is highly individual. Published studies report substantial between-person variability, and
some fat/protein algorithms increase hypoglycaemia when applied to ordinary meals.

The next safe step is therefore a personalized delayed-meal learner trained on replayed CGM/insulin data,
not a fixed universal FPU conversion.

## Evidence used for this design

- ADA Standards of Care 2026: prandial dosing should account for carbohydrate and, when appropriate,
  fat/protein; dose adjustment must be individualized.
- Bell et al., Diabetes Care 2016, PMID 27388474: HFHP meals required substantially more insulin on
  average, with large inter-individual variability.
- Al Balwi et al., Pediatr Diabetes 2022, PMID 36263447: systematic review/meta-analysis found benefit
  from additional/split insulin for HFHP meals, but dosing strategies were heterogeneous.
- Noaro et al., IEEE JBHI 2023, PMID 37027579: adaptive/personalized bolus calculation can be formulated
  as a learning problem.
- Reddy/Pesl et al. ABC4D randomized crossover work, PMID 37017468: adaptive bolus decision support can
  be evaluated prospectively against non-adaptive calculation.
- Haidar et al. 2024, PMID 39097566: reinforcement-learning decision support personalized high-fat meal
  and exercise dose adjustments.
- 2026 ADA/EASD adult T1D consensus: meal composition (fat, protein, fibre, alcohol) changes postprandial
  response; effects vary markedly between individuals and AID systems.
- AndroidAPS issue #3359: dynamic ISF with static I:C can unintentionally change CSF and therefore COB
  decay; developers/users explicitly discuss separating absorption behaviour from variable sensitivity.

## Validation plan before any actuation

1. Run historical replay only.
2. Compare profile I:C vs shadow I:C per meal.
3. Exclude/confidence-downweight meals with exercise, rescue carbs, sensor gaps, infusion-site failures,
   severe pre-meal excursions or overlapping meals.
4. Evaluate 0-2 h and 2-6 h glucose separately.
5. Primary safety metrics: predicted/observed <70 mg/dL exposure and additional insulin before nadir.
6. Efficacy metrics: postprandial peak, iAUC, time >180 mg/dL, time-to-return-to-target.
7. Require repeated evidence before changing a learned meal factor.
8. Only after replay success: advisory display. Closed-loop actuation remains a separate future review.

## Implementation in this commit

- `DynamicIcrShadowEstimator`: pure Kotlin, no pump commands.
- Bounded partial sensitivity coupling.
- Confidence-weighted meal factor.
- Conservative suppression of more-aggressive I:C in low/falling/exercise context.
- Separate absorption I:C that can preserve profile CSF.
- Fat/protein delayed-meal metadata only.
- Unit tests for neutrality, resistance/sensitivity, safety suppression and CSF preservation.

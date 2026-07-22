# Experiments

The experiment artifacts currently live at the workspace root in `../Experiment/`, outside the `mtsa` Git repository.

## Directory Map

`../Experiment/` contains:

- `OldController/`: generated or saved old controllers.
- `NewController/`: generated or saved new controllers.
- `LTS/`: LTS benchmark material.
- `OTF-DUC/`: OTF-DUC output logs/controllers.
- `Traditional DUC/`: Traditional DUC output logs/controllers.
- `processed/`: processed CSV/XLSX outputs.
- `tools/`: scripts for parsing logs, validating controllers, and building workbooks.

Observed benchmark/example families include:

- `Surveillance`
- `ProductionCell`
- `Workflow`
- `GSM`
- `Industry`
- `MetaSocket`
- `RailCab`
- `PowerPlant`

## Processed Outputs

Important processed files include:

- `../Experiment/processed/Experiment_evaluation_results.xlsx`
- `../Experiment/processed/All/extracted_rows_full.csv`
- `../Experiment/processed/All/runs_compact.csv`
- `../Experiment/processed/All/metric_dictionary.csv`
- `../Experiment/processed/ByExample/*_evaluation_results.xlsx`
- `../Experiment/processed/Workflow/Workflow_evaluation_results.xlsx`
- `../Experiment/processed/otf_duc_gr1_validation.csv`
- `../Experiment/processed/otf_duc_gr1_validation.md`

The validation Markdown file was present when this document was created, but the inspected header did not show filled result rows.  Verify the CSV or regenerate validation before citing validation results.

## Tools

`ltsa.updatingControllers.cli.BatchExperimentRunner`

- Runs YAML-defined experiment batches from the jar / classpath.
- A YAML root key `runs: N` repeats the whole `cases:` list N times.
- One run means executing every case in the YAML once, in order.
- When `runs` is specified, output is wrapped by run label.  For example, `outputDir: Experiment/result` writes to `Experiment/run_01/result/...`, `Experiment/run_02/result/...`, and so on.
- If `runs` is omitted, the existing output layout is preserved for compatibility.
- `runCount` and `repetitions` are accepted aliases, but `runs` is the preferred key.
- The runner automatically creates a per-case/per-run evaluation CSV path and passes it to the child JVM as `-Dmtsa.evaluation.csvFile=...`. Do not put a fixed CSV path in YAML `javaOptions` for batch runs, because multiple cases would collide.
- The CSV path is recorded in `meta.json` as `evaluationCsv`. The CSV itself also includes Run metadata such as `batch_case_id`, `batch_target`, `batch_run_index`, and `batch_run_label`.
- Each case performs normal synthesis only once, writes raw transitions and evaluation CSV data, then minimizes the already synthesized controller and writes `*_minimized_transitions_*.txt` plus `*_minimized_counts_*.csv`. Minimization is post-synthesis work and is not included in `コントローラ合成時間`.
- `meta.json` also records `minimizedTransitions`, `minimizedCounts`, `rawOutputStates`, `rawOutputTransitions`, `minimizedOutputStates`, and `minimizedOutputTransitions`.
- GR(1) time is recorded in the evaluation CSV. Use `traditional_gr1_solving_time` for Traditional DUC, and `stepwise_delayed_gr1_solving_time` for both stepwise_delayed and stepwise_delayed+SBP. With SBP enabled, this is the time to run GR(1) on the post-SBP final safety environment; distinguish the variant via case id / variant / YAML metadata.
- GR(1) breakdown metrics are also recorded. Main `metric_key` values include `*_gr1_synthesize_total_time`, `*_gr1_winning_region_time`, `*_gr1_strategy_build_time`, `*_gr1_strategy_to_controller_mts_time`, and `*_gr1_compact_state_conversion_time`, where `*` is `traditional` or `stepwise_delayed`.
- Optional Slack notification can be configured in YAML with top-level `slackWebhookUrl`, `notifyOn`, and `notifyTimeoutSeconds`. The runner sends one notification after all runs/cases finish, and skips notifications during `--dry-run`. Keep real webhook URLs out of Git-managed files.

Example:

```yaml
outputDir: Experiment/result
runs: 5
timeoutHours: 16
notifyOn: always  # always, success, failure, or never
slackWebhookUrl: "https://hooks.slack.com/services/..."
notifyTimeoutSeconds: 30

javaOptions:
  - -Dmtsa.evaluation.enabled=true

cases:
  - id: productioncell_n_1_k_1_otf_no_tr
    example: "ProductionCell_N=1, K=1"
    method: OTF
    variant: no_tr
    lts: "Experiment/LTS/ProductionCell/ProductionCell_N=1, K=1/ProductionCell_N=1, K=1_otf_no_tr.lts"
    target: UpdCont_OTF
```

### Slack Notification Notes

For long-running experiment batches, `BatchExperimentRunner` can send a Slack Incoming Webhook notification when the YAML-defined batch finishes.

The implementation is in `maven-root/mtsa/src/main/java/ltsa/updatingControllers/cli/ExperimentConfig.java` and `maven-root/mtsa/src/main/java/ltsa/updatingControllers/cli/BatchExperimentRunner.java`. The parent batch runner sends the notification, not the child JVM. It sends one notification after all `runs` / `cases` complete, not once per case.

Notification YAML keys must be top-level keys before `cases:`:

- `slackWebhookUrl`: Slack Incoming Webhook URL. `slackIncomingWebhookUrl` and `slackWebhook` are accepted aliases.
- `notifyOn`: `always`, `success`, `failure`, or `never`. Default is `always`.
- `notifyTimeoutSeconds`: connect/read timeout for the Slack POST. Default is `30`.

`notifyOn: always` means the runner sends a notification whether the whole batch succeeds or fails. Use this when the main goal is to know that the experiment has finished. `success` sends only when every case succeeds, `failure` sends only for failures such as timeout, OOM, exception, or no composition, and `never` disables notifications.

`notifyTimeoutSeconds` controls how long the runner waits for the Slack webhook request. For example, `30` gives the Slack connection and response wait up to 30 seconds each. This prevents notification delivery from holding the runner for too long when Slack or the network is slow. Notification failure does not change the experiment success/failure result.

The Slack message includes `SUCCESS` / `FAILURE`, failed case count, total case count, run count, YAML config path, `outputDir`, start/end timestamps, and elapsed time. If the parent runner exits because of an exception, a shortened runner error is included.

Operational notes:

- Notifications are skipped during `--dry-run`.
- Slack notification failures do not change the experiment exit code. They are reported as warnings on `stderr`.
- The webhook URL is not recorded in `meta.json`. If a Slack notification warning contains the URL, it is replaced with `<slack-webhook-url>`.
- Do not commit real webhook URLs to Git-managed YAML files. Keep them in local experiment YAML files or other Git-ignored experiment artifacts.
- If a webhook URL leaks, delete the old Incoming Webhook URL in the Slack app and issue a new one.
- If mobile Slack notifications do not appear, set the notification channel to notify on all new messages and set mobile notification timing to immediate.

A short Mac smoke test can use a temporary YAML file pointing to a missing LTS. The expected notification status is `FAILURE`.

```yaml
outputDir: "/tmp/mtsa-slack-test/result"
runs: 1
timeoutMinutes: 1

notifyOn: always
slackWebhookUrl: "https://hooks.slack.com/services/..."
notifyTimeoutSeconds: 10

javaOptions:
  - -Djava.awt.headless=true
  - -Xmx512m

cases:
  - id: slack_notification_smoke_test
    example: SlackNotificationSmoke
    method: OTF
    variant: notification_test
    lts: "/tmp/mtsa-slack-test/does-not-exist.lts"
    target: UpdCont_OTF
```

As of 2026-07-07, direct Slack webhook testing from macOS and the YAML-driven `BatchExperimentRunner` smoke test were both confirmed. The YAML smoke test intentionally produced `Status: FAILURE`, `Case failures: 1 / 1`.

`../Experiment/tools/compact_mtsa_evaluation.py`

- Reads machine-readable `EVALUATION DATA CSV` blocks from MTSA output logs, or `*_evaluation_*.csv` files produced by the batch runner.
- Adds run metadata inferred from file paths.
- Detects batch-run folders such as `run_01` and includes them in `batch_run` and `run_id`, so repeated YAML runs remain separate rows for later averaging.
- When a standalone CSV contains Run metadata such as `batch_case_id` or `batch_run_label`, that metadata is preferred for compact rows.
- Produces compact CSV files and metric dictionaries.

`../Experiment/tools/build_experiment_workbooks.py`

- Builds Excel workbooks from processed evaluation CSV data.
- Use this when refreshing aggregate experiment results.

`../Experiment/tools/build_workflow_workbook.mjs`

- Builds a workflow-focused workbook.

`../Experiment/tools/validate_otf_uc_gr1.py`

- Validates OTF-DUC output controllers against the traditional DUC GR(1) progress goal.
- Checks that after every reachable `hotSwapIn`, executions cannot remain forever in a cycle while any of `stopOldSpec`, `reconfigure`, or `startNewSpec` is missing.
- Uses mask bits: `1=stopOldSpec`, `2=reconfigure`, `4=startNewSpec`.
- Treats `hotSwapOut` separately from the three traditional GR(1) guarantees.

## Fixed 2x2x2 Drone Policy-update Examples

Two focused Drone update examples keep the airspace fixed at `2x2x2` and vary
the control policy instead of enlarging the movement range:

- `maven-root/mtsa/MODEL/StepwiseDUCS/Experiment/Drone/Drone_2x2x2_geofence_shift.lts`
  relocates the forbidden voxel from `(2,2,2)` to `(1,2,2)`.  `hotSwapIn` is
  not readiness-guarded; after entering the update phase, a drone may be moved
  out of the new geofence before `reconfigure`.
- `maven-root/mtsa/MODEL/StepwiseDUCS/Experiment/Drone/Drone_2x2x2_downwash_update.lts`
  strengthens collision avoidance from exact-voxel exclusion to exclusion of
  a shared vertical `(x,y)` column.  Vertically stacked old states may take
  `hotSwapIn`; `startNewSpec` waits until the columns are separated.

Both examples deliberately omit the unchanged battery subsystem.  They contain
two movement stages and isolate the policy-update structure: the two geofence
requirements are local to individual stages, whereas the downwash requirements
are cross-stage.  Exact-voxel collision avoidance remains cross-stage.

Each example has exactly one transition requirement, and it expresses a plant
readiness condition rather than an ordering between update events.  The
geofence example requires both drones to be outside the new forbidden voxel
when `reconfigure` occurs.  The downwash example requires separated columns
when `startNewSpec` occurs.  No transition requirement orders `reconfigure`,
`startNewSpec`, and `stopOldSpec`; DUCS is responsible for choosing their order.

Targets in both files are:

- `UpdCont` / `UPDATE_CONTROLLER`: Traditional DUCS;
- `UpdCont_SBP` / `UPDATE_CONTROLLER_SBP`: Traditional DUCS with SBP;
- `UpdCont_stepwise_delayed` / `STEPWISE_DELAYED_UPDATE_CONTROLLER`;
- `UpdCont_stepwise_delayed_SBP` / `STEPWISE_DELAYED_UPDATE_CONTROLLER_SBP`.

Validation on 2026-07-12 confirmed successful synthesis for Traditional DUCS,
`stepwise_delayed`, and `stepwise_delayed+SBP`.  `UpdateRequirementChecker`
reported `OVERALL=PASS` for all three checked variants in both examples.  In
particular, R1 (`hotSwapIn_enabled_in_all_pre_update_states`) passed for all 859
pre-update states in the geofence example and all 1369 pre-update states in the
downwash example.  Stepwise delayed connection construction also reported zero
old states without a target in both cases.

### Larger Fixed-airspace Variants

The same two policy updates are also available with a fixed `3x2x2`, `2x3x2`,
or `3x3x2` airspace.  In every file, the old and new movement environments have
the same dimensions; these are scale variants of the policy-update examples,
not movement-range update scenarios.

Geofence relocation variants:

- `maven-root/mtsa/MODEL/StepwiseDUCS/Experiment/Drone/Drone_3x2x2_geofence_shift.lts`:
  `(3,2,2)` to `(1,2,2)`;
- `maven-root/mtsa/MODEL/StepwiseDUCS/Experiment/Drone/Drone_2x3x2_geofence_shift.lts`:
  `(2,3,2)` to `(1,3,2)`;
- `maven-root/mtsa/MODEL/StepwiseDUCS/Experiment/Drone/Drone_3x3x2_geofence_shift.lts`:
  `(3,3,2)` to `(1,3,2)`.

Downwash-separation variants:

- `maven-root/mtsa/MODEL/StepwiseDUCS/Experiment/Drone/Drone_3x2x2_downwash_update.lts`;
- `maven-root/mtsa/MODEL/StepwiseDUCS/Experiment/Drone/Drone_2x3x2_downwash_update.lts`;
- `maven-root/mtsa/MODEL/StepwiseDUCS/Experiment/Drone/Drone_3x3x2_downwash_update.lts`.

The geofence rule is generalized as `(MaxX,MaxY,2)` to `(1,MaxY,2)`.  The
downwash rule covers every `(x,y)` column in the selected airspace.  The
transition requirements remain readiness-only and do not order update events.
All six files can be regenerated with
`maven-root/mtsa/MODEL/StepwiseDUCS/Experiment/Drone/generate_fixed_airspace_policy_models.py`.

On 2026-07-12 all six larger files passed full-file MTSA parsing, FLTL-property
compilation, and standalone `OLD_ENV` / `NEW_ENV` composition.  Controller
synthesis and `UpdateRequirementChecker` were intentionally not run because
these files are inputs for the larger-scale experiment.

## Metric Sources in Java

Most evaluation metrics are recorded by:

- `maven-root/mtsa/src/main/java/ltsa/updatingControllers/UpdatingControllerEvaluationRecorder.java`
- `maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/UpdatePhaseEvaluator.java`
- `maven-root/mtsa/src/main/java/MTSTools/ac/ic/doc/mtstools/model/operations/DCS/nonblocking/DirectedControllerSynthesisDUC.java`
- `maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/UpdatingControllerSynthesizer.java`

Metric categories include:

- input size;
- Traditional DUC `E_u`, meta environment, safety environment, and GR1 times;
- stepwise_delayed / stepwise_delayed+SBP final GR(1) time and GR(1) breakdown;
- OTF-DUC box-list preparation, Marking LTS generation, StateMapper time, DCS search, and output construction;
- state and transition counts;
- update-event transition counts;
- update phase flow;
- distance from `hotSwapIn` to completion;
- enabled update events by phase;
- hotSwapOut guard blocks;
- New Controller connection successes/misses;
- cache and heartbeat diagnostics.

## Reproducibility Notes

- Before citing experiment results, confirm whether the processed workbook was generated from logs corresponding to the current Java code.
- If logs are copied from the MTSA UI output, prefer the `EVALUATION DATA CSV` block as the source of truth.
- Keep Traditional DUC and OTF-DUC runs clearly separated in filenames and metadata; the processing scripts infer method and benchmark information from paths.
- If an experiment modifies metric labels or sections in Java, refresh `metric_dictionary.csv` as well as aggregate workbooks.

## Suggested Validation Flow

1. Generate or collect MTSA output logs for both Traditional DUC and OTF-DUC runs.
2. Run the compact extraction script on the log directories.
3. Build aggregate and by-example workbooks.
4. Run GR(1)-progress validation on OTF-DUC output controllers.
5. Compare state-space and timing metrics against the paper claims.

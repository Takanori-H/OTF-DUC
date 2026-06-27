# Stepwise DUCS Error Cases

These examples intentionally violate Stepwise DUCS assumptions.
Compose `STEPWISE_UPDATE_CONTROLLER` in each file.

| File | Expected error |
|---|---|
| `Error_stepwise_with_on_the_fly.lts` | `stepwise cannot be combined with on_the_fly, fine_grained, or selective_fine_grained.` |
| `Error_stepwise_mapping_keyword.lts` | `stepwise mode requires oldEnvironment/newEnvironment/mapRelation lists, not mapping = ...` |
| `Error_stepwise_stage_count_mismatch.lts` | `Size mismatch in Updating Controller definition: oldEnvironment, newEnvironment, and mapRelation must have the same number of elements.` |
| `Error_stepwise_assumption_goal.lts` | `Stepwise DUCS initial mode does not support assume in oldGoal.` |
| `Error_stepwise_goal_action_not_found.lts` | `Stepwise DUCS classification error: GOAL_ACTION_NOT_FOUND ... action=ghostAction.` |

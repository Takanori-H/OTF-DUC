#!/usr/bin/env python3
"""Generate the larger fixed-airspace geofence and downwash DUCS models."""

from pathlib import Path


OUTPUT_DIRECTORY = Path(__file__).resolve().parent
AIRSPACE_SIZES = ((3, 2), (2, 3), (3, 3))
MAX_HEIGHT = 2


MOVEMENT_MODEL = """//******************************************************************************
//* Constants and action sets
//******************************************************************************

const N = 2
const MaxX = __MAX_X__
const MaxY = __MAX_Y__
const MaxH = 2

range DroneID = 1..N
set Drones = {drone[DroneID]}
range X = 1..MaxX
range Y = 1..MaxY
range H = 1..MaxH

set Direction = {n,s,e,w}
set DroneActions = {drone[DroneID].takeoff, drone[DroneID].land, drone[DroneID].up, drone[DroneID].down, drone[DroneID].move[Direction]}
set ControllableActions = {DroneActions}

// This focused benchmark omits the unchanged battery subsystem so that the
// measured difference comes from the policy update and local/cross pruning.

//******************************************************************************
//* Old and new environments.  Their transition structures are identical.
//******************************************************************************

MOVE_OLD(I=1) =
\t(drone[I].takeoff -> drone[I].arrive[1][1][1] -> ACTION_OLD[1][1][1]),
ACTION_OLD[x:X][y:Y][h:H] =
\t(when(x > 1)    drone[I].move['w] -> drone[I].arrive[x-1][y][h] -> ACTION_OLD[x-1][y][h]
\t|when(x < MaxX) drone[I].move['e] -> drone[I].arrive[x+1][y][h] -> ACTION_OLD[x+1][y][h]
\t|when(y > 1)    drone[I].move['s] -> drone[I].arrive[x][y-1][h] -> ACTION_OLD[x][y-1][h]
\t|when(y < MaxY) drone[I].move['n] -> drone[I].arrive[x][y+1][h] -> ACTION_OLD[x][y+1][h]
\t|when(h > 1)    drone[I].down      -> drone[I].arrive[x][y][h-1] -> ACTION_OLD[x][y][h-1]
\t|when(h < MaxH) drone[I].up        -> drone[I].arrive[x][y][h+1] -> ACTION_OLD[x][y][h+1]
\t|when(h == 1)   drone[I].land      -> MOVE_OLD).

MOVE_NEW(I=1) =
\t(drone[I].takeoff -> drone[I].arrive[1][1][1] -> ACTION_NEW[1][1][1]),
ACTION_NEW[x:X][y:Y][h:H] =
\t(when(x > 1)    drone[I].move['w] -> drone[I].arrive[x-1][y][h] -> ACTION_NEW[x-1][y][h]
\t|when(x < MaxX) drone[I].move['e] -> drone[I].arrive[x+1][y][h] -> ACTION_NEW[x+1][y][h]
\t|when(y > 1)    drone[I].move['s] -> drone[I].arrive[x][y-1][h] -> ACTION_NEW[x][y-1][h]
\t|when(y < MaxY) drone[I].move['n] -> drone[I].arrive[x][y+1][h] -> ACTION_NEW[x][y+1][h]
\t|when(h > 1)    drone[I].down      -> drone[I].arrive[x][y][h-1] -> ACTION_NEW[x][y][h-1]
\t|when(h < MaxH) drone[I].up        -> drone[I].arrive[x][y][h+1] -> ACTION_NEW[x][y][h+1]
\t|when(h == 1)   drone[I].land      -> MOVE_NEW).

||OLD_MOVE_ENV = (forall[i:DroneID] MOVE_OLD(i)).
||OLD_ENV = OLD_MOVE_ENV.

||NEW_MOVE_ENV = (forall[i:DroneID] MOVE_NEW(i)).
||NEW_ENV = NEW_MOVE_ENV.

//******************************************************************************
//* Identity mapping: the current flight/position state is preserved.
//******************************************************************************

relation R_MOVE(I) = {
\tMOVE_OLD@MOVE_OLD(I) = reconfigure -> MOVE_NEW@MOVE_NEW(I),
\tforall[x:X][y:Y][h:H]
\t\tACTION_OLD[x][y][h]@MOVE_OLD(I) = reconfigure -> ACTION_NEW[x][y][h]@MOVE_NEW(I)
}
"""


UPDATING_CONTROLLERS = """//******************************************************************************
//* Updating controllers
//******************************************************************************

updatingController UpdCont = {
\toldController = OldDrone,
\toldEnvironment = {MOVE_OLD(1), MOVE_OLD(2)},
\tnewEnvironment = {MOVE_NEW(1), MOVE_NEW(2)},
\tmapRelation = {R_MOVE(1), R_MOVE(2)},
\toldGoal = OLD_SPEC,
\tnewGoal = NEW_SPEC,
\ttransition = __TRANSITION__,
\tnonblocking
}

||UPDATE_CONTROLLER = UpdCont.

updatingController UpdCont_SBP = {
\toldController = OldDrone,
\toldEnvironment = {MOVE_OLD(1), MOVE_OLD(2)},
\tnewEnvironment = {MOVE_NEW(1), MOVE_NEW(2)},
\tmapRelation = {R_MOVE(1), R_MOVE(2)},
\toldGoal = OLD_SPEC,
\tnewGoal = NEW_SPEC,
\ttransition = __TRANSITION__,
\tnonblocking,
\tsafetyBackwardPruning
}

||UPDATE_CONTROLLER_SBP = UpdCont_SBP.

updatingController UpdCont_stepwise_delayed = {
\toldController = OldDrone,
\toldEnvironment = {MOVE_OLD(1), MOVE_OLD(2)},
\tnewEnvironment = {MOVE_NEW(1), MOVE_NEW(2)},
\tmapRelation = {R_MOVE(1), R_MOVE(2)},
\toldGoal = OLD_SPEC,
\tnewGoal = NEW_SPEC,
\ttransition = __TRANSITION__,
\tnonblocking,
\tstepwise_delayed
}

||STEPWISE_DELAYED_UPDATE_CONTROLLER = UpdCont_stepwise_delayed.

updatingController UpdCont_stepwise_delayed_SBP = {
\toldController = OldDrone,
\toldEnvironment = {MOVE_OLD(1), MOVE_OLD(2)},
\tnewEnvironment = {MOVE_NEW(1), MOVE_NEW(2)},
\tmapRelation = {R_MOVE(1), R_MOVE(2)},
\toldGoal = OLD_SPEC,
\tnewGoal = NEW_SPEC,
\ttransition = __TRANSITION__,
\tnonblocking,
\tstepwise_delayed,
\tsafetyBackwardPruning
}

||STEPWISE_DELAYED_UPDATE_CONTROLLER_SBP = UpdCont_stepwise_delayed_SBP.

// The CLI UpdateRequirementChecker can be used after synthesis to verify R1:
// hotSwapIn_enabled_in_all_pre_update_states.
"""


def movement_model(max_x: int, max_y: int) -> str:
    return (
        MOVEMENT_MODEL.replace("__MAX_X__", str(max_x))
        .replace("__MAX_Y__", str(max_y))
    )


def flight_terminators(drone: int, include_vertical: bool = True) -> str:
    actions = [
        f"drone[{drone}].move['n]",
        f"drone[{drone}].move['s]",
        f"drone[{drone}].move['e]",
        f"drone[{drone}].move['w]",
    ]
    if include_vertical:
        actions.extend((f"drone[{drone}].up", f"drone[{drone}].down"))
    actions.append(f"drone[{drone}].land")
    return "{" + ", ".join(actions) + "}"


def voxel_fluents(prefix: str, max_x: int, max_y: int) -> str:
    lines: list[str] = []
    for drone in (1, 2):
        for x in range(1, max_x + 1):
            for y in range(1, max_y + 1):
                for height in range(1, MAX_HEIGHT + 1):
                    lines.append(
                        f"fluent {prefix}_{drone}_{x}_{y}_{height} = "
                        f"<drone[{drone}].arrive[{x}][{y}][{height}], "
                        f"{flight_terminators(drone)}>"
                    )
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def exact_collision_properties(prefix: str, name_prefix: str, max_x: int, max_y: int) -> tuple[str, list[str]]:
    lines: list[str] = []
    property_names: list[str] = []
    for source, target in ((1, 2), (2, 1)):
        for height in range(1, MAX_HEIGHT + 1):
            assertion = f"{name_prefix}_{source}_TO_{target}_H{height}"
            lines.append(f"assert {assertion} = (")
            terms = [
                f"({prefix}_{source}_{x}_{y}_{height} -> "
                f"!drone[{target}].arrive[{x}][{y}][{height}])"
                for x in range(1, max_x + 1)
                for y in range(1, max_y + 1)
            ]
            for index, term in enumerate(terms):
                operator = "\t" if index == 0 else "\t&& "
                lines.append(operator + term)
            lines.append(")")
        lines.append("")

    for source, target in ((1, 2), (2, 1)):
        for height in range(1, MAX_HEIGHT + 1):
            assertion = f"{name_prefix}_{source}_TO_{target}_H{height}"
            property_name = f"P_{assertion}"
            property_names.append(property_name)
            lines.append(f"ltl_property {property_name} = []{assertion}")
    return "\n".join(lines) + "\n", property_names


def safety_list(property_names: list[str]) -> str:
    return ",\n".join(f"\t\t{name}" for name in property_names)


def static_controllers(old_properties: list[str], new_properties: list[str]) -> str:
    return f"""//******************************************************************************
//* Static old and new controllers
//******************************************************************************

controllerSpec OLD_SPEC = {{
\tsafety = {{
{safety_list(old_properties)}
\t}}
\tcontrollable = {{ControllableActions}}
}}

controllerSpec NEW_SPEC = {{
\tsafety = {{
{safety_list(new_properties)}
\t}}
\tcontrollable = {{ControllableActions}}
}}

controller ||C_OLD_DRONE = (OLD_ENV)~{{OLD_SPEC}}.
||OldDrone = (C_OLD_DRONE || OLD_ENV).

controller ||C_NEW_DRONE = (NEW_ENV)~{{NEW_SPEC}}.
||NewDrone = (C_NEW_DRONE || NEW_ENV).
"""


def updating_controllers(transition: str) -> str:
    return UPDATING_CONTROLLERS.replace("__TRANSITION__", transition)


def geofence_model(max_x: int, max_y: int) -> str:
    size = f"{max_x}x{max_y}x{MAX_HEIGHT}"
    old_voxel = (max_x, max_y, MAX_HEIGHT)
    new_voxel = (1, max_y, MAX_HEIGHT)
    old_position = ",".join(str(value) for value in old_voxel)
    new_position = ",".join(str(value) for value in new_voxel)

    header = f"""//******************************************************************************
//* Drone policy update in a fixed {size} airspace: geofence relocation
//*
//* Old geofence : voxel ({old_position}) is forbidden.
//* New geofence : voxel ({new_position}) is forbidden.
//*
//* The relocation rule generalizes the 2x2x2 example: the forbidden voxel
//* moves from (MaxX,MaxY,2) to (1,MaxY,2).
//*
//* hotSwapIn is intentionally not guarded by the geofence-ready condition.
//* It must remain enabled in every pre-update state.  If a drone occupies the
//* new forbidden voxel, the update controller first moves it out while the old
//* specification is still active.  Reconfiguration is allowed only after both
//* drones are outside the new geofence.
//******************************************************************************

"""

    collision_text, collision_properties = exact_collision_properties(
        "OCC", "NO_COLLISION", max_x, max_y
    )
    old_x, old_y, old_h = old_voxel
    new_x, new_y, new_h = new_voxel
    geofence_properties = f"""//******************************************************************************
//* Geofence policies
//******************************************************************************

// The old controller excludes ({old_position}); the new forbidden voxel is allowed.
assert OLD_GEOFENCE_1 = (!drone[1].arrive[{old_x}][{old_y}][{old_h}])
assert OLD_GEOFENCE_2 = (!drone[2].arrive[{old_x}][{old_y}][{old_h}])
ltl_property P_OLD_GEOFENCE_1 = []OLD_GEOFENCE_1
ltl_property P_OLD_GEOFENCE_2 = []OLD_GEOFENCE_2

// The new controller excludes ({new_position}); the old forbidden voxel is released.
assert NEW_GEOFENCE_1 = (!drone[1].arrive[{new_x}][{new_y}][{new_h}])
assert NEW_GEOFENCE_2 = (!drone[2].arrive[{new_x}][{new_y}][{new_h}])
ltl_property P_NEW_GEOFENCE_1 = []NEW_GEOFENCE_1
ltl_property P_NEW_GEOFENCE_2 = []NEW_GEOFENCE_2

"""
    old_properties = ["P_OLD_GEOFENCE_1", "P_OLD_GEOFENCE_2", *collision_properties]
    new_properties = ["P_NEW_GEOFENCE_1", "P_NEW_GEOFENCE_2", *collision_properties]
    transition = f"""//******************************************************************************
//* Transition requirements
//******************************************************************************

fluent AT_NEW_GEOFENCE_1 = <drone[1].arrive[{new_x}][{new_y}][{new_h}], {flight_terminators(1)}>
fluent AT_NEW_GEOFENCE_2 = <drone[2].arrive[{new_x}][{new_y}][{new_h}], {flight_terminators(2)}>

assert NEW_GEOFENCE_READY = (!AT_NEW_GEOFENCE_1 && !AT_NEW_GEOFENCE_2)

// Readiness constrains reconfigure, never hotSwapIn.
// It does not impose an order on the update events; DUCS chooses that order.
assert RECONFIGURE_OUTSIDE_NEW_GEOFENCE =
\t(reconfigure -> NEW_GEOFENCE_READY)

ltl_property T_RECONFIGURE_OUTSIDE_NEW_GEOFENCE =
\tRECONFIGURE_OUTSIDE_NEW_GEOFENCE

"""

    return (
        header
        + movement_model(max_x, max_y)
        + "\n//******************************************************************************\n"
        + "//* Shared exact-voxel collision avoidance\n"
        + "//******************************************************************************\n\n"
        + voxel_fluents("OCC", max_x, max_y)
        + "\n"
        + collision_text
        + "\n"
        + geofence_properties
        + static_controllers(old_properties, new_properties)
        + "\n"
        + transition
        + updating_controllers("T_RECONFIGURE_OUTSIDE_NEW_GEOFENCE")
    )


def column_fluents(max_x: int, max_y: int) -> str:
    lines: list[str] = []
    for drone in (1, 2):
        for x in range(1, max_x + 1):
            for y in range(1, max_y + 1):
                arrivals = ", ".join(
                    f"drone[{drone}].arrive[{x}][{y}][{height}]"
                    for height in range(1, MAX_HEIGHT + 1)
                )
                lines.append(
                    f"fluent COLUMN_{drone}_{x}_{y} = <{{{arrivals}}}, "
                    f"{flight_terminators(drone, include_vertical=False)}>"
                )
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def downwash_properties(max_x: int, max_y: int) -> tuple[str, list[str]]:
    lines: list[str] = []
    property_names: list[str] = []
    for source, target in ((1, 2), (2, 1)):
        for x in range(1, max_x + 1):
            for y in range(1, max_y + 1):
                suffix = f"{x}{y}"
                assertion = f"NEW_NO_DOWNWASH_{source}_TO_{target}_{suffix}"
                arrivals = " || ".join(
                    f"drone[{target}].arrive[{x}][{y}][{height}]"
                    for height in range(1, MAX_HEIGHT + 1)
                )
                lines.extend(
                    (
                        f"assert {assertion} =",
                        f"\t(COLUMN_{source}_{x}_{y} -> !({arrivals}))",
                    )
                )
        lines.append("")

    for source, target in ((1, 2), (2, 1)):
        for x in range(1, max_x + 1):
            for y in range(1, max_y + 1):
                suffix = f"{x}{y}"
                assertion = f"NEW_NO_DOWNWASH_{source}_TO_{target}_{suffix}"
                property_name = f"P_{assertion}"
                property_names.append(property_name)
                lines.append(f"ltl_property {property_name} = []{assertion}")
    return "\n".join(lines) + "\n", property_names


def downwash_model(max_x: int, max_y: int) -> str:
    size = f"{max_x}x{max_y}x{MAX_HEIGHT}"
    header = f"""//******************************************************************************
//* Drone policy update in a fixed {size} airspace: downwash separation
//*
//* Old policy : two drones may share an (x,y) column when their heights differ;
//*              only exact-voxel collisions are forbidden.
//* New policy : two airborne drones may not share an (x,y) column at any
//*              heights, protecting the lower drone from rotor downwash.
//*
//* hotSwapIn is intentionally available in every pre-update state, including
//* states in which the drones are vertically stacked.  After hotSwapIn, the
//* update controller separates the drones before startNewSpec.
//******************************************************************************

"""

    collision_text, old_properties = exact_collision_properties(
        "VOXEL", "OLD_NO_COLLISION", max_x, max_y
    )
    downwash_text, new_properties = downwash_properties(max_x, max_y)
    readiness_terms = [
        f"(COLUMN_1_{x}_{y} && COLUMN_2_{x}_{y})"
        for x in range(1, max_x + 1)
        for y in range(1, max_y + 1)
    ]
    readiness_lines = "\n".join(
        ("\t" if index == 0 else "\t|| ") + term
        for index, term in enumerate(readiness_terms)
    )
    transition = f"""//******************************************************************************
//* Transition requirements
//******************************************************************************

assert DOWNWASH_READY = !(
{readiness_lines}
)

// Readiness constrains startNewSpec, never hotSwapIn.
// It does not impose an order on the update events; DUCS chooses that order.
assert START_WHEN_DOWNWASH_READY = (startNewSpec -> DOWNWASH_READY)

ltl_property T_START_WHEN_DOWNWASH_READY = START_WHEN_DOWNWASH_READY

"""

    return (
        header
        + movement_model(max_x, max_y)
        + "\n//******************************************************************************\n"
        + "//* Old policy: exact-voxel collision avoidance\n"
        + "//******************************************************************************\n\n"
        + voxel_fluents("VOXEL", max_x, max_y)
        + "\n"
        + collision_text
        + "\n//******************************************************************************\n"
        + "//* New policy: no shared vertical column\n"
        + "//******************************************************************************\n\n"
        + "// Vertical moves do not terminate a column fluent; horizontal moves and land do.\n"
        + column_fluents(max_x, max_y)
        + "\n"
        + downwash_text
        + "\n"
        + static_controllers(old_properties, new_properties)
        + "\n"
        + transition
        + updating_controllers("T_START_WHEN_DOWNWASH_READY")
    )


def write_models() -> None:
    for max_x, max_y in AIRSPACE_SIZES:
        size = f"{max_x}x{max_y}x{MAX_HEIGHT}"
        outputs = {
            f"Drone_{size}_geofence_shift.lts": geofence_model(max_x, max_y),
            f"Drone_{size}_downwash_update.lts": downwash_model(max_x, max_y),
        }
        for filename, content in outputs.items():
            if "__" in content:
                raise ValueError(f"unexpanded template token in {filename}")
            (OUTPUT_DIRECTORY / filename).write_text(content, encoding="utf-8")


if __name__ == "__main__":
    write_models()

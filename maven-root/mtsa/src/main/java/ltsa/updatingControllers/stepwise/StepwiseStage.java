package ltsa.updatingControllers.stepwise;

import ltsa.lts.CompactState;
import ltsa.lts.MappingEnvironmentGenerator;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class StepwiseStage {

    private final int index;
    private final String oldEnvironmentName;
    private final String newEnvironmentName;
    private final String mapRelationName;
    private final CompactState oldEnvironment;
    private final CompactState newEnvironment;
    private final CompactState mappingEnvironment;
    private final Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata> mappingStateMetadata;

    public StepwiseStage(
            int index,
            String oldEnvironmentName,
            String newEnvironmentName,
            String mapRelationName,
            CompactState oldEnvironment,
            CompactState newEnvironment,
            CompactState mappingEnvironment) {
        this(index, oldEnvironmentName, newEnvironmentName, mapRelationName,
                oldEnvironment, newEnvironment, mappingEnvironment,
                Collections.<Integer, MappingEnvironmentGenerator.MappingStateMetadata>emptyMap());
    }

    public StepwiseStage(
            int index,
            String oldEnvironmentName,
            String newEnvironmentName,
            String mapRelationName,
            CompactState oldEnvironment,
            CompactState newEnvironment,
            CompactState mappingEnvironment,
            Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata> mappingStateMetadata) {
        this.index = index;
        this.oldEnvironmentName = oldEnvironmentName;
        this.newEnvironmentName = newEnvironmentName;
        this.mapRelationName = mapRelationName;
        this.oldEnvironment = oldEnvironment;
        this.newEnvironment = newEnvironment;
        this.mappingEnvironment = mappingEnvironment;
        this.mappingStateMetadata =
                new HashMap<Integer, MappingEnvironmentGenerator.MappingStateMetadata>(mappingStateMetadata);
    }

    public int getIndex() {
        return index;
    }

    public String getDisplayIndex() {
        return Integer.toString(index + 1);
    }

    public String getOldEnvironmentName() {
        return oldEnvironmentName;
    }

    public String getNewEnvironmentName() {
        return newEnvironmentName;
    }

    public String getMapRelationName() {
        return mapRelationName;
    }

    public CompactState getOldEnvironment() {
        return oldEnvironment;
    }

    public CompactState getNewEnvironment() {
        return newEnvironment;
    }

    public CompactState getMappingEnvironment() {
        return mappingEnvironment;
    }

    public Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata> getMappingStateMetadata() {
        return Collections.unmodifiableMap(mappingStateMetadata);
    }
}

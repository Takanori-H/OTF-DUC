package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking;

import ltsa.updatingControllers.structures.UpdateProtocolSpec;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

public class ProgressRegistry {

    public static final long PRE_UPDATE = 0L;
    public static final long UPDATE_EMPTY = 1L;
    public static final long GOAL = 2L;

    private final UpdateProtocolSpec protocolSpec;
    private final BigInteger allDoneMask;
    private final Map<BigInteger, Long> idByMask = new HashMap<>();
    private final Map<Long, BigInteger> maskById = new HashMap<>();
    private long nextId = 3L;

    public ProgressRegistry(UpdateProtocolSpec protocolSpec) {
        this.protocolSpec = protocolSpec;
        BigInteger mask = BigInteger.ZERO;
        for (int i = 0; i < protocolSpec.progressActionCount(); i++) {
            mask = mask.setBit(i);
        }
        this.allDoneMask = mask;
        idByMask.put(BigInteger.ZERO, UPDATE_EMPTY);
        maskById.put(UPDATE_EMPTY, BigInteger.ZERO);
    }

    public boolean isPreUpdate(long progressId) {
        return progressId == PRE_UPDATE;
    }

    public boolean isGoal(long progressId) {
        return progressId == GOAL;
    }

    public boolean isUpdate(long progressId) {
        return progressId != PRE_UPDATE && progressId != GOAL;
    }

    public boolean isAllDone(long progressId) {
        if (!isUpdate(progressId)) {
            return false;
        }
        return maskFor(progressId).equals(allDoneMask);
    }

    public boolean isCompleted(long progressId, String actionName) {
        if (!protocolSpec.isProgressAction(actionName)) {
            return false;
        }
        if (isGoal(progressId)) {
            return true;
        }
        if (!isUpdate(progressId)) {
            return false;
        }
        return maskFor(progressId).testBit(protocolSpec.getProgressIndex(actionName));
    }

    public long nextFor(long progressId, String actionName) {
        if (protocolSpec.isProgressAction(actionName)) {
            BigInteger next = maskFor(progressId).setBit(protocolSpec.getProgressIndex(actionName));
            return idFor(next);
        }
        return progressId;
    }

    public int completedCount(long progressId) {
        if (!isUpdate(progressId)) {
            return 0;
        }
        return maskFor(progressId).bitCount();
    }

    private BigInteger maskFor(long progressId) {
        BigInteger mask = maskById.get(progressId);
        if (mask == null) {
            throw new IllegalArgumentException("Unknown progress state id: " + progressId);
        }
        return mask;
    }

    private long idFor(BigInteger mask) {
        Long existing = idByMask.get(mask);
        if (existing != null) {
            return existing;
        }
        long id = nextId++;
        idByMask.put(mask, id);
        maskById.put(id, mask);
        return id;
    }
}

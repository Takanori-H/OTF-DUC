package ltsa.lts;

public class CompactStateActionRelabeler {

    public static void relabelAction(CompactState machine, String fromAction, String toAction) {
        if (machine == null || fromAction == null || toAction == null || fromAction.equals(toAction)) {
            return;
        }
        int fromIndex = indexOf(machine.alphabet, fromAction);
        if (fromIndex < 0) {
            return;
        }
        int toIndex = indexOf(machine.alphabet, toAction);
        if (toIndex < 0) {
            machine.alphabet[fromIndex] = toAction;
            return;
        }

        for (int i = 0; i < machine.states.length; i++) {
            rewriteEvents(machine.states[i], fromIndex, toIndex);
        }
        String[] newAlphabet = new String[machine.alphabet.length - 1];
        int write = 0;
        for (int read = 0; read < machine.alphabet.length; read++) {
            if (read == fromIndex) {
                continue;
            }
            newAlphabet[write++] = machine.alphabet[read];
        }
        machine.alphabet = newAlphabet;
        for (int i = 0; i < machine.states.length; i++) {
            shiftEventsAfterRemovedIndex(machine.states[i], fromIndex);
        }
    }

    private static int indexOf(String[] alphabet, String action) {
        if (alphabet == null) {
            return -1;
        }
        for (int i = 0; i < alphabet.length; i++) {
            if (action.equals(alphabet[i])) {
                return i;
            }
        }
        return -1;
    }

    private static void rewriteEvents(EventState state, int fromIndex, int toIndex) {
        EventState p = state;
        while (p != null) {
            EventState q = p;
            while (q != null) {
                if (q.event == fromIndex) {
                    q.event = toIndex;
                }
                if (q instanceof ProbabilisticEventState) {
                    ProbabilisticEventState prob = ((ProbabilisticEventState) q).probTr;
                    while (prob != null) {
                        if (prob.event == fromIndex) {
                            prob.event = toIndex;
                        }
                        prob = prob.probTr;
                    }
                }
                q = q.nondet;
            }
            p = p.list;
        }
    }

    private static void shiftEventsAfterRemovedIndex(EventState state, int removedIndex) {
        EventState p = state;
        while (p != null) {
            EventState q = p;
            while (q != null) {
                if (q.event > removedIndex) {
                    q.event--;
                }
                if (q instanceof ProbabilisticEventState) {
                    ProbabilisticEventState prob = ((ProbabilisticEventState) q).probTr;
                    while (prob != null) {
                        if (prob.event > removedIndex) {
                            prob.event--;
                        }
                        prob = prob.probTr;
                    }
                }
                q = q.nondet;
            }
            p = p.list;
        }
    }
}

package ltsa.updatingControllers.checks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;

public final class TraceLanguageChecker {

    private TraceLanguageChecker() {
    }

    public static EquivalenceResult compare(TransitionGraph left, TransitionGraph right) {
        InclusionResult leftSubsetRight = checkInclusion(left, right);
        InclusionResult rightSubsetLeft = checkInclusion(right, left);
        return new EquivalenceResult(leftSubsetRight, rightSubsetLeft);
    }

    public static InclusionResult checkInclusion(TransitionGraph source, TransitionGraph target) {
        Queue<SearchNode> queue = new ArrayDeque<SearchNode>();
        Set<SearchNodeKey> visited = new HashSet<SearchNodeKey>();
        Set<Integer> initialTarget = new TreeSet<Integer>();
        initialTarget.add(Integer.valueOf(0));
        SearchNode initial = new SearchNode(0, initialTarget, Collections.<String>emptyList());
        queue.add(initial);
        visited.add(initial.key());

        while (!queue.isEmpty()) {
            SearchNode current = queue.remove();
            for (TransitionGraph.Edge edge : source.getOutgoing(current.sourceState)) {
                Set<Integer> targetSuccessors = successors(target, current.targetStates, edge.action);
                if (targetSuccessors.isEmpty()) {
                    List<String> witness = new ArrayList<String>(current.trace);
                    witness.add(edge.action);
                    return InclusionResult.fail(witness,
                            "target has no transition for action '" + edge.action
                                    + "' after source state Q" + current.sourceState);
                }
                if (edge.to < 0) {
                    continue;
                }
                List<String> nextTrace = new ArrayList<String>(current.trace);
                nextTrace.add(edge.action);
                SearchNode next = new SearchNode(edge.to, targetSuccessors, nextTrace);
                SearchNodeKey key = next.key();
                if (visited.add(key)) {
                    queue.add(next);
                }
            }
        }

        return InclusionResult.pass();
    }

    private static Set<Integer> successors(TransitionGraph graph, Set<Integer> states, String action) {
        Set<Integer> result = new TreeSet<Integer>();
        for (Integer state : states) {
            result.addAll(graph.successors(state.intValue(), action));
        }
        return result;
    }

    public static final class InclusionResult {
        public final boolean included;
        public final List<String> witness;
        public final String detail;

        private InclusionResult(boolean included, List<String> witness, String detail) {
            this.included = included;
            this.witness = witness;
            this.detail = detail;
        }

        static InclusionResult pass() {
            return new InclusionResult(true, Collections.<String>emptyList(), "");
        }

        static InclusionResult fail(List<String> witness, String detail) {
            return new InclusionResult(false, witness, detail);
        }

        public String witnessText() {
            if (witness == null || witness.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < witness.size(); i++) {
                if (i > 0) {
                    builder.append(' ');
                }
                builder.append(witness.get(i));
            }
            return builder.toString();
        }
    }

    public static final class EquivalenceResult {
        public final InclusionResult leftSubsetRight;
        public final InclusionResult rightSubsetLeft;

        EquivalenceResult(InclusionResult leftSubsetRight, InclusionResult rightSubsetLeft) {
            this.leftSubsetRight = leftSubsetRight;
            this.rightSubsetLeft = rightSubsetLeft;
        }

        public boolean equivalent() {
            return leftSubsetRight.included && rightSubsetLeft.included;
        }
    }

    private static final class SearchNode {
        final int sourceState;
        final Set<Integer> targetStates;
        final List<String> trace;

        SearchNode(int sourceState, Set<Integer> targetStates, List<String> trace) {
            this.sourceState = sourceState;
            this.targetStates = targetStates;
            this.trace = trace;
        }

        SearchNodeKey key() {
            return new SearchNodeKey(sourceState, targetStates);
        }
    }

    private static final class SearchNodeKey {
        final int sourceState;
        final Set<Integer> targetStates;

        SearchNodeKey(int sourceState, Set<Integer> targetStates) {
            this.sourceState = sourceState;
            this.targetStates = new TreeSet<Integer>(targetStates);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof SearchNodeKey)) {
                return false;
            }
            SearchNodeKey that = (SearchNodeKey) other;
            return sourceState == that.sourceState && targetStates.equals(that.targetStates);
        }

        @Override
        public int hashCode() {
            return 31 * sourceState + targetStates.hashCode();
        }
    }
}

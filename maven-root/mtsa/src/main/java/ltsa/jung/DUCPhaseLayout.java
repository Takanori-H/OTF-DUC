package ltsa.jung;

import edu.uci.ics.jung.algorithms.layout.Layout;
import edu.uci.ics.jung.graph.Graph;

import java.awt.Dimension;
import java.awt.geom.Point2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/**
 * Places DUC graphs by update progress rather than by force-directed distance.
 * The layout is computed lazily in the Layout tab only.
 */
public class DUCPhaseLayout implements Layout<StateVertex, TransitionEdge> {

    private static final int PHASE_PRE = 0;
    private static final int PHASE_000 = 1;
    private static final int PHASE_100 = 2;
    private static final int PHASE_010 = 3;
    private static final int PHASE_001 = 4;
    private static final int PHASE_110 = 5;
    private static final int PHASE_101 = 6;
    private static final int PHASE_011 = 7;
    private static final int PHASE_111 = 8;
    private static final int PHASE_POST = 9;
    private static final int PHASE_MIXED = 10;

    private static final int BIT_STOP = 1;
    private static final int BIT_RECONFIGURE = 2;
    private static final int BIT_START = 4;

    private static final String HOT_SWAP_IN = "hotSwapIn";
    private static final String STOP_OLD_SPEC = "stopOldSpec";
    private static final String RECONFIGURE = "reconfigure";
    private static final String START_NEW_SPEC = "startNewSpec";
    private static final String HOT_SWAP_OUT = "hotSwapOut";
    private static final String FINISH_UPDATE = "finishUpdate";
    private static final String WORSE_RANK_PREFIX = "#w#_";
    private static final String DEBUG_PROPERTY = "updating.controller.layout.ducPhase.debug";
    private static final double OUTER_MARGIN = 60.0;
    private static final double COLUMN_GAP = 0.0;
    private static final double ROW_GAP = 0.0;
    private static final double NODE_SPACING = 46.0;
    private static final double GROUP_PADDING = 45.0;
    private static final double MIN_GROUP_WIDTH = 140.0;
    private static final double MIN_GROUP_HEIGHT = 100.0;
    private static final int MAX_GROUP_ROWS = 8;
    private static final int FORCE_ITERATIONS = 80;

    private Graph<StateVertex, TransitionEdge> graph;
    private Dimension requestedSize = new Dimension(600, 600);
    private Dimension size = new Dimension(600, 600);
    private final Map<StateVertex, Point2D> locations = new HashMap<StateVertex, Point2D>();

    public DUCPhaseLayout(Graph<StateVertex, TransitionEdge> graph) {
        if (graph == null) {
            throw new IllegalArgumentException("Graph must be non-null");
        }
        this.graph = graph;
        buildLayout();
    }

    public static boolean isDucGraph(Graph<StateVertex, TransitionEdge> graph) {
        if (graph == null) {
            return false;
        }
        for (TransitionEdge edge : graph.getEdges()) {
            for (String label : edge.getLabels()) {
                if (isUpdateAction(label)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void buildLayout() {
        locations.clear();

        Map<StateVertex, Set<Integer>> phasesByState = computePhasesByState();
        Map<Integer, List<StateVertex>> groups = new HashMap<Integer, List<StateVertex>>();
        for (StateVertex vertex : graph.getVertices()) {
            int group = groupFor(vertex, phasesByState);
            List<StateVertex> vertices = groups.get(group);
            if (vertices == null) {
                vertices = new ArrayList<StateVertex>();
                groups.put(group, vertices);
            }
            vertices.add(vertex);
        }

        for (List<StateVertex> vertices : groups.values()) {
            Collections.sort(vertices, new StateVertexComparator());
        }
        debugPhases(phasesByState, groups);

        Map<Integer, Area> areas = buildAreas(groups);
        double[] columnWidth = new double[] {
                maxWidth(areas, PHASE_PRE),
                maxWidth(areas, PHASE_000),
                maxWidth(areas, PHASE_100, PHASE_010, PHASE_001),
                maxWidth(areas, PHASE_110, PHASE_101, PHASE_011),
                maxWidth(areas, PHASE_111),
                maxWidth(areas, PHASE_POST)
        };
        double[] rowHeight = new double[] {
                maxHeight(areas, PHASE_100, PHASE_110),
                maxHeight(areas, PHASE_PRE, PHASE_000, PHASE_010, PHASE_101, PHASE_111, PHASE_POST),
                maxHeight(areas, PHASE_001, PHASE_011)
        };

        double[] centerX = centers(columnWidth, OUTER_MARGIN, COLUMN_GAP);
        double[] centerY = centers(rowHeight, OUTER_MARGIN, ROW_GAP);
        double mixedCenterX = (centerX[0] + centerX[centerX.length - 1]) / 2.0;
        Area mixedArea = areas.get(PHASE_MIXED);
        boolean hasMixed = hasVertices(groups, PHASE_MIXED);
        double mixedCenterY = centerY[2] + rowHeight[2] / 2.0 + ROW_GAP + mixedArea.height / 2.0;

        double layoutWidth = Math.max(requestedSize.width,
                centerX[centerX.length - 1] + columnWidth[columnWidth.length - 1] / 2.0 + OUTER_MARGIN);
        if (hasMixed) {
            layoutWidth = Math.max(layoutWidth, mixedCenterX + mixedArea.width / 2.0 + OUTER_MARGIN);
        }
        double layoutHeight = Math.max(requestedSize.height,
                centerY[2] + rowHeight[2] / 2.0 + OUTER_MARGIN);
        if (hasMixed) {
            layoutHeight = Math.max(layoutHeight, mixedCenterY + mixedArea.height / 2.0 + OUTER_MARGIN);
        }
        size = new Dimension((int) Math.ceil(layoutWidth), (int) Math.ceil(layoutHeight));

        placeGroup(groups.get(PHASE_PRE), centerX[0], centerY[1], areas.get(PHASE_PRE));
        placeGroup(groups.get(PHASE_000), centerX[1], centerY[1], areas.get(PHASE_000));

        placeGroup(groups.get(PHASE_100), centerX[2], centerY[0], areas.get(PHASE_100));
        placeGroup(groups.get(PHASE_010), centerX[2], centerY[1], areas.get(PHASE_010));
        placeGroup(groups.get(PHASE_001), centerX[2], centerY[2], areas.get(PHASE_001));

        placeGroup(groups.get(PHASE_110), centerX[3], centerY[0], areas.get(PHASE_110));
        placeGroup(groups.get(PHASE_101), centerX[3], centerY[1], areas.get(PHASE_101));
        placeGroup(groups.get(PHASE_011), centerX[3], centerY[2], areas.get(PHASE_011));

        placeGroup(groups.get(PHASE_111), centerX[4], centerY[1], areas.get(PHASE_111));
        placeGroup(groups.get(PHASE_POST), centerX[5], centerY[1], areas.get(PHASE_POST));
        if (hasMixed) {
            placeGroup(groups.get(PHASE_MIXED), mixedCenterX, mixedCenterY, mixedArea);
        }
    }

    private static Map<Integer, Area> buildAreas(Map<Integer, List<StateVertex>> groups) {
        Map<Integer, Area> result = new HashMap<Integer, Area>();
        for (int phase = PHASE_PRE; phase <= PHASE_MIXED; phase++) {
            result.put(phase, areaFor(groups.get(phase)));
        }
        return result;
    }

    private static Area areaFor(List<StateVertex> vertices) {
        int count = vertices == null ? 0 : vertices.size();
        if (count <= 0) {
            return new Area(MIN_GROUP_WIDTH, MIN_GROUP_HEIGHT);
        }
        int rows = rowsFor(count);
        int columns = Math.max(1, (int) Math.ceil((double) count / rows));
        double width = Math.max(MIN_GROUP_WIDTH, GROUP_PADDING * 2.0 + (columns - 1) * NODE_SPACING);
        double height = Math.max(MIN_GROUP_HEIGHT, GROUP_PADDING * 2.0 + (rows - 1) * NODE_SPACING);
        return new Area(width, height);
    }

    private static int rowsFor(int count) {
        if (count <= 1) {
            return 1;
        }
        return Math.min(MAX_GROUP_ROWS, Math.max(2, (int) Math.ceil(Math.sqrt(count / 3.0))));
    }

    private static double[] centers(double[] sizes, double margin, double gap) {
        double[] result = new double[sizes.length];
        result[0] = margin + sizes[0] / 2.0;
        for (int i = 1; i < sizes.length; i++) {
            result[i] = result[i - 1] + sizes[i - 1] / 2.0 + gap + sizes[i] / 2.0;
        }
        return result;
    }

    private static double maxWidth(Map<Integer, Area> areas, int... phases) {
        double width = MIN_GROUP_WIDTH;
        for (int phase : phases) {
            width = Math.max(width, areas.get(phase).width);
        }
        return width;
    }

    private static double maxHeight(Map<Integer, Area> areas, int... phases) {
        double height = MIN_GROUP_HEIGHT;
        for (int phase : phases) {
            height = Math.max(height, areas.get(phase).height);
        }
        return height;
    }

    private static boolean hasVertices(Map<Integer, List<StateVertex>> groups, int phase) {
        List<StateVertex> vertices = groups.get(phase);
        return vertices != null && !vertices.isEmpty();
    }

    private void placeGroup(List<StateVertex> vertices, double centerX, double centerY, Area area) {
        if (vertices == null || vertices.isEmpty()) {
            return;
        }
        if (vertices.size() == 1) {
            locations.put(vertices.get(0), new Point2D.Double(centerX, centerY));
            return;
        }

        Map<StateVertex, Integer> indexByVertex = new HashMap<StateVertex, Integer>();
        for (int i = 0; i < vertices.size(); i++) {
            indexByVertex.put(vertices.get(i), i);
        }
        double[][] positions = initialPositions(vertices.size(), area);
        List<EdgePair> edges = collectInternalEdges(vertices, indexByVertex);
        relaxPositions(positions, edges, area);
        normalizeAndStore(vertices, positions, centerX, centerY, area);
    }

    private static double[][] initialPositions(int count, Area area) {
        double[][] positions = new double[count][2];
        int rows = rowsFor(count);
        int columns = Math.max(1, (int) Math.ceil((double) count / rows));
        double usableWidth = Math.max(1.0, area.width - GROUP_PADDING * 2.0);
        double usableHeight = Math.max(1.0, area.height - GROUP_PADDING * 2.0);
        for (int i = 0; i < count; i++) {
            int row = i % rows;
            int column = i / rows;
            double xRatio = columns == 1 ? 0.5 : (double) column / (columns - 1);
            double yRatio = rows == 1 ? 0.5 : (double) row / (rows - 1);
            positions[i][0] = -usableWidth / 2.0 + usableWidth * xRatio;
            positions[i][1] = -usableHeight / 2.0 + usableHeight * yRatio;
        }
        return positions;
    }

    private List<EdgePair> collectInternalEdges(List<StateVertex> vertices, Map<StateVertex, Integer> indexByVertex) {
        List<EdgePair> edges = new ArrayList<EdgePair>();
        for (StateVertex vertex : vertices) {
            Integer source = indexByVertex.get(vertex);
            if (source == null) {
                continue;
            }
            for (TransitionEdge edge : graph.getOutEdges(vertex)) {
                StateVertex targetVertex = graph.getDest(edge);
                Integer target = indexByVertex.get(targetVertex);
                if (target != null && !target.equals(source)) {
                    edges.add(new EdgePair(source, target));
                }
            }
        }
        return edges;
    }

    private static void relaxPositions(double[][] positions, List<EdgePair> edges, Area area) {
        int count = positions.length;
        double k = Math.sqrt(Math.max(1.0, area.width * area.height / count)) * 0.75;
        double halfWidth = area.width / 2.0;
        double halfHeight = area.height / 2.0;
        for (int iteration = 0; iteration < FORCE_ITERATIONS; iteration++) {
            double[][] displacement = new double[count][2];
            applyRepulsion(positions, displacement, k);
            applyAttraction(positions, displacement, edges, k);
            double temperature = Math.max(area.width, area.height)
                    * (0.06 * (FORCE_ITERATIONS - iteration) / FORCE_ITERATIONS + 0.01);
            for (int i = 0; i < count; i++) {
                double dx = displacement[i][0];
                double dy = displacement[i][1];
                double length = Math.max(0.0001, Math.sqrt(dx * dx + dy * dy));
                double step = Math.min(temperature, length);
                positions[i][0] = clamp(positions[i][0] + dx / length * step, -halfWidth, halfWidth);
                positions[i][1] = clamp(positions[i][1] + dy / length * step, -halfHeight, halfHeight);
            }
        }
    }

    private static void applyRepulsion(double[][] positions, double[][] displacement, double k) {
        for (int i = 0; i < positions.length; i++) {
            for (int j = i + 1; j < positions.length; j++) {
                double dx = positions[i][0] - positions[j][0];
                double dy = positions[i][1] - positions[j][1];
                double distance = Math.max(1.0, Math.sqrt(dx * dx + dy * dy));
                double force = k * k / distance;
                double fx = dx / distance * force;
                double fy = dy / distance * force;
                displacement[i][0] += fx;
                displacement[i][1] += fy;
                displacement[j][0] -= fx;
                displacement[j][1] -= fy;
            }
        }
    }

    private static void applyAttraction(
            double[][] positions,
            double[][] displacement,
            List<EdgePair> edges,
            double k) {
        for (EdgePair edge : edges) {
            double dx = positions[edge.source][0] - positions[edge.target][0];
            double dy = positions[edge.source][1] - positions[edge.target][1];
            double distance = Math.max(1.0, Math.sqrt(dx * dx + dy * dy));
            double force = distance * distance / k * 0.10;
            double fx = dx / distance * force;
            double fy = dy / distance * force;
            displacement[edge.source][0] -= fx;
            displacement[edge.source][1] -= fy;
            displacement[edge.target][0] += fx;
            displacement[edge.target][1] += fy;
        }
    }

    private void normalizeAndStore(
            List<StateVertex> vertices,
            double[][] positions,
            double centerX,
            double centerY,
            Area area) {
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] position : positions) {
            minX = Math.min(minX, position[0]);
            maxX = Math.max(maxX, position[0]);
            minY = Math.min(minY, position[1]);
            maxY = Math.max(maxY, position[1]);
        }

        double padding = Math.min(GROUP_PADDING, Math.min(area.width, area.height) / 3.0);
        double usableWidth = Math.max(1.0, area.width - padding * 2.0);
        double usableHeight = Math.max(1.0, area.height - padding * 2.0);
        double rangeX = Math.max(0.0001, maxX - minX);
        double rangeY = Math.max(0.0001, maxY - minY);
        for (int i = 0; i < vertices.size(); i++) {
            double xRatio = (positions[i][0] - minX) / rangeX;
            double yRatio = (positions[i][1] - minY) / rangeY;
            double x = centerX - area.width / 2.0 + padding + usableWidth * xRatio;
            double y = centerY - area.height / 2.0 + padding + usableHeight * yRatio;
            locations.put(vertices.get(i), new Point2D.Double(x, y));
        }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private int groupFor(StateVertex vertex, Map<StateVertex, Set<Integer>> phasesByState) {
        Set<Integer> phases = phasesByState.get(vertex);
        if (phases == null || phases.isEmpty()) {
            return PHASE_MIXED;
        }
        if (phases.size() != 1) {
            return PHASE_MIXED;
        }
        return phases.iterator().next();
    }

    private Map<StateVertex, Set<Integer>> computePhasesByState() {
        Map<StateVertex, Set<Integer>> result = new HashMap<StateVertex, Set<Integer>>();
        StateVertex initial = findInitialVertex();
        if (initial == null) {
            return result;
        }

        Set<PhaseNode> visited = new HashSet<PhaseNode>();
        Queue<PhaseNode> queue = new ArrayDeque<PhaseNode>();
        PhaseNode first = new PhaseNode(initial, PHASE_PRE);
        visited.add(first);
        queue.add(first);

        while (!queue.isEmpty()) {
            PhaseNode current = queue.remove();
            Set<Integer> phases = result.get(current.vertex);
            if (phases == null) {
                phases = new HashSet<Integer>();
                result.put(current.vertex, phases);
            }
            phases.add(current.phase);

            for (TransitionEdge edge : graph.getOutEdges(current.vertex)) {
                StateVertex nextVertex = graph.getDest(edge);
                if (nextVertex == null) {
                    continue;
                }
                for (Integer nextPhase : nextPhases(current.phase, edge)) {
                    PhaseNode next = new PhaseNode(nextVertex, nextPhase);
                    if (visited.add(next)) {
                        queue.add(next);
                    }
                }
            }
        }
        return result;
    }

    private StateVertex findInitialVertex() {
        StateVertex fallback = null;
        for (StateVertex vertex : graph.getVertices()) {
            if (fallback == null) {
                fallback = vertex;
            }
            if (vertex.getStateName() == 0) {
                return vertex;
            }
        }
        return fallback;
    }

    private Set<Integer> nextPhases(int phase, TransitionEdge edge) {
        Set<Integer> result = new HashSet<Integer>();
        for (String label : edge.getLabels()) {
            result.add(nextPhase(phase, label));
        }
        if (result.isEmpty()) {
            result.add(phase);
        }
        return result;
    }

    private static int nextPhase(int phase, String label) {
        String action = normalizeAction(label);
        if (phase == PHASE_POST) {
            return PHASE_POST;
        }
        if (phase == PHASE_PRE) {
            return HOT_SWAP_IN.equals(action) ? PHASE_000 : PHASE_PRE;
        }
        if (HOT_SWAP_OUT.equals(action) || FINISH_UPDATE.equals(action)) {
            return PHASE_POST;
        }
        if (HOT_SWAP_IN.equals(action)) {
            return phase;
        }

        int mask = maskFromPhase(phase);
        if (STOP_OLD_SPEC.equals(action)) {
            mask |= BIT_STOP;
        } else if (RECONFIGURE.equals(action)) {
            mask |= BIT_RECONFIGURE;
        } else if (START_NEW_SPEC.equals(action)) {
            mask |= BIT_START;
        }
        return phaseFromMask(mask);
    }

    private static int phaseFromMask(int mask) {
        switch (mask) {
            case 0:
                return PHASE_000;
            case BIT_STOP:
                return PHASE_100;
            case BIT_RECONFIGURE:
                return PHASE_010;
            case BIT_START:
                return PHASE_001;
            case BIT_STOP | BIT_RECONFIGURE:
                return PHASE_110;
            case BIT_STOP | BIT_START:
                return PHASE_101;
            case BIT_RECONFIGURE | BIT_START:
                return PHASE_011;
            default:
                return PHASE_111;
        }
    }

    private static int maskFromPhase(int phase) {
        switch (phase) {
            case PHASE_100:
                return BIT_STOP;
            case PHASE_010:
                return BIT_RECONFIGURE;
            case PHASE_001:
                return BIT_START;
            case PHASE_110:
                return BIT_STOP | BIT_RECONFIGURE;
            case PHASE_101:
                return BIT_STOP | BIT_START;
            case PHASE_011:
                return BIT_RECONFIGURE | BIT_START;
            case PHASE_111:
                return BIT_STOP | BIT_RECONFIGURE | BIT_START;
            default:
                return 0;
        }
    }

    private static boolean isUpdateAction(String label) {
        String action = normalizeAction(label);
        return HOT_SWAP_IN.equals(action)
                || STOP_OLD_SPEC.equals(action)
                || RECONFIGURE.equals(action)
                || START_NEW_SPEC.equals(action)
                || HOT_SWAP_OUT.equals(action)
                || FINISH_UPDATE.equals(action);
    }

    private static String normalizeAction(String label) {
        if (label == null) {
            return "";
        }
        String action = label;
        if (action.startsWith(WORSE_RANK_PREFIX)) {
            action = action.substring(WORSE_RANK_PREFIX.length());
        }
        if (action.endsWith(".old")) {
            action = action.substring(0, action.length() - ".old".length());
        }
        return action;
    }

    private static void debugPhases(
            Map<StateVertex, Set<Integer>> phasesByState,
            Map<Integer, List<StateVertex>> groups) {
        if (!Boolean.getBoolean(DEBUG_PROPERTY)) {
            return;
        }
        System.out.println("[DUCPhaseLayout] phase assignment");
        for (Map.Entry<Integer, List<StateVertex>> entry : groups.entrySet()) {
            List<StateVertex> vertices = new ArrayList<StateVertex>(entry.getValue());
            Collections.sort(vertices, new StateVertexComparator());
            for (StateVertex vertex : vertices) {
                System.out.println("[DUCPhaseLayout] state=" + vertex.getStateName()
                        + " group=" + phaseName(entry.getKey())
                        + " phases=" + phaseSetName(phasesByState.get(vertex)));
            }
        }
    }

    private static String phaseSetName(Set<Integer> phases) {
        if (phases == null || phases.isEmpty()) {
            return "[]";
        }
        List<Integer> sorted = new ArrayList<Integer>(phases);
        Collections.sort(sorted);
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(phaseName(sorted.get(i)));
        }
        builder.append("]");
        return builder.toString();
    }

    private static String phaseName(int phase) {
        switch (phase) {
            case PHASE_PRE:
                return "PRE";
            case PHASE_000:
                return "{}";
            case PHASE_100:
                return "{stopOldSpec}";
            case PHASE_010:
                return "{reconfigure}";
            case PHASE_001:
                return "{startNewSpec}";
            case PHASE_110:
                return "{stopOldSpec,reconfigure}";
            case PHASE_101:
                return "{stopOldSpec,startNewSpec}";
            case PHASE_011:
                return "{reconfigure,startNewSpec}";
            case PHASE_111:
                return "{stopOldSpec,reconfigure,startNewSpec}";
            case PHASE_POST:
                return "POST";
            case PHASE_MIXED:
                return "MIXED";
            default:
                return "UNKNOWN";
        }
    }

    public void initialize() {
    }

    public void reset() {
        buildLayout();
    }

    public void setInitializer(org.apache.commons.collections15.Transformer<StateVertex, Point2D> initializer) {
    }

    public void setGraph(Graph<StateVertex, TransitionEdge> graph) {
        if (graph == null) {
            throw new IllegalArgumentException("Graph must be non-null");
        }
        this.graph = graph;
        buildLayout();
    }

    public Graph<StateVertex, TransitionEdge> getGraph() {
        return graph;
    }

    public Dimension getSize() {
        return size;
    }

    public void setSize(Dimension size) {
        if (size != null) {
            this.requestedSize = new Dimension(size);
            buildLayout();
        }
    }

    public boolean isLocked(StateVertex vertex) {
        return false;
    }

    public void lock(StateVertex vertex, boolean state) {
    }

    public void setLocation(StateVertex vertex, Point2D location) {
        locations.put(vertex, location);
    }

    public Point2D transform(StateVertex vertex) {
        Point2D location = locations.get(vertex);
        if (location == null) {
            location = new Point2D.Double(size.width / 2.0, size.height / 2.0);
            locations.put(vertex, location);
        }
        return location;
    }

    private static final class Area {
        private final double width;
        private final double height;

        private Area(double width, double height) {
            this.width = width;
            this.height = height;
        }
    }

    private static final class EdgePair {
        private final int source;
        private final int target;

        private EdgePair(int source, int target) {
            this.source = source;
            this.target = target;
        }
    }

    private static final class PhaseNode {
        private final StateVertex vertex;
        private final int phase;

        private PhaseNode(StateVertex vertex, int phase) {
            this.vertex = vertex;
            this.phase = phase;
        }

        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof PhaseNode)) {
                return false;
            }
            PhaseNode other = (PhaseNode) object;
            return phase == other.phase && vertex == other.vertex;
        }

        public int hashCode() {
            return Objects.hash(System.identityHashCode(vertex), phase);
        }
    }

    private static final class StateVertexComparator implements java.util.Comparator<StateVertex> {
        public int compare(StateVertex left, StateVertex right) {
            int byState = left.getStateName() - right.getStateName();
            if (byState != 0) {
                return byState;
            }
            return left.getGraphName().compareTo(right.getGraphName());
        }
    }
}

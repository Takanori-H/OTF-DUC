package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;
import java.util.*;

import ltsa.lts.CompactState;
import ltsa.lts.CompositeState;

public class ArchitectureUtils {


    public static void measureArchitectureIndex(CompositeState compositeState) {

        // first make automatas as graph
        Map<CompactState, Integer> ltsToIdx = new HashMap<>();
        Graph<Integer> graph = new Graph<Integer>();
        Integer ltsIdx = 0;
        for(CompactState lts : compositeState.getMachines()) {
            graph.addVertex(ltsIdx);
            ltsToIdx.put(lts, ltsIdx);
            ltsIdx++;
        }

        for(CompactState lts : compositeState.getMachines()){
            for(CompactState ltsToConnect : compositeState.getMachines()){
                if(!lts.equals(ltsToConnect)){
                    Set<String> ltsAlphabet = new HashSet<>(lts.getAlphabetV());
                    ltsAlphabet.retainAll(ltsToConnect.getAlphabetV());
                    ltsAlphabet.remove("tau?");
                    ltsAlphabet.remove("tau");
                    if(!ltsAlphabet.isEmpty()){
                        graph.addEdge(
                                ltsToIdx.get(lts),
                                ltsToIdx.get(ltsToConnect),
                                ltsAlphabet.size(),
                                false
                        );
                    }
                }
            }

        }
        
        DecentralizationMetric metric = new DecentralizationMetric(graph);
        double clusteringCoefficient = metric.clusteringCoefficient();
        Map<Integer, Double> betweenessCentrality = metric.betweennessCentrality();
        Map<Integer, Integer> degreeDistribution = metric.degreeDistribution();
        double score = metric.decentralizationScore(1, 1);
        System.currentTimeMillis();
    }
}

class DecentralizationMetric {
    private Graph<Integer> graph;

    public DecentralizationMetric(Graph<Integer> graph) {
        this.graph = graph;
    }

    public double clusteringCoefficient() {
        // Calculate the average clustering coefficient of the graph
        // Return the average clustering coefficient
        double totalCoefficient = 0.0;
        for (Integer node : graph.getVertices()) {
            List<Edge<Integer>> neighbors = graph.getEdges(node);
            int n = neighbors.size();
            if (n < 2) continue;

            int links = 0;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (graph.hasEdge(neighbors.get(i).destination, neighbors.get(j).destination)) {
                        links++;
                    }
                }
            }
            double coefficient = (2.0 * links) / (n * (n - 1));
            totalCoefficient += coefficient;
        }
        return totalCoefficient / graph.getVertexCount();
    }

    public Map<Integer, Double> betweennessCentrality() {
        // Calculate betweenness centrality for each node
        // Return a map of node to its betweenness centrality value
        Map<Integer, Double> centrality = new HashMap<>();
        for (Integer node : graph.getVertices()) {
            centrality.put(node, 0.0);
        }

        for (Integer source : graph.getVertices()) {
            Deque<Integer> stack = new ArrayDeque<>();
            Map<Integer, List<Integer>> predecessors = new HashMap<>();
            Map<Integer, Integer> sigma = new HashMap<>();
            Map<Integer, Integer> distance = new HashMap<>();
            Queue<Integer> queue = new LinkedList<>();

            for (Integer v : graph.getVertices()) {
                predecessors.put(v, new LinkedList<>());
                sigma.put(v, 0);
                distance.put(v, -1);
            }
            sigma.put(source, 1);
            distance.put(source, 0);
            queue.add(source);

            while (!queue.isEmpty()) {
                Integer v = queue.poll();
                stack.push(v);
                for (Edge<Integer> edge : graph.getEdges(v)) {
                    Integer w = edge.destination;
                    if (distance.get(w) < 0) {
                        queue.add(w);
                        distance.put(w, distance.get(v) + 1);
                    }
                    if (distance.get(w) == distance.get(v) + 1) {
                        sigma.put(w, sigma.get(w) + sigma.get(v));
                        predecessors.get(w).add(v);
                    }
                }
            }

            Map<Integer, Double> delta = new HashMap<>();
            for (Integer v : graph.getVertices()) {
                delta.put(v, 0.0);
            }

            while (!stack.isEmpty()) {
                Integer w = stack.pop();
                for (Integer v : predecessors.get(w)) {
                    delta.put(v, delta.get(v) + (sigma.get(v) / (double) sigma.get(w)) * (1 + delta.get(w)));
                }
                if (w != source) {
                    centrality.put(w, centrality.get(w) + delta.get(w));
                }
            }
        }

        for (Integer v : graph.getVertices()) {
            centrality.put(v, centrality.get(v) / 2.0);
        }

        return centrality;
    }

    public Map<Integer, Integer> degreeDistribution() {
        Map<Integer, Integer> degreeDistribution = new HashMap<>();
        for (Integer node : graph.getVertices()) {
            degreeDistribution.put(node, graph.getEdges(node).size());
        }
        return degreeDistribution;
    }
    public Map<Integer, Integer> degreeWeightedDistribution() {
        Map<Integer, Integer> degreeDistribution = new HashMap<>();
        for (Integer node : graph.getVertices()) {
            double sumEdge = 0;
            for(Edge<Integer> edge : graph.getEdges(node)){
                sumEdge += edge.weight;
            }
            degreeDistribution.put(node, (int) sumEdge);
        }
        return degreeDistribution;
    }

    public double averageDegree() {
        int totalDegree = 0;
        for (Integer node : graph.getVertices()) {
            totalDegree += graph.getEdges(node).size();
        }
        return (double) totalDegree / graph.getVertices().size();
    }

    public double degreeVariance(double averageDegree) {
        double variance = 0.0;
        for (Integer node : graph.getVertices()) {
            double degree = graph.getEdges(node).size();
            variance += Math.pow(degree - averageDegree, 2);
        }
        return variance / graph.getVertices().size();
    }

    public double decentralizationScore(double alpha, double beta) {
        // Combine the metrics into a single score
        // Example: return some function of clusteringCoefficient, betweennessCentrality, and degreeDistribution
        double clusteringCoeff = clusteringCoefficient();
        Map<Integer, Integer> degreeDistribution = degreeWeightedDistribution();

        double avgDegree = averageDegree();
        double degreeVariance = degreeVariance(avgDegree);

        Map<Integer, Double> betweennessCentrality = betweennessCentrality();
        double maxBetweennessCentrality = Collections.max(betweennessCentrality.values());

        double totalNodes = graph.getVertices().size();
        double maxDegreeVariance = Math.pow(totalNodes - 1, 2) / totalNodes;

        return alpha * (1 - (maxBetweennessCentrality / totalNodes)) +
                beta * (1 - (degreeVariance / maxDegreeVariance));
    }
}

class TarjanSCC {
    private int index = 0;
    private Stack<Integer> stack = new Stack<>();
    private Map<Integer, Integer> indices = new HashMap<>();
    private Map<Integer, Integer> lowlink = new HashMap<>();
    private Set<Integer> onStack = new HashSet<>();
    private List<List<Integer>> SCCs = new ArrayList<>();

    public List<List<Integer>> tarjanSCC(Map<Integer, List<Edge<Integer>>> graph) {
        for (Integer v : graph.keySet()) {
            if (!indices.containsKey(v)) {
                strongconnect(v, graph);
            }
        }
        return SCCs;
    }

    private void strongconnect(int v, Map<Integer, List<Edge<Integer>>> graph) {
        indices.put(v, index);
        lowlink.put(v, index);
        index++;
        stack.push(v);
        onStack.add(v);

        for (Edge<Integer> w : graph.get(v)) {
            if (!indices.containsKey(w.destination)) {
                strongconnect(w.destination, graph);
                lowlink.put(v, Math.min(lowlink.get(v), lowlink.get(w.destination)));
            } else if (onStack.contains(w.destination)) {
                lowlink.put(v, Math.min(lowlink.get(v), indices.get(w.destination)));
            }
        }

        if (lowlink.get(v).equals(indices.get(v))) {
            List<Integer> currentSCC = new ArrayList<>();
            int w;
            do {
                w = stack.pop();
                onStack.remove(w);
                currentSCC.add(w);
            } while (w != v);
            SCCs.add(currentSCC);
        }
    }
}

class Graph<T> {

    // We use Hashmap to store the edges in the graph
    public Map<T, List<Edge<T>>> map = new HashMap<>();

    public Map<T, List<Edge<T>>> getMap(){
        return map;
    }

    // This function adds a new vertex to the graph
    public void addVertex(T s) {
        map.putIfAbsent(s, new LinkedList<>());
    }

    // This function adds the edge between source to destination with a weight
    public void addEdge(T source, T destination, double weight, boolean bidirectional) {
        if (!map.containsKey(source)) addVertex(source);
        if (!map.containsKey(destination)) addVertex(destination);

        map.get(source).add(new Edge<>(destination, weight));
        if (bidirectional) {
            map.get(destination).add(new Edge<>(source, weight));
        }
    }

    // This function gives the count of vertices
    public int getVertexCount() {
        return map.keySet().size();
    }
    
    public Collection<T> getVertices(){
        return map.keySet();
    }

    public List<Edge<T>> getEdges(T node){
        return map.get(node);
    }


    // This function gives the count of edges
    public void getEdgesCount(boolean bidirectional) {
        int count = 0;
        for (T v : map.keySet()) {
            count += map.get(v).size();
        }
        if (bidirectional) {
            count = count / 2;
        }
        System.out.println("The graph has " + count + " edges.");
    }

    // This function gives whether a vertex is present or not.
    public void hasVertex(T s) {
        if (map.containsKey(s)) {
            System.out.println("The graph contains " + s + " as a vertex.");
        } else {
            System.out.println("The graph does not contain " + s + " as a vertex.");
        }
    }

    // This function gives whether an edge is present or not.
    public boolean hasEdge(T s, T d) {
        boolean found = false;
        for (Edge<T> edge : map.getOrDefault(s, Collections.emptyList())) {
            if (edge.destination.equals(d)) {
                found = true;
                break;
            }
        }
        return found;
    }

    // This function prints the neighbors of a vertex
    public void neighbours(T s) {
        if (!map.containsKey(s)) return;
        System.out.println("The neighbours of " + s + " are");
        for (Edge<T> edge : map.get(s)) {
            System.out.print(edge.destination + " (weight: " + edge.weight + "), ");
        }
        System.out.println();
    }

    // Prints the adjacency list of each vertex.
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();

        for (T v : map.keySet()) {
            builder.append(v.toString()).append(": ");
            for (Edge<T> edge : map.get(v)) {
                builder.append(edge.destination.toString()).append(" (weight: ").append(edge.weight).append(") ");
            }
            builder.append("\n");
        }

        return builder.toString();
    }


}
class Edge<T> {
    T destination;
    double weight;

    Edge(T destination, double weight) {
        this.destination = destination;
        this.weight = weight;
    }
}
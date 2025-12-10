package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import MTSTools.ac.ic.doc.commons.relations.BinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import ltsa.lts.LTSOutput;
import org.ejml.data.DMatrixRMaj;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;


public class TransitiveClosureUtils {


    static BitSet[] multiply(BitSet[] a, BitSet[] b) {
        int n = a.length;
        BitSet[] result = new BitSet[n];
        for (int i = 0; i < n; i++) {
            result[i] = new BitSet(n);
            for (int k = a[i].nextSetBit(0); k >= 0; k = a[i].nextSetBit(k + 1)) {
                result[i].or(b[k]);
            }
        }
        return result;
    }

    public static BitSet[] searchPathsWithMatrix(MTS<Long, String> env, Set<String> allowedLabels, LTSOutput output) throws InterruptedException {

        // First, i have to prepare graph of transitions
        Set<Long> states = env.getStates();
        int n = env.getStates().size();
        BitSet[] closure = new BitSet[n+1];
        for (int i = 0; i < n+1; i++) {
            closure[i] = new BitSet(n+1);
        }
        for (Long source : states) {
            int sourceIndex = (source == -1) ? n : source.intValue();

            for (Pair<String, Long> edge : env.getTransitions(source, MTS.TransitionType.REQUIRED)) {
                String label = edge.getFirst();
                Long dst = edge.getSecond();
                int dstIndex = (dst == -1) ? n : dst.intValue();

                if (allowedLabels.contains(label)) {
                    closure[sourceIndex].set(dstIndex);
                }
            }

            // Reflexive closure
            if(sourceIndex!=n)
                closure[sourceIndex].set(sourceIndex);
        }

        boolean changed;
        do {
            changed = false;
            BitSet[] product = multiply(closure, closure);
            for (int i = 0; i < n; i++) {
                BitSet old = (BitSet) closure[i].clone();
                closure[i].or(product[i]);
                if (!closure[i].equals(old)) {
                    changed = true;
                }
            }
        } while (changed);

        return closure;
    }

    /**
     * Returns a Hashmap with === state -> (label1 -> <pred1, pred2, pred3>, label2 -> <pred4, pred2>) **
     **/
    public static HashMap<Long, HashMap<String, Set<Long>>> getPredecessors(MTS<Long, String> lts) {

        HashMap<Long, HashMap<String, Set<Long>>> sourceLocal = new HashMap<Long, HashMap<String, Set<Long>>>();
        Set<Long> states = lts.getStates();

        for(Long state : states){
            sourceLocal.put(state, new HashMap<String, Set<Long>>());

        }
        for(Long state : states){
            BinaryRelation<String, Long> transitions
                    = lts.getTransitions(state, MTS.TransitionType.REQUIRED);

            for(Pair<String, Long> transition : transitions){
                String label = transition.getFirst();
                Long dst = transition.getSecond();
                sourceLocal.get(dst).putIfAbsent(label, new HashSet<>());

                sourceLocal.get(dst).get(label).add(state);
            }
        }
        assertEquals(sourceLocal.keySet(), lts.getStates());

        return sourceLocal;
    }


}

class BitMatrix {
    private final int rows, cols;
    private final BitSet[] data;

    public BitMatrix(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.data = new BitSet[rows];
        for (int i = 0; i < rows; i++) {
            data[i] = new BitSet(cols);
        }
    }

    public void set(int row, int col, boolean value) {
        data[row].set(col, value);
    }

    public boolean get(int row, int col) {
        return data[row].get(col);
    }

    public int getRows() { return rows; }
    public int getCols() { return cols; }

    public BitMatrix multiply(BitMatrix other) {
        if (this.cols != other.rows) throw new IllegalArgumentException("Incompatible matrix sizes");
        BitMatrix result = new BitMatrix(this.rows, other.cols);

        for (int i = 0; i < this.rows; i++) {
            for (int k = data[i].nextSetBit(0); k >= 0; k = data[i].nextSetBit(k + 1)) {
                result.data[i].or(other.data[k]);
            }
        }
        return result;
    }

    public boolean equals(BitMatrix other) {
        if (this.rows != other.rows || this.cols != other.cols) return false;
        for (int i = 0; i < rows; i++) {
            if (!this.data[i].equals(other.data[i])) return false;
        }
        return true;
    }

    public void or(BitMatrix other) {
        for (int i = 0; i < rows; i++) {
            this.data[i].or(other.data[i]);
        }
    }

    public BitMatrix copy() {
        BitMatrix copy = new BitMatrix(rows, cols);
        for (int i = 0; i < rows; i++) {
            copy.data[i] = (BitSet) this.data[i].clone();
        }
        return copy;
    }
}
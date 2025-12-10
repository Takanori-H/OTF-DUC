package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction;

public class NumericEstimate extends HEstimate {

    public NumericEstimate(Integer value) {
        super(1); // Create an HEstimate with one slot
        // Since HEstimate relies on HDist comparisons, we need to ensure that NumericEstimate
        // (which wraps a single Number) behaves correctly when compared to HEstimate instances.
        values.add(new HDist(value, 0)); // So we store the value plus a zero distance
        // TODO remove the need of HEstimate (or HDist), Recommendations should be able to receive an int/float
    }

    @Override
    public int compareTo(HEstimate o) {
        return get(0).compareTo(o.get(0));
    }
}

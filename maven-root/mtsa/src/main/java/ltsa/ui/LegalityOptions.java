package ltsa.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Set;
import java.util.Vector;

import ltsa.lts.CompactState;
import ltsa.lts.LabelSet;

public class LegalityOptions {

    
    public int source;
    public int target;
    public Vector<CompactState> machines;
    private Hashtable<String, LabelSet> labelSetConstants;
    public Set<String> actionSet;
    public ArrayList<String> labels;
    
    public LegalityOptions(Vector<CompactState> machines, Hashtable<String,LabelSet> labelSetConstants) {
        this.machines =  machines;
        this.labelSetConstants = labelSetConstants;
        
        this.labels = new ArrayList<String>();
        // for each machine
        for (CompactState machine : machines) {
            // for each label set in the machine
            String[] alphabet = machine.alphabet;

            // remove strings ending with ? and tau
            for (String label : alphabet) {
                if (!label.endsWith("?") && !label.equals("tau") && !this.labels.contains(label)) {
                	// add label to the list
                    this.labels.add(label);
                }
            }
        }
    }

    public boolean isValid() {
        return (source != target) && (actionSet != null);
    }

    public CompactState getSourceModel() {
        // TODO Auto-generated method stub
        return this.machines.get(this.source);
    }

    public CompactState getTargetModel() {
        // TODO Auto-generated method stub
        return this.machines.get(this.target);
    }
    
    public Enumeration<String> getLabelSetNames() {
        return this.labelSetConstants.keys();    
    }
    
}
